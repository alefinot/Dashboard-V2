#!/usr/bin/env python3
"""BLE central for the Dashboard++ unit - the client the firmware is measured against.

The ESP no longer has a settings UI or an HTTP server (phase 2a), and the Kotlin
app only grows a GATT client in phase 2c, so this script is the independent
client the BLE protocol gets verified with. It pairs - reading the six digits the
panel displays out of the USB log and handing them to Windows, so no one has to
type anything - subscribes to telemetry and RPC, reassembles the frame protocol
from src/bt_proto.h, streams the config mirror, and reports what it got.

    python scripts/ble_bench_central.py [--port COM7] [--skip-driver-wait]

The firmware has its own bench driver for the same stream (-DBT_CFG_VERIFY,
footer of src/bt_rpc.cpp). This script waits for that run to finish and merges
its frames too, so the same bytes are counted from both ends of the link and a
disagreement between the two counts is a protocol bug rather than a parser bug.

Exit code is 0 only when every check passed.
"""

import argparse
import asyncio
import json
import re
import sys
import threading
import time

# Wire constants - keep in step with src/bt_proto.h.
PROTO_VERSION = 1
HOLD = {}  # the live client, so _run() can close the link after an exception
FRAME_HDR = 8
FLAG_FIRST, FLAG_LAST = 0x01, 0x02
F_REQ, F_RESP, F_TELEM, F_PING, F_PONG, F_CHUNK = 1, 2, 3, 6, 7, 10

SVC_UUID = "d9a1f000-6c1e-4b8a-9f2d-3e7c1a5b9d40"
TELEM_UUID = "d9a1f001-6c1e-4b8a-9f2d-3e7c1a5b9d40"
RPC_UUID = "d9a1f002-6c1e-4b8a-9f2d-3e7c1a5b9d40"

PASSKEY_RE = re.compile(r"pairing passkey (\d{6})")
DRIVER_DONE_RE = re.compile(r"CFGVERIFY: done\.")


class EspLog:
    """Reads the unit's USB log in a thread.

    Two things come out of it: the pairing passkey the panel shows (so pairing
    needs no hands), and the firmware's own CFGVERIFY lines, which are the other
    half of every count this script reports.
    """

    def __init__(self, port):
        self.port = port
        self.lines = []
        self.passkey = None
        self.ok = False
        self._lock = threading.Lock()
        self._stop = threading.Event()

    def start(self):
        t = threading.Thread(target=self._run, daemon=True)
        t.start()
        return t

    def _run(self):
        try:
            import serial
        except ImportError:
            self._note("(pyserial missing - no USB log, passkey must be typed)")
            return
        try:
            # DTR and RTS are held low before the port is opened. Opening with the
            # pyserial defaults pulses them, and on this board that pulse goes
            # straight into the USB-UART auto-reset circuit: the unit rebooted in
            # the middle of a run, the 1 Hz stream stalled for seconds, and the
            # fragments of a telemetry frame landed without their FIRST.
            s = serial.Serial()
            s.port = self.port
            s.baudrate = 115200
            s.timeout = 0.5
            s.dtr = False
            s.rts = False
            s.open()
            try:
                self.ok = True
                buf = b""
                while not self._stop.is_set():
                    chunk = s.read(4096)
                    if not chunk:
                        continue
                    buf += chunk
                    while b"\n" in buf:
                        line, buf = buf.split(b"\n", 1)
                        self._note(line.decode("utf-8", "replace").rstrip())
            finally:
                s.close()
        except Exception as e:  # a monitor holding COM7 is the usual cause
            self._note(f"(serial {self.port} unavailable: {e})")

    def _note(self, line):
        with self._lock:
            self.lines.append(line)
        m = PASSKEY_RE.search(line)
        if m:
            self.passkey = m.group(1)
        # A firmware log line is whatever the ESP put on the wire, and the console
        # this script prints to is a Windows code page. One undecodable byte must
        # not take the reader thread down with it - the passkey comes through the
        # same thread.
        try:
            print(f"  esp| {line}", flush=True)
        except UnicodeEncodeError:
            print(f"  esp| {line.encode('ascii', 'backslashreplace').decode()}", flush=True)

    def wait_passkey(self, timeout):
        end = time.time() + timeout
        while time.time() < end:
            if self.passkey:
                return self.passkey
            time.sleep(0.2)
        return None

    def wait_line(self, pattern, timeout):
        rx = re.compile(pattern)
        end = time.time() + timeout
        seen = 0
        while time.time() < end:
            with self._lock:
                while seen < len(self.lines):
                    line = self.lines[seen]
                    seen += 1
                    if rx.search(line):
                        return line
            time.sleep(0.2)
        return None

    def grep(self, pattern):
        rx = re.compile(pattern)
        with self._lock:
            return [l for l in self.lines if rx.search(l)]

    def text(self):
        with self._lock:
            return "\n".join(self.lines)

    def stop(self):
        self._stop.set()


class Reassembler:
    """One open message per (type, seq), the way bt.cpp does it on the ESP side.

    A fragment with FIRST resets the slot; LAST closes it. A second FIRST on an
    open slot is a lost fragment and gets reported instead of growing a buffer.
    """

    def __init__(self):
        self.slots = {}
        self.complete = []  # (type, seq, payload str)
        self.errors = []

    def push(self, raw):
        if len(raw) < FRAME_HDR:
            self.errors.append(f"short frame ({len(raw)} B)")
            return
        if raw[0] != PROTO_VERSION:
            self.errors.append(f"version {raw[0]} != {PROTO_VERSION}")
            return
        ftype, flags, seq = raw[1], raw[2], raw[4] | (raw[5] << 8)
        n = raw[6] | (raw[7] << 8)
        if n != len(raw) - FRAME_HDR:
            self.errors.append(f"len field {n} vs {len(raw) - FRAME_HDR} payload bytes")
            return
        key = (ftype, seq)
        if flags & FLAG_FIRST:
            if key in self.slots and self.slots[key]:
                self.errors.append(f"type {ftype} seq {seq}: FIRST over an open slot")
            self.slots[key] = bytearray(raw[FRAME_HDR:])
        else:
            if key not in self.slots:
                self.errors.append(f"type {ftype} seq {seq}: fragment without a FIRST")
                return
            self.slots[key] += raw[FRAME_HDR:]
        if flags & FLAG_LAST:
            payload = bytes(self.slots.pop(key))
            try:
                self.complete.append((ftype, seq, payload.decode("utf-8")))
            except UnicodeDecodeError:
                self.errors.append(f"type {ftype} seq {seq}: payload is not UTF-8")


async def pair_with_passkey(di, log):
    """LE Secure Connections pairing with the passkey the ESP displays.

    bleak's own pair() only drives a Just Works ceremony (its handler always
    calls accept()); here the peripheral shows a six digit code and this side has
    to type it, so the WinRT ceremony is driven directly and the code is taken
    from the USB log. The handler goes on before the ceremony - and the ceremony
    goes on before the connection.
    """
    from winrt.windows.devices.enumeration import DevicePairingKinds

    kinds = (
        DevicePairingKinds.CONFIRM_ONLY
        | DevicePairingKinds.PROVIDE_PIN
        | DevicePairingKinds.CONFIRM_PIN_MATCH
        | DevicePairingKinds.DISPLAY_PIN
    )
    used = []

    def handler(sender, args):
        kind = args.pairing_kind
        used.append(kind.name)
        if kind in (DevicePairingKinds.PROVIDE_PIN, DevicePairingKinds.CONFIRM_PIN_MATCH):
            pin = log.wait_passkey(8.0) if log.ok else None
            if not pin and sys.stdin and sys.stdin.isatty():
                pin = input("  Six digits shown on the panel: ").strip()
            if not pin:
                print("  pairing: no passkey available (USB log not reading) - cannot answer", flush=True)
                return
            print(f"  pairing: supplying passkey {pin} ({kind.name})", flush=True)
            args.accept_with_pin(pin)
        else:
            print(f"  pairing: accepting {kind.name}", flush=True)
            args.accept()

    token = di.pairing.custom.add_pairing_requested(handler)
    return token, kinds, used


async def resolve_device_information(address):
    """The WinRT DeviceInformation behind a MAC string - what pairing needs.

    This SDK projection has no BluetoothAddress struct (the scanner hands back a
    plain integer address), and WinRT ids are not MAC strings, so create_from_id
    on "94:B5:..." fails. Going through BluetoothLEDevice is how bleak's own
    pair() finds the same id.
    """
    from winrt.windows.devices.bluetooth import BluetoothLEDevice
    from winrt.windows.devices.enumeration import DeviceInformation

    le = await BluetoothLEDevice.from_bluetooth_address_async(int(address.replace(":", ""), 16))
    if le is None:
        return None
    return await DeviceInformation.create_from_id_async(le.device_information.id)


async def ensure_radio():
    """Windows lets the BLE radio sit switched off, and every scan then comes
    back empty with no error. Print the state and turn it on if it is off."""
    try:
        from winrt.windows.devices.bluetooth import BluetoothAdapter
        from winrt.windows.devices.radios import RadioAccessStatus, RadioState
    except ImportError:
        return
    adapter = await BluetoothAdapter.get_default_async()
    if adapter is None:
        print("  no Bluetooth adapter on this machine", flush=True)
        return
    radio = await adapter.get_radio_async()
    print(f"  radio: {radio.state.name}", flush=True)
    if radio.state != RadioState.ON:
        try:
            await radio.set_state_async(RadioState.ON)
            print(f"  radio now {radio.state.name}", flush=True)
        except Exception as exc:  # noqa: BLE001 - reporting beats crashing here
            print(f"  could not switch the radio on: {exc}", flush=True)


async def main(a):
    from bleak import BleakClient, BleakScanner
    from winrt.windows.devices.enumeration import DeviceInformation  # used for the re-lookup

    checks = []

    def check(name, ok, detail=""):
        checks.append((name, ok, detail))
        print(f"  {'PASS' if ok else 'FAIL'}  {name}{'  ' + detail if detail else ''}", flush=True)

    log = EspLog(a.port)
    if not a.no_serial:
        log.start()

    print(f"scanning for {a.prefix}*", flush=True)
    await ensure_radio()
    found = None
    for _ in range(3):
        # return_adv gives (BLEDevice, AdvertisementData) per address; the name
        # is in the advertisement, which is what the unit puts in its scan
        # response.
        adv_map = await BleakScanner.discover(timeout=a.scan_timeout, return_adv=True)
        for addr, (dev, adv_data) in adv_map.items():
            if (adv_data.local_name or "").startswith(a.prefix):
                found = (addr, adv_data.local_name, dev)
                break
        if found:
            break
    if not found:
        print(f"no {a.prefix}* advertising", flush=True)
        return 2
    address, name, dev = found
    print(f"found {name} at {address}", flush=True)

    di = await resolve_device_information(address)
    if di is None:
        print("cannot resolve device information for pairing", flush=True)
        return 2
    if a.unpair and di.pairing.is_paired:
        print("unpairing first (--unpair)", flush=True)
        print("  unpair:", (await di.pairing.unpair_async()).status.name, flush=True)
        di = await resolve_device_information(address)

    # Pair before the GATT link is opened on purpose: the unit asks for the
    # encrypted link the moment anything connects, and a ceremony started after
    # that races the peripheral's own security request - that is what the unit's
    # "pairing window expired" line means. The handler has to sit on the
    # DeviceInformation object the ceremony runs on, so it goes back on every time
    # that object is rebuilt; a ceremony that was rejected or half-finished gets
    # retried instead of ending the run.
    used_kinds, token, kinds = [], None, None
    paired = di.pairing.is_paired
    if paired:
        print("already paired (bond in use)", flush=True)
    for attempt in range(1, 4):
        if paired:
            break
        token, kinds, used_kinds = await pair_with_passkey(di, log)
        res = await di.pairing.custom.pair_async(kinds)
        print(f"  pairing ceremony try {attempt}: {res.status.name}", flush=True)
        paired = res.status.name in ("PAIRED", "ALREADY_PAIRED")
        di.pairing.custom.remove_pairing_requested(token)
        token = None
        if paired or attempt == 3:
            break
        await asyncio.sleep(2.0)
        di = await resolve_device_information(address)
        if di is None:
            break
    fresh = await DeviceInformation.create_from_id_async(di.id) if di else None
    check("paired (LE SC, passkey)", bool(fresh and fresh.pairing.is_paired),
          ",".join(used_kinds) or "no ceremony event")
    if not (fresh and fresh.pairing.is_paired):
        print("not paired - every characteristic is encryption-gated, stopping", flush=True)
        log.stop()
        return 3

    client = BleakClient(dev, timeout=30.0)
    HOLD["client"] = client  # closed in _run() even if this function throws
    await client.connect()
    print(f"connected, MTU {client.mtu_size}", flush=True)
    check("link up", client.is_connected, f"MTU {client.mtu_size}")

    r = Reassembler()
    extra = {}  # things two sections of the run need to hand each other

    # What the OS's GATT layer believes the unit's attribute table is, printed
    # before anything is subscribed: handle numbers, properties, CCCDs. When an
    # answer never arrives, this is the table both sides have to agree on.
    svc = client.services.get_service(SVC_UUID)
    if svc is not None:
        for ch in svc.characteristics:
            print(f"  char {ch.uuid[-6:]} handle 0x{ch.handle:04x} "
                  f"properties={','.join(ch.properties)} "
                  f"descriptors={[(d.uuid[-6:], f'0x{d.handle:04x}') for d in ch.descriptors]}",
                  flush=True)
    else:
        check("service present", False, f"{SVC_UUID} not in the discovered table")

    raw = {"n": 0, "recent": []}  # every notification Windows handed us, since forever

    def on_notify(_, data: bytearray):
        b = bytes(data)
        raw["n"] += 1
        # (type, flags, seq, length) from the frame header: byte 0 is the protocol
        # version, so the type is byte 1 and the seq is bytes 4-5.
        if len(b) >= 8:
            raw["recent"].append((b[1], b[2], b[4] | (b[5] << 8), len(b)))
        else:
            raw["recent"].append((b[0] if b else -1, 0, 0, len(b)))
        if len(raw["recent"]) > 12:
            del raw["recent"][0]
        r.push(b)

    telem_frames = []
    telem_at = []  # arrival times - the gap between two frames is the measurement

    async def drain():
        """Collect telemetry frames, leave everything else in the queue.

        Popping unconditionally used to swallow the RPC answers: a response that
        landed between two waits was gone before request() ever looked for it, and
        the run reported a client timeout for a reply that had arrived intact.
        """
        rest = []
        for ftype, seq, payload in r.complete:
            if ftype == F_TELEM:
                telem_frames.append(payload)
                telem_at.append(time.time())
            else:
                rest.append((ftype, seq, payload))
        r.complete = rest

    await client.start_notify(TELEM_UUID, on_notify)
    await client.start_notify(RPC_UUID, on_notify)

    seqbox = [0]

    def next_seq():
        seqbox[0] = (seqbox[0] + 1) & 0xFFFF
        return seqbox[0] or 1

    def frame(ftype, seq, payload: bytes):
        """One message as the fragments it travels in - the mirror image of what
        bt.cpp does on the way out, and what the unit expects on the way in:
        header (version, type, flags, seq, len) plus that many payload bytes, a
        message wider than one ATT write split across several.
        """
        chunk = max(1, client.mtu_size - 3 - FRAME_HDR)
        out, off = [], 0
        while True:
            n = min(chunk, len(payload) - off)
            flags = (FLAG_FIRST if off == 0 else 0) | (FLAG_LAST if off + n >= len(payload) else 0)
            out.append(bytes([PROTO_VERSION, ftype, flags, 0,
                              seq & 0xFF, seq >> 8, n & 0xFF, n >> 8]) + payload[off:off + n])
            off += n
            if off >= len(payload):
                return out

    stray = {}  # chunk frames belonging to another request, parked by id

    async def request(method, params=None, seq=None, timeout=None):
        """One RPC message, then read until its response lands.

        Returns (ok, payload-or-error, [chunk dicts]) for this request. The unit
        answers with {"id","ok","r"} or {"id","ok":false,"e"}; a config.get
        streams BT_F_CHUNK frames with the same id first and closes with an
        ordinary response. Chunks carrying a different id are parked in stray -
        the firmware's bench stream runs with id 0xBEEF while the answer to
        bench.run is still on its way - so they get merged separately instead of
        being dropped on the floor.
        """
        sid = seq if seq is not None else next_seq()
        body = {"m": method, "id": sid}
        if params is not None:
            body["p"] = params
        for frag in frame(F_REQ, sid, json.dumps(body).encode()):
            await client.write_gatt_char(RPC_UUID, bytearray(frag), response=True)
        limit = timeout or a.timeout
        out, deadline = None, time.time() + limit
        while time.time() < deadline:
            await drain()
            for item in list(r.complete):
                ftype, fseq, payload = item
                if ftype == F_CHUNK:
                    try:
                        c = json.loads(payload)
                    except json.JSONDecodeError:
                        r.errors.append("chunk frame is not valid JSON")
                        r.complete.remove(item)
                        continue
                    stray.setdefault(c.get("id"), []).append(c)
                    r.complete.remove(item)
                elif ftype == F_RESP:
                    try:
                        j = json.loads(payload)
                    except json.JSONDecodeError:
                        r.errors.append("response frame is not valid JSON")
                        r.complete.remove(item)
                        continue
                    if j.get("id") == sid:
                        r.complete.remove(item)
                        out = j
            if out is not None:
                return out.get("ok"), out, stray.pop(sid, [])
            await asyncio.sleep(0.02)
        # Nothing matched this id. Saying what did arrive is the whole point of
        # the timeout branch: frames parked under another id, frames that failed
        # the header checks, or silence.
        parked = [(t, q) for (t, q, _) in r.complete]
        print(f"       timeout for {method} (id {sid}): complete={parked[:8]}, "
              f"parked_ids={sorted(stray)}, errors={r.errors[:3]}", flush=True)
        print(f"       windows gave {raw['n']} notification(s); last few (type,len)={raw['recent']}",
              flush=True)
        r.errors.clear()
        return False, {"e": "client timeout"}, stray.pop(sid, [])

    # --- telemetry stream -----------------------------------------------------
    await asyncio.sleep(3.0)
    await drain()
    snaps = []
    bad = 0
    for p in telem_frames:
        try:
            snaps.append(json.loads(p))
        except json.JSONDecodeError:
            bad += 1
    check("1 Hz telemetry parses", bool(snaps) and bad == 0,
          f"{len(snaps)} frame(s), {bad} bad, {len(snaps[0]) if snaps else 0} keys")
    if snaps:
        print(f"       sample: {json.dumps(snaps[-1])[:160]}", flush=True)

    # --- the firmware's own bench driver, watched over the live link ----------
    # bench.run drives the real request path on the unit: every read-only method,
    # three full config streams, two config.set round trips. The unit logs its own
    # counts, and every frame it sends is reassembled and merged here as well -
    # when the two sides disagree, the protocol is wrong, not one parser.
    if not a.skip_driver_wait:
        print("running the firmware bench driver (bench.run)", flush=True)
        ok, resp, _ = await request("bench.run", timeout=240)
        done = log.wait_line(DRIVER_DONE_RE.pattern, 15) if log.ok else None
        await drain()
        cs = stray.pop(0xBEEF, [])
        drv, last = {}, sum(1 for c in cs if c.get("last"))
        for c in cs:
            drv.update(c.get("d", {}))
        if drv:
            extra["driver_keys"] = drv
        check("bench.run answers and streams", ok and bool(cs),
              f"{len(cs)} chunk(s), {len(drv)} key(s); response {resp.get('r') or resp.get('e')}")
        check("bench stream closes exactly once", bool(cs) and last == 1,
              f"last-flag count {last} in {len(cs)} chunk(s)")
        check("bench driver finished on the unit", done is not None, done or "no CFGVERIFY: done line")
        if done and log.ok:
            def logcheck(label, pat, verdict):
                hits = re.findall(pat, log.text())
                check(f"unit log: {label}", bool(hits) and verdict(hits),
                      f"{len(hits)} match(es): {hits[-1] if hits else ''}")

            # "N of M ... K missing" has to read 198 of 198, 0 missing in every
            # pass, and no "not valid JSON" line anywhere - that one is what the
            # unit's own observer catches when a response is built wrong.
            logcheck("every config key reaches the unit's own mirror",
                     r"(\d+) of (\d+) table key\(s\) mirrored, (\d+) missing",
                     lambda h: all(t[0] == t[1] and t[2] == "0" for t in h))
            logcheck("the unit sees its own config.set come back",
                     r"config\.set mirrored - WEATHER_LAT (\S+) -> (-?[\d.]+)",
                     lambda h: h[-1][1] == "12.500")
            logcheck("the unit restores what it changed",
                     r"WEATHER_LAT restored to (-?[\d.]+)", lambda h: h[-1][1] != "-999.000")
            logcheck("no frame of the unit's own failed its JSON check",
                     r"frames (\d+) ok (\d+) bad", lambda h: h[-1][1] == "0")
            check("unit log: no 'not valid JSON' line", not log.grep("is not valid JSON"),
                  "; ".join(log.grep("is not valid JSON")[:2]))

    # --- read-only methods ---------------------------------------------------
    for m in ["ping", "snapshot", "live", "health", "perf", "boot", "sensors", "fuel",
              "ambient", "odo.get", "noise"]:
        ok, resp, chunks = await request(m)
        keys = len(resp.get("r", {})) if ok else 0
        check(f"method {m}", ok and not chunks, f"{keys} key(s)" if ok else str(resp.get("e")))
        if m == "perf" and ok:
            rr = resp["r"]
            print(f"       heap {rr.get('free_heap')} B, uptime {rr.get('uptime_s')} s, "
                  f"mac {rr.get('mac')}, ble mtu {rr.get('ble_mtu')}, stack {rr.get('stack_free')}", flush=True)

    # --- config.get: the mirror, from the client side -------------------------
    ok, resp, chunks = await request("config.get", timeout=60)
    merged, i_flags = {}, []
    for c in chunks:
        i_flags.append((c.get("i"), c.get("last")))
        merged.update(c.get("d", {}))
    closing = resp.get("r", {}) if ok else {}
    check("config.get streams", ok and len(chunks) > 1,
          f"{len(chunks)} chunk(s), closing response {closing}")
    check("chunk numbering", [i for i, _ in i_flags] == list(range(len(i_flags))) and
          sum(1 for _, last in i_flags if last) == 1 and i_flags[-1][1] is True,
          f"i sequence {[i for i, _ in i_flags]}, last flags {[int(l) for _, l in i_flags]}")
    check("chunk count agrees with the unit", len(chunks) == closing.get("chunks"),
          f"client {len(chunks)} vs unit {closing.get('chunks')}")
    check("key count agrees with the unit", len(merged) == closing.get("keys"),
          f"client {len(merged)} vs unit {closing.get('keys')}")
    drvkeys = extra.get("driver_keys")
    if drvkeys is not None:
        same = set(drvkeys) == set(merged)
        diff = set(drvkeys) ^ set(merged)
        check("client mirror == firmware mirror", same and drvkeys == merged,
              f"differing keys: {sorted(diff)[:8]}" if not same else f"{len(merged)} keys identical")
    biggest = max((len(json.dumps(c.get("d", {}))) for c in chunks), default=0)
    print(f"       largest chunk d-object {biggest} B; largest chunk frame "
          f"{max((len(json.dumps(c)) for c in chunks), default=0)} B", flush=True)
    # Telemetry has to survive a config stream: the RPC handler runs on the same
    # task as the 1 Hz pump, so the pump stalls for the length of the stream and
    # the app has to see that as one late frame, not a lost link.
    await asyncio.sleep(3.0)
    await drain()
    gaps = [y - x for x, y in zip(telem_at, telem_at[1:])]
    check("telemetry keeps running across a config stream", len(telem_at) >= 8 and max(gaps or [0]) < 4.0,
          f"{len(telem_at)} frame(s), largest gap {max(gaps or [0]):.1f} s")

    # --- config.set: change one key, see it come back -------------------------
    was = merged.get("WEATHER_CITY", "")
    ok, resp, _ = await request("config.set", {"WEATHER_CITY": "Bench Central"})
    check("config.set accepted", ok, str(resp.get("r") or resp.get("e")))
    ok, resp, chunks2 = await request("config.get")
    now = {}
    for c in chunks2:
        now.update(c.get("d", {}))
    check("config.set mirrored", ok and now.get("WEATHER_CITY") == "Bench Central",
          f"'{was}' -> '{now.get('WEATHER_CITY')}'")
    await asyncio.sleep(0.5)
    ok, resp, _ = await request("config.set", {"WEATHER_CITY": was})
    ok2, resp2, chunks3 = await request("config.get")
    back = {}
    for c in chunks3:
        back.update(c.get("d", {}))
    check("config.set restored", ok and ok2 and back.get("WEATHER_CITY") == was,
          f"now '{back.get('WEATHER_CITY')}' (was '{was}')")

    # --- framing sanity ------------------------------------------------------
    check("no framing errors", not r.errors, "; ".join(r.errors[:4]))
    open_slots = len(r.slots)
    check("no half-open messages left", open_slots == 0, f"{open_slots} slot(s)")

    await client.stop_notify(TELEM_UUID)
    await client.stop_notify(RPC_UUID)
    await client.disconnect()
    log.stop()

    failed = [c for c in checks if not c[1]]
    print(f"\n{len(checks) - len(failed)}/{len(checks)} checks passed", flush=True)
    for name, _, detail in failed:
        print(f"  FAILED: {name}  {detail}", flush=True)
    return 1 if failed else 0


async def _run(a):
    # A script that dies mid-run leaves the unit holding a dead link: it stops
    # advertising, and the next attempt then looks like a pairing bug.
    try:
        return await main(a)
    finally:
        c = HOLD.get("client")
        if c is not None:
            try:
                if c.is_connected:
                    await c.disconnect()
            except Exception:
                pass


if __name__ == "__main__":
    # The ESP log is bytes, the console is a code page; never let one odd byte
    # abort a run that is otherwise measuring fine.
    try:
        sys.stdout.reconfigure(errors="backslashreplace")
    except Exception:
        pass
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--port", default="COM7", help="USB serial port of the unit (log + passkey)")
    ap.add_argument("--no-serial", action="store_true", help="skip the USB log; type the passkey by hand")
    ap.add_argument("--prefix", default="DPP-", help="advertised name prefix to scan for")
    ap.add_argument("--scan-timeout", type=float, default=8.0)
    ap.add_argument("--timeout", type=float, default=8.0, help="per-request response timeout")
    ap.add_argument("--skip-driver-wait", action="store_true",
                    help="do not wait for the firmware's own bench driver run")
    ap.add_argument("--unpair", action="store_true",
                    help="drop the existing bond before pairing (clears a stale one)")
    sys.exit(asyncio.run(_run(ap.parse_args())))
