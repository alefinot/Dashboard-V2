#!/usr/bin/env python3
"""Heap-recovery gate checker - the executable form of the plan's acceptance.

The 2.0.x heap work (Implementation plans/HEAP-RECOVERY-2026-11-02.md) is
accepted on BEHAVIOUR, not on a heap number: "a settings save never trips the
mem-saver", "a full install completes", "the dashboard comes back". Those are
sentences a person can nod at, so this script turns them into a pass/fail over a
bench session and exits non-zero when one is not true.

    # evaluate an existing USB capture
    python scripts/heap_gates.py check --log Local_artifacts/logs/run.log [--ota-attempted]

    # drive the bench unit end to end, then evaluate
    python scripts/heap_gates.py bench --port COM7 --restore --ota .pio/build/esp32dev/firmware.bin \
        --minutes 30 --out Local_artifacts/logs/heap-run.log

Gates (plan Task 5):

  G1  A full settings save / restore never runs the memory-saver.
  G2  The config.set memory gate costs zero 50 ms waits.
  G3  A full BLE install completes and ota.begin is never refused for memory.
  G4  The dashboard is intact after an install: the speed sprite is rebuilt, and
      the memory-saver is not left latched.
  G5  No crash, no new stall class, frame rate kept, and the soak heap stays
      above the floor.

The `bench` mode re-uses scripts/ble_bench_central.py for the parts that are
already proven against this firmware (the USB log reader, the frame reassembler,
the passkey pairing ceremony), and adds the two things that script does not do:
writing the whole config back (the save/restore scenario) and pushing a firmware
image over the OTA characteristic (the install scenario).

The restore is the app's own: read config.get, split the values into messages
that fit the unit's 8192 B reassembly buffer (RpcFraming.split in the app), and
send them back. That is what makes the measurement about the real save path.
"""

import argparse
import asyncio
import json
import os
import re
import sys
import time
import zlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ble_bench_central as bbc  # noqa: E402  (same folder; the proven client plumbing)

F_OTA = 8                     # BT_F_OTA in src/bt_proto.h
OTA_UUID = "d9a1f003-6c1e-4b8a-9f2d-3e7c1a5b9d40"
BT_MAX_MSG = 8192             # BT_MAX_MSG in src/bt_proto.h
ENVELOPE_RESERVE = 128        # android RpcFraming.ENVELOPE_RESERVE
MAX_BODY_BYTES = BT_MAX_MSG - ENVELOPE_RESERVE
OTA_SILENCE_MS = 120000       # OTA_SILENCE_MS in src/bt_ota.cpp
OTA_OUTSTANDING = 32768       # OtaController.OUTSTANDING_BYTES in the app

# What the unit's heartbeat prints. One regex per field, so a firmware change
# that renames a field fails loudly here instead of reading as a pass.
HB_RE = re.compile(
    r"^HB: up=(\d+)s?s? fps=([\d.]+).*heap=(\d+) min=(\d+) maxAlloc=(\d+) "
    r"sp=(\d+) fallback=(\d+) otaReq=(\d+) memAct=(\d+) bt=(\d+) pair=(\d{6}) "
    r"temp=([\d.]+) maxFrame=(\d+)ms over24=(\d+) sMaxGap=(\d+)ms")
CONFIGSET_RE = re.compile(
    r"CONFIGSET keys=(\d+) payload=(\d+) B heap (\d+)->(\d+)\(copied\)->(\d+)\(applied\) "
    r"maxalloc (\d+)->(\d+) gate_waits=(\d+) gate_ms=(\d+)ms")
MEM_SAVER_RE = re.compile(r"MEM SAVER: dropped speed sprite")
MEM_REQUEST_RE = re.compile(r"config\.set: heap \d+ B, requesting memory-saver")
SPRITE_BUILT_RE = re.compile(r"Speed sprite rebuilt")
SPRITE_SKIP_RE = re.compile(r"Speed sprite skipped: maxAlloc=(\d+) < need=(\d+)")
OTA_BEGIN_REFUSED_RE = re.compile(r"RPC: ota\.begin refused")
OTA_VERIFIED_RE = re.compile(r"OTA: image verified in (\d+)ms")
OTA_RING_RE = re.compile(r"OTA: ring high-water (\d+)/(\d+) frames, longest (\d+)ms between drains")
OTA_QUIET_OPEN_RE = re.compile(r"OTA quiet: open")
OTA_QUIET_CLOSE_RE = re.compile(r"OTA quiet: closed")
OTA_ACCEPTED_RE = re.compile(r"OTA: image accepted")
BOOT_DONE_RE = re.compile(r"Setup done|DIAG BUILD")
OTA_STOPPED_RE = re.compile(r"OTA: install stopped \((\w+)\)")
CRASH_RES = [
    re.compile(r"Guru Meditation Error"),
    re.compile(r"abort\(\) was called"),
    re.compile(r"ETS\((0x[0-9A-F]+)\)"),
    re.compile(r"panic: (?:illegal|instr|load|store)"),
    re.compile(r"backtrace:0x"),
    re.compile(r"Brownout detector was triggered"),
    re.compile(r"Rebooted after watchdog|task watchdog"),
]
STALL_RE = re.compile(r"STALL: loop gap (\d+)ms")
BAD_ALLOC_RE = re.compile(r"bad_alloc|std::bad_alloc")


def reset_bench(port):
    """Pulse DTR/RTS so a gated run always starts from a clean boot.

    The memory-saver latches for the whole boot: once it has run, memAct stays 1
    until the unit is reset. A gate that reads a capture started mid-boot would
    then blame the change under test for a state an earlier run left behind.
    Same pulse scripts/serial_capture.py uses.
    """
    try:
        import serial
        ser = serial.Serial(port, 115200, timeout=0.2)
        ser.dtr = False
        ser.rts = True
        time.sleep(0.2)
        ser.rts = False
        time.sleep(0.1)
        ser.dtr = True
        ser.close()
        time.sleep(1.0)
        return True
    except Exception as exc:  # noqa: BLE001 - the run can still go on, unread
        print(f"  serial reset on {port} failed: {exc}", flush=True)
        return False


class EspCapture(bbc.EspLog):
    """The central's log reader with a timestamped copy of every line.

    G4 measures a 5 s window between two log lines, which needs arrival times;
    EspLog keeps only the text. The passkey capture and the console echo stay
    exactly as they are in the reference client.
    """

    def __init__(self, port, log):
        super().__init__(port)
        self.log = log

    def _note(self, line):
        self.log.add(line)
        super()._note(line)

    def restart(self):
        """Read the port again after a stop(); a retried session needs a live reader.

        The reader thread exits when its stop flag is set and releases COMn, so the
        flag is cleared and a fresh thread opens the port. Lines already captured
        stay in the log - a retry adds to the capture, it does not replace it.
        """
        self.ok = False
        self._stop.clear()
        self.start()
        for _ in range(20):
            if self.ok:
                return True
            time.sleep(0.25)
        return False


class Log:
    """A USB capture in memory, with the timestamps the firmware does not print.

    Lines arrive as the unit writes them; we stamp arrival time so the 5 s
    rebuild window of G4 can be measured, and we keep the raw line for the
    report. The unit's own millis() is in some lines, but not all of them, and
    the two clocks are not comparable across a reboot.
    """

    def __init__(self):
        self.rows = []  # (arrival wall-clock seconds, text)

    def add(self, line):
        self.rows.append((time.time(), line.rstrip("\r\n")))

    def text(self):
        return "\n".join(t for _, t in self.rows)

    def write(self, path):
        os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
        with open(path, "w", encoding="utf-8", errors="replace") as f:
            f.write(self.text() + "\n")

    def find(self, rx, group=None):
        out = []
        for at, line in self.rows:
            m = rx.search(line)
            if m:
                out.append((at, line, m) if group is None else (at, line, m.group(group)))
        return out

    def heartbeats(self):
        out = []
        for at, line in self.rows:
            m = HB_RE.search(line)
            if m:
                out.append({"at": at, "line": line, "up": int(m.group(1)),
                            "fps": float(m.group(2)), "heap": int(m.group(3)),
                            "min": int(m.group(4)), "maxAlloc": int(m.group(5)),
                            "sp": int(m.group(6)), "memAct": int(m.group(9))})
        return out


def parked_intervals(log):
    """Time spans in which the firmware deliberately parks the dashboard.

    During an install the loop and the sensor task are held at their tops, so the
    frame rate is ~3 fps by design and the heap low-water sits at its release
    point. Judging G5's frame-rate and heap floors on those minutes would be
    judging a number the firmware chose on purpose; the gate reads the parts of
    the run where the dashboard is supposed to be live. Everything else (crash
    markers, mem-saver state) is still measured across the whole capture.
    """
    out, open_at = [], None
    for at, line in log.rows:
        if OTA_QUIET_OPEN_RE.search(line):
            open_at = at
        elif open_at is not None and (OTA_QUIET_CLOSE_RE.search(line)
                                      or OTA_ACCEPTED_RE.search(line)
                                      or BOOT_DONE_RE.search(line)):
            out.append((open_at, at))
            open_at = None
    if open_at is not None:
        out.append((open_at, float("inf")))
    return out


def evaluate(log, ota_attempted, min_heap_floor, max_stalls, min_fps, min_live_hb=6):
    """Return [(gate, ok, detail)] - every row says what it saw, not just a mark."""
    res = []
    hbs = log.heartbeats()
    parked = parked_intervals(log)

    def live(h):
        return not any(a <= h["at"] <= b for a, b in parked)

    live_hbs = [h for h in hbs if live(h)]
    parked_hbs = [h for h in hbs if not live(h)]

    # --- G1: a save must never run the memory-saver ---------------------------
    mem_act = [h for h in hbs if h["memAct"]]
    reqs = log.find(MEM_REQUEST_RE)
    drops = log.find(MEM_SAVER_RE)
    res.append(("G1 mem-saver never engages", not mem_act and not reqs and not drops,
                f"HB memAct=1 x{len(mem_act)}, 'requesting memory-saver' x{len(reqs)}, "
                f"'MEM SAVER' x{len(drops)}"
                + (f"; first: {reqs[0][1]}" if reqs else "")))

    # --- G2: the config.set gate costs no waiting -----------------------------
    sets = [(at, line, m) for at, line, m in log.find(CONFIGSET_RE)]
    if not sets:
        res.append(("G2 config.set gate waits zero", False,
                    "no CONFIGSET line in the capture - the save never ran, "
                    "so the gate was never measured"))
    else:
        waits = [int(m.group(8)) for _, _, m in sets]
        worst = max(waits)
        peak = max(int(m.group(4)) for _, _, m in sets)
        res.append(("G2 config.set gate waits zero", worst == 0,
                    f"{len(sets)} save(s), gate_waits max {worst}, "
                    f"heap after payload copy min {peak} B"
                    + (f"; worst: {next(l for _, l, mm in sets if int(mm.group(8)) == worst)}"
                       if worst else "")))

    # --- G3: the install completes --------------------------------------------
    if not ota_attempted:
        res.append(("G3 install completes", None, "no install in this run (--ota not given)"))
    else:
        verified = log.find(OTA_VERIFIED_RE)
        refused = log.find(OTA_BEGIN_REFUSED_RE)
        stopped = log.find(OTA_STOPPED_RE)
        ok = bool(verified) and not refused
        res.append(("G3 install completes", ok,
                    f"'image verified' x{len(verified)}, 'ota.begin refused' x{len(refused)}, "
                    f"'install stopped' x{len(stopped)}"
                    + (f": {stopped[-1][2].group(1)}" if stopped and not verified else "")))

    # --- G4: the dashboard is intact after the install window -----------------
    if not ota_attempted:
        res.append(("G4 dashboard intact after install", None, "no install in this run"))
    else:
        verified = log.find(OTA_VERIFIED_RE)
        closes = log.find(OTA_QUIET_CLOSE_RE)
        # Two ways the window ends. A good install ends it by rebooting, so the
        # promise on that path is "the next boot draws the dashboard normally".
        # An aborted one closes the window on the same boot, and there the
        # promise is "the sprite is back within 5 s". Both are checked.
        if verified:
            at, line, _ = verified[-1]
            later = [h for h in hbs if h["at"] > at]
            rebuilt = [r for r in log.find(SPRITE_BUILT_RE) if r[0] > at]
            memdrop = [m for m in log.find(MEM_SAVER_RE) if m[0] > at]
            ok = bool(rebuilt) and not memdrop
            detail = (f"install verified at {line.strip()[:70]}; sprite rebuild(s) after it "
                      f"{len(rebuilt)}, mem-saver after it {len(memdrop)}"
                      + (f"; first rebuild: {rebuilt[0][1]}" if rebuilt else ""))
            if later:
                ok = ok and later[0]["sp"] == 1 and later[0]["memAct"] == 0
                detail += (f"; first HB of the new boot fps={later[0]['fps']} sp={later[0]['sp']} "
                           f"memAct={later[0]['memAct']} heap={later[0]['heap']}")
            else:
                ok = False
                detail += "; no heartbeat after the install - the unit never came back up"
            res.append(("G4 dashboard intact after install", ok, detail))
        elif not closes:
            res.append(("G4 dashboard intact after install", False,
                        "no 'image verified' and no 'OTA quiet: closed' line - the window "
                        "never ended on this boot"))
        else:
            at, line, _ = closes[-1]
            rebuilt = [r for r in log.find(SPRITE_BUILT_RE) if r[0] >= at]
            skipped = [r for r in log.find(SPRITE_SKIP_RE) if r[0] >= at]
            after = [h for h in hbs if h["at"] >= at]
            in_window = [r for r in rebuilt if r[0] - at <= 5.0]
            ok = bool(in_window) and not skipped
            detail = (f"window closed at {line[:60]}; rebuild {len(in_window)} within 5 s "
                      f"of it, skipped {len(skipped)}"
                      + (f"; first rebuild: {in_window[0][1]}" if in_window else "")
                      + (f"; first skip: {skipped[0][1]}" if skipped else ""))
            if after:
                ok = ok and after[0]["sp"] == 1 and after[0]["memAct"] == 0
                detail += f"; first HB after close sp={after[0]['sp']} memAct={after[0]['memAct']}"
            res.append(("G4 dashboard intact after install", ok, detail))

    # --- G5: no crash, no new stall class, frame rate, soak heap --------------
    crashes = []
    for rx in CRASH_RES:
        crashes += log.find(rx)
    stalls = [s for s in log.find(STALL_RE)
              if not any(a <= s[0] <= b for a, b in parked)]
    # The unit's own frame rate is adaptive (no GNSS fix, static values, CPU
    # frequency steps), so an absolute fps floor would fail a healthy bench run -
    # the first boot's own log shows 11 -> 33 fps while ramping up. What this gate
    # cares about is a COLLAPSE: a live line far below what the same boot
    # achieves elsewhere. --min-fps pins an absolute floor when you want one.
    vals = sorted(h["fps"] for h in live_hbs[3:]) if len(live_hbs) > 6 else []
    median = vals[len(vals) // 2] if vals else 0.0
    floor = min_fps if min_fps > 0 else (0.5 * median if median else 0.0)
    fps_ok = [h for h in live_hbs if floor and h["fps"] < floor]
    low = [h for h in live_hbs if h["min"] < min_heap_floor]
    bad = log.find(BAD_ALLOC_RE)
    # A soak that produced no live heartbeat is not a passing soak - it is a
    # capture that only covers an install window. Say so instead of letting an
    # empty list satisfy the fps and heap floors.
    enough = len(live_hbs) >= min_live_hb
    ok = (not crashes and len(stalls) <= max_stalls and not fps_ok and not low
          and not bad and enough)
    res.append(("G5 no crash / stall / fps / heap floor", ok,
                f"live HB lines {len(live_hbs)} (need {min_live_hb}), crash markers "
                f"{len(crashes)}, STALL lines {len(stalls)} outside the install "
                f"window (max {max_stalls}), HB below {min_fps} fps {len(fps_ok)} of "
                f"{len(live_hbs)} live, HB min-heap below {min_heap_floor} B {len(low)}, "
                f"bad_alloc {len(bad)} ({len(parked_hbs)} HB line(s) inside an install "
                f"window are not judged on fps/heap; fps floor {floor:.1f} "
                + (f"(pinned, median fps this boot {median:.1f})" if min_fps > 0
                   else f"(half the median {median:.1f})")
                + (f"; first crash line: {crashes[0][1]}" if crashes else "")
                + (f"; first low HB: {low[0]['line']}" if low else "")))

    # Information, not a gate: the numbers the trims are judged on.
    info = []
    if live_hbs:
        info.append(f"live heap: first {live_hbs[0]['heap']} B, "
                    f"min {min(h['min'] for h in live_hbs)} B, "
                    f"maxAlloc last {live_hbs[-1]['maxAlloc']} B "
                    f"({len(parked_hbs)} parked HB line(s) excluded)")
    ring = log.find(OTA_RING_RE)
    if ring:
        at, line, m = ring[-1]
        info.append(f"ring high-water {m.group(1)}/{m.group(2)} slots, "
                    f"longest drain gap {m.group(3)} ms")
    for at, line, m in sets[-3:]:
        info.append(line.strip())
    return res, info


def report(res, info, stream=sys.stdout):
    failed = 0
    skipped = 0
    print("", file=stream)
    for name, ok, detail in res:
        tag = "SKIP" if ok is None else ("PASS" if ok else "FAIL")
        if ok is None:
            skipped += 1
        elif not ok:
            failed += 1
        print(f"  {tag}  {name}", file=stream)
        print(f"        {detail}", file=stream)
    if info:
        print("  -- numbers (information, not gates) --", file=stream)
        for line in info:
            print(f"     {line}", file=stream)
    print(f"\n  {len(res) - failed - skipped} gate(s) passed, {failed} failed, {skipped} skipped",
          file=stream)
    return failed


# ---------------------------------------------------------------------------
# bench mode: drive the unit, then evaluate what it said
# ---------------------------------------------------------------------------
def split_config(values, limit=MAX_BODY_BYTES):
    """Mirror of RpcFraming.split in the app: cut a big write into messages.

    The unit reassembles at most BT_MAX_MSG bytes and refuses anything larger, so
    a whole-config restore has to be cut. Same rule, same reserve - if the two
    drift, this script is not measuring what the app does.
    """
    groups, current, size = [], {}, 2
    for k, v in values.items():
        entry = 1 + len(json.dumps(k)) + 1 + len(json.dumps(v))
        if current and size + entry > limit:
            groups.append(current)
            current, size = {}, 2
            current[k] = v
            size += entry - 1
        else:
            current[k] = v
            size += entry if len(current) == 1 else entry
    if current:
        groups.append(current)
    return groups


async def bench(a):
    """Own the log reader and the capture file, whatever the run does.

    A run that throws halfway still has to leave a capture and a verdict: the
    lines the unit wrote before it broke are the evidence, and the exit code is
    what a person or a script reads.
    """
    log = Log()
    if a.reset_first and not reset_bench(a.port):
        print(f"could not reset {a.port} - the run starts on whatever state the "
              f"unit is already in", flush=True)
    esp = EspCapture(a.port, log)
    esp.start()
    code = 0
    try:
        code = await drive_with_retries(a, log, esp)
    finally:
        esp.stop()
    out = a.out or os.path.join("Local artifacts", "logs",
                                time.strftime("heap-gates-%Y%m%d-%H%M%S.log"))
    log.write(out)
    print(f"\nwrote capture: {out} ({len(log.rows)} line(s))", flush=True)
    res, info = evaluate(log, ota_attempted=bool(a.ota), min_heap_floor=a.min_heap,
                         max_stalls=a.max_stalls, min_fps=a.min_fps,
                         min_live_hb=a.min_live_hb)
    rc = report(res, info)
    return rc or code


async def drive_with_retries(a, log, esp):
    """Run the scenario, retrying only while the LINK never came up.

    Windows is the flaky part of this bench: a connection that pairs, connects and
    then refuses the CCCD write, or comes up at MTU 23 because WinRT skipped the
    exchange, is a property of its GATT cache and not of the firmware. Retrying the
    session (fresh scan, fresh client) is what a person at the bench does; giving up
    on the first hiccup would blame the build under test. Anything that goes wrong
    AFTER the link is up is not retried - that is the thing being measured.
    """
    codes = {2: "never found the unit", 3: "never paired", 4: "link not usable"}
    code = 9
    for attempt in range(1, a.sessions + 1):
        if attempt > 1:
            print(f"  session {attempt}/{a.sessions}: the link was {codes.get(code, code)} "
                  f"- rescanning and reconnecting", flush=True)
            await asyncio.sleep(3.0)
            esp.restart()
        try:
            code = await drive(a, log, esp)
        except Exception as exc:  # noqa: BLE001 - a broken link still reports gates
            print(f"run aborted: {type(exc).__name__}: {exc}", flush=True)
            code = 9
        if code not in (2, 3, 4):
            return code
    print(f"  no usable BLE session in {a.sessions} tries - the gates below still read "
          f"whatever the unit logged", flush=True)
    return code


async def drive(a, log, esp):
    from bleak import BleakClient, BleakScanner
    from winrt.windows.devices.enumeration import DeviceInformation

    await bbc.ensure_radio()

    print(f"scanning for {a.prefix}*", flush=True)
    found = None
    for _ in range(3):
        adv = await BleakScanner.discover(timeout=a.scan_timeout, return_adv=True)
        for addr, (dev, adv_data) in adv.items():
            if (adv_data.local_name or "").startswith(a.prefix):
                found = (addr, adv_data.local_name, dev)
                break
        if found:
            break
    if not found:
        print(f"no {a.prefix}* advertising", flush=True)
        esp.stop()
        return 2
    address, name, dev = found
    print(f"found {name} at {address}", flush=True)

    di = await bbc.resolve_device_information(address)
    if di is None:
        print("cannot resolve device information for pairing", flush=True)
        esp.stop()
        return 2
    paired = di.pairing.is_paired
    print(f"paired with Windows already: {paired}", flush=True)
    for attempt in range(1, 4):
        if paired:
            break
        token, kinds, used = await bbc.pair_with_passkey(di, esp)
        r = await di.pairing.custom.pair_async(kinds)
        print(f"  pairing try {attempt}: {r.status.name}", flush=True)
        paired = r.status.name in ("PAIRED", "ALREADY_PAIRED")
        di.pairing.custom.remove_pairing_requested(token)
        if paired or attempt == 3:
            break
        await asyncio.sleep(2.0)
        di = await bbc.resolve_device_information(address)
    fresh = await DeviceInformation.create_from_id_async(di.id) if di else None
    if not (fresh and fresh.pairing.is_paired):
        print("not paired - every characteristic is encryption-gated, stopping", flush=True)
        esp.stop()
        return 3

    client = BleakClient(dev, timeout=30.0)
    await client.connect()
    print(f"connected, MTU {client.mtu_size}", flush=True)

    r = bbc.Reassembler()
    telem = []

    def on_notify(_, data):
        b = bytes(data)
        r.push(b)

    async def drain():
        rest = []
        for item in r.complete:
            if item[0] == bbc.F_TELEM:
                telem.append(item[2])
            elif item[0] == F_OTA:
                # Install events are collected whether or not a request is
                # waiting: the unit ACKs during the write loop, and an ACK the
                # client does not read is a window that never opens again.
                try:
                    events.append(json.loads(item[2]))
                except json.JSONDecodeError:
                    pass
            else:
                rest.append(item)
        r.complete = rest

    # The attribute table is printed for the same reason the reference client
    # prints it: it shows what Windows really cached, which is where a missing
    # CCCD or a stale handle shows up before it is blamed on the firmware.
    svc = client.services.get_service(bbc.SVC_UUID)
    if svc is not None:
        for ch in svc.characteristics:
            print(f"  char {ch.uuid[-6:]} handle 0x{ch.handle:04x} "
                  f"properties={','.join(ch.properties)}", flush=True)
    else:
        print(f"  {bbc.SVC_UUID} not in the discovered table", flush=True)

    async def subscribe(uuid, label):
        # WinRT answers a CCCD write with "operation cancelled" now and then -
        # usually right after a reboot of the peripheral, when its attribute
        # table is up but the link is still settling. The reference client
        # happens to connect into a link that is already warm; this one does not
        # always, so the subscription gets three tries before the run gives up.
        for attempt in range(3):
            try:
                await client.start_notify(uuid, on_notify)
                print(f"  subscribed {label}", flush=True)
                return True
            except Exception as exc:  # noqa: BLE001
                # Several different objects come back here (OSError with a WinRT
                # code, BleakError "Not connected" the next try). They mean the
                # same thing: this connection is not usable yet.
                print(f"  subscribe {label} try {attempt + 1}: {exc}", flush=True)
                await asyncio.sleep(1.0 + attempt)
        return False

    if not await subscribe(bbc.TELEM_UUID, "telemetry"):
        await client.disconnect(); esp.stop(); return 4
    if not await subscribe(bbc.RPC_UUID, "rpc"):
        await client.disconnect(); esp.stop(); return 4
    await asyncio.sleep(2.0)
    print(f"MTU now {client.mtu_size}", flush=True)
    if client.mtu_size < 100:
        # WinRT did not run the MTU exchange on this connection. At MTU 23 a
        # 1.6 MB install is ~79k frames instead of 3.2k, which measures the
        # Windows GATT cache rather than the firmware - so the session is torn
        # down and retried, the way a person at the bench would.
        print("  MTU is still 23 - dropping the link and retrying the session", flush=True)
        try:
            await client.disconnect()
        except Exception:  # noqa: BLE001
            pass
        esp.stop()
        return 4

    seqbox = [0]

    def next_seq():
        seqbox[0] = (seqbox[0] + 1) & 0xFFFF
        return seqbox[0] or 1

    def frame(ftype, seq, payload: bytes):
        chunk = max(1, client.mtu_size - 3 - bbc.FRAME_HDR)
        out, off = [], 0
        while True:
            n = min(chunk, len(payload) - off)
            flags = (bbc.FLAG_FIRST if off == 0 else 0) | (bbc.FLAG_LAST if off + n >= len(payload) else 0)
            out.append(bytes([bbc.PROTO_VERSION, ftype, flags, 0, seq & 0xFF, seq >> 8,
                              n & 0xFF, n >> 8]) + payload[off:off + n])
            off += n
            if off >= len(payload):
                return out

    events = []      # BT_F_OTA bodies, in arrival order
    chunks = {}      # BT_F_CHUNK frames parked by the request id they answer

    async def request(method, params=None, timeout=20.0, want_events=False):
        """One RPC message, then read until its answer lands.

        Returns (ok, response, own_chunks). config.get streams BT_F_CHUNK frames
        with the same id and closes with an ordinary response; install events
        (BT_F_OTA) are only collected when the caller asks for them, so a stray
        event from a previous install cannot be read as this one's ACK.
        """
        sid = next_seq()
        body = {"m": method, "id": sid}
        if params is not None:
            body["p"] = params
        for frag in frame(bbc.F_REQ, sid, json.dumps(body).encode()):
            await client.write_gatt_char(bbc.RPC_UUID, bytearray(frag), response=True)
        out, deadline = None, time.time() + timeout
        while time.time() < deadline:
            await drain()
            for item in list(r.complete):
                ftype, fseq, payload = item
                if ftype == bbc.F_CHUNK:
                    try:
                        c = json.loads(payload)
                    except json.JSONDecodeError:
                        r.complete.remove(item)
                        continue
                    chunks.setdefault(c.get("id"), []).append(c)
                    r.complete.remove(item)
                elif ftype == bbc.F_RESP:
                    try:
                        j = json.loads(payload)
                    except json.JSONDecodeError:
                        r.complete.remove(item)
                        continue
                    if j.get("id") == sid:
                        r.complete.remove(item)
                        out = j
            if out is not None:
                return bool(out.get("ok")), out, chunks.pop(sid, [])
            await asyncio.sleep(0.02)
        return False, {"e": "client timeout"}, chunks.pop(sid, [])

    # ---- the save/restore scenario (G1, G2) ---------------------------------
    if a.restore:
        ok, resp, got = await request("config.get", timeout=60.0)
        merged = {}
        for c in got:
            merged.update(c.get("d", {}))
        print(f"config.get: ok={ok} in {len(got)} chunk frame(s), {len(merged)} key(s) "
              f"{resp.get('r') or resp.get('e')}", flush=True)
        if not merged:
            print("config.get streamed nothing - the save scenario cannot run", flush=True)
        else:
            groups = split_config(merged)
            print(f"writing all {len(merged)} key(s) back in {len(groups)} config.set message(s)",
                  flush=True)
            for i, g in enumerate(groups):
                t0 = time.time()
                ok, resp, _ = await request("config.set", g, timeout=30.0)
                print(f"  save {i + 1}/{len(groups)}: {len(g)} key(s) "
                      f"{len(json.dumps(g).encode())} B ok={ok} in {time.time() - t0:.2f}s "
                      f"{'' if ok else resp.get('e')}", flush=True)
                if not ok:
                    break

    # ---- the install scenario (G3, G4) --------------------------------------
    def harvest(last_ack):
        """Fold the install events seen so far into the resume offset.

        Returns (ack, failure). An ACK is an offset the unit says is on flash, so
        it is also the proof the link is alive; the resume window is measured from
        the last one, exactly like OTA_SILENCE_MS on the unit.
        """
        failure = None
        for ev in list(events):
            kind = ev.get("ev")
            if kind == "ack":
                last_ack = max(last_ack, int(ev.get("off", 0)))
                events.remove(ev)
            elif kind == "begin_ok":
                events.remove(ev)
            elif kind in ("error",):
                failure = f"error {ev.get('reason', '?')} at {ev.get('off', '?')} B"
                events.remove(ev)
            elif kind == "done":
                # The unit has verified the image and is about to reboot; that is
                # the happy ending, not a failure to report.
                events.remove(ev)
        return last_ack, failure

    if a.ota:
        data = open(a.ota, "rb").read()
        crc = zlib.crc32(data) & 0xFFFFFFFF
        print(f"installing {a.ota}: {len(data)} B, crc32 {crc:08x}", flush=True)
        ok, resp, _ = await request("ota.begin", {"size": len(data), "crc32": crc},
                                    timeout=30.0, want_events=True)
        begin = resp.get("r") or {}
        print(f"  ota.begin ok={ok} {begin or resp.get('e')}", flush=True)
        if ok and begin.get("chunk"):
            off, chunk = int(begin.get("off", 0)), int(begin["chunk"])
            last_ack, next_send = off, off
            wrote, failure = 0, None
            progress_at = time.time()
            t0 = progress_at
            while next_send < len(data) and failure is None:
                # Flow control, the app's rule: never run more than the
                # outstanding window past the last offset the unit confirmed on
                # flash. Pushing past it is what turned this run into a link that
                # died with the image half-written.
                while next_send - last_ack >= OTA_OUTSTANDING:
                    await asyncio.sleep(0.02)
                    await drain()
                    acked, failure = harvest(last_ack)
                    if acked != last_ack:
                        last_ack, progress_at = acked, time.time()
                    if failure:
                        break
                    if time.time() - progress_at > OTA_SILENCE_MS / 1000.0:
                        failure = "client saw no ACK inside the resume window"
                        break
                if failure or next_send >= len(data):
                    break
                n = min(chunk, len(data) - next_send)
                payload = struct_pack_off(next_send) + data[next_send:next_send + n]
                await client.write_gatt_char(OTA_UUID, bytearray(payload), response=False)
                wrote += 1
                next_send += n
                await drain()
                acked, failure = harvest(last_ack)
                if acked != last_ack:
                    last_ack = acked
                    progress_at = time.time()
                if wrote % 400 == 0:
                    print(f"  sent {next_send}/{len(data)} B ({next_send * 100 // len(data)}%), "
                          f"ack {last_ack}, {wrote} write(s)", flush=True)
            await drain()
            last_ack, failure = harvest(last_ack)
            print(f"  pushed {next_send} B in {wrote} write(s), unit confirmed {last_ack} B "
                  f"in {time.time() - t0:.1f}s" + (f" ({failure})" if failure else ""), flush=True)
            if failure and "done" not in str(failure):
                ok2, resp2, _ = await request("ota.abort", {}, timeout=10.0)
                print(f"  ota.abort ok={ok2} {resp2.get('r') or resp2.get('e')}", flush=True)
            else:
                ok, resp, _ = await request("ota.end", {}, timeout=90.0)
                print(f"  ota.end ok={ok} {resp.get('r') or resp.get('e')}", flush=True)

            # A verified image means the unit flips slots and reboots, and the
            # gates on the dashboard after an install (G4) are read from that new
            # boot. So the run does not end at the reboot: the serial log stays
            # open until the new boot says "Setup done" and prints a heartbeat.
            verified_at = next((at for at, l in log.rows if OTA_VERIFIED_RE.search(l)), None)
            if verified_at:
                print("  waiting for the unit to come back up on the new slot", flush=True)
                deadline = time.time() + 120
                while time.time() < deadline and not any(
                        at > verified_at and BOOT_DONE_RE.search(l) for at, l in log.rows):
                    await asyncio.sleep(1.0)
                deadline = time.time() + 40
                while time.time() < deadline and not any(
                        at > verified_at and HB_RE.search(l) for at, l in log.rows):
                    await asyncio.sleep(1.0)
            try:
                await client.disconnect()
            except Exception:  # noqa: BLE001 - a reboot drops the link on its own
                pass
        else:
            print("  install not started; gates that need an install read the log", flush=True)

    # ---- soak (G5) ----------------------------------------------------------
    if a.minutes:
        print(f"soaking {a.minutes} min (heartbeat lines are the evidence)", flush=True)
        end = time.time() + a.minutes * 60
        told = False
        while time.time() < end:
            await asyncio.sleep(5)
            if not client.is_connected and not told:
                # A reboot after an install drops the link on purpose; the log is
                # still the evidence, so the soak keeps reading it.
                print("  link is down - continuing to read the unit's log", flush=True)
                told = True
    try:
        await client.disconnect()
    except Exception:  # noqa: BLE001 - already gone is fine here
        pass
    return 0


def struct_pack_off(off):
    return bytes([off & 0xFF, (off >> 8) & 0xFF, (off >> 16) & 0xFF, (off >> 24) & 0xFF])


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("mode", choices=["check", "bench"])
    ap.add_argument("--log", help="capture to evaluate (check mode)")
    ap.add_argument("--port", default="COM7")
    ap.add_argument("--prefix", default="DPP-", help="advertised name prefix to scan for")
    ap.add_argument("--scan-timeout", type=float, default=12.0)
    ap.add_argument("--restore", action="store_true", help="run the full config save/restore")
    ap.add_argument("--ota", help="push this firmware.bin over the OTA characteristic")
    ap.add_argument("--minutes", type=float, default=0.0, help="soak this many minutes")
    ap.add_argument("--out", help="where to write the capture (bench mode)")
    ap.add_argument("--verbose-esp", action="store_true", help="echo the unit log while running")
    ap.add_argument("--no-reset-first", dest="reset_first", action="store_false",
                    help="do not pulse DTR/RTS to start the run from a clean boot")
    ap.set_defaults(reset_first=True)
    ap.add_argument("--sessions", type=int, default=3,
                    help="retries of a session that never got a usable link")
    ap.add_argument("--min-heap", type=int, default=25000,
                    help="G5 floor for the soak's low-water heap, bytes")
    ap.add_argument("--max-stalls", type=int, default=0, help="G5 allowance for STALL lines")
    ap.add_argument("--min-fps", type=float, default=0.0,
                    help="absolute G5 frame-rate floor (0 = half this boot median)")
    ap.add_argument("--min-live-hb", type=int, default=6,
                    help="G5 needs at least this many heartbeats outside an install window")
    ap.add_argument("--ota-attempted", action="store_true",
                    help="check mode: the capture contains an install attempt")
    a = ap.parse_args()

    if a.mode == "check":
        if not a.log:
            ap.error("check mode needs --log")
        log = Log()
        with open(a.log, encoding="utf-8", errors="replace") as f:
            for line in f:
                log.add(line.rstrip("\r\n"))
        res, info = evaluate(log, ota_attempted=a.ota_attempted, min_heap_floor=a.min_heap,
                             max_stalls=a.max_stalls, min_fps=a.min_fps,
                             min_live_hb=a.min_live_hb)
        return report(res, info)
    return asyncio.run(bench(a))


if __name__ == "__main__":
    sys.exit(main())
