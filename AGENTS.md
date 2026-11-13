# AGENTS.md — Dashboard++ for ESP32

Rules for ALL agents (AI or human) working in this repository. These rules override agent defaults.

## 1. Decision Policy: Ask, Don't Guess
When you are unsure about anything — ambiguous requirements, conflicting options, risky assumptions, missing context — **stop and ask the user to decide**. Do not overthink or silently guess. The only exception is when the user explicitly instructs you to work alone / make your own calls for that task.

## 2. Commits
- **Every change must be committed on `main`.** No feature branches, no PR flow — work and commit directly on `main`.
- Commit after each logical unit of work so the history is incremental.
- **Everything outside `Implementation plans/` is tracked.** As of 2026-11-01 the app's own manual (`android/README.md`) is missing from disk and was never committed, so it is unrecoverable from git. If a copy turns up, commit it; do not treat an untracked doc as "local-only" — rule 6 excludes the plans folder and nothing else.

## 3. Releasing ESP Code
- When the user says **"release the ESP code"**, bump the version by **+0.0.1** (patch bump, e.g. `1.3.2` → `1.3.3`) unless the user explicitly states the exact target version.
- The firmware version string is `FW_VERSION` in `src/config.cpp` — **one compiled-in constant**. It is not stored in NVS and nothing fetched from the network can change it. (`VERSION_OVERRIDE` is a separate, user-set label for display and pull testing — not the build identity. The old `OTA_CURRENT_VERSION` / NVS `OTA_VER` pair is gone.)
- Commit the bump on `main` (per rule 2).
- **The built image must carry its own label.** `src/config.cpp` embeds `DPPVER=<FW_VERSION>` inside `firmware.bin`, and `scripts/fix_upload_layout.py` reports what it finds (`[image tag] firmware.bin declares itself 1.5.1 (marker at 0x202e4)`). If that line ever reads `WARNING: no DPPVER= marker in firmware.bin`, the literal got garbage-collected out of the link again — it happens when nothing in the compiled sources references `imageVersionTag()`; `setup()` printing it at boot is the reference that keeps it alive.
- **No signing, anywhere.** Updates are not signed, the firmware carries no public key, and the build flags that used to control verification (`-DOTA_REQUIRE_SIG`, `-DOTA_ALLOW_UNSIGNED`) are deleted. The maintainer asked for signatures out of every update path on 2026-10-30; **do not reintroduce signature verification, `.sig` release assets, or those flags without asking first** (rule 1). `scripts/ota_sign.py` stays in the repo as an out-of-band tool only — nothing on a device looks at its output.

## 4. README on Important Releases
- If a version bump includes **important changes** that must be reflected in the documentation, update `README.md` too (changelog entry and any affected reference sections).
- Push to GitHub as part of that release flow.

## 5. Flashing the ESP32
- Default flashing method: **PlatformIO over USB serial on COM7** (e.g. `pio run -t upload --upload-port COM7`), after `pio run` builds.
- The upload layout is repaired by `scripts/fix_upload_layout.py` (a `post:` extra script in `platformio.ini`): it moves the otadata write to this table's real offset (`0x1D000`) and removes PlatformIO's fixed `0xe000` write, which sits **inside `nvs`** here and was destroying two settings pages per flash. Keep that script: without it a wired flash may not boot what it just wrote, and it silently loses NVS settings.
- If esptool reports `Failed to start stub flasher. There was no response.`, retry once (at 115200 baud if needed); if it keeps failing, hold **BOOT** while tapping **RESET** to force download mode. Confirm the port with `esptool --port COM7 --baud 115200 read_mac`.
- If COM7 is unreachable / not detected, **stop and ask the user how to continue** — do not auto-fallback to a guessed port or method.
- **There is no Wi-Fi stack in this tree at all.** `src/web.cpp`, `src/webui.html` and
  the generated `src/webui_html_gz.h` were deleted on 2026-11-02 (BLE-only plan,
  step 0 cleanup) — there is no `build_src_filter` for them any more, nothing in
  `src/` is excluded, and there is nothing to put back. A unit flashed from this
  build has no SoftAP, no `192.168.4.1`, no REST endpoint and no IP address, so for
  that unit method 1 below is the only one that exists. Methods 2 and 3 apply only
  to units still running a Wi-Fi firmware (≤ 1.4.x); the page they talk to lives on
  those units and in git history, not in the tree — if a task depends on the Web
  Portal or the HTTP API being live on the bench unit, **ask before assuming it**.
- When asking, list the available flashing methods:
  1. USB serial (PlatformIO `--upload-port <other COM port>`) — works on every unit
  2. WiFi OTA via PlatformIO: `pio run -t upload --upload-port <ESP_IP>` (device must be on the `Dashboard_Config` SoftAP or a known LAN IP) — Wi-Fi firmwares only (≤ 1.4.x)
  3. Web Portal OTA upload (browser → `http://192.168.4.1` → System Actions → upload `firmware.bin`) — Wi-Fi firmwares only (≤ 1.4.x)
  4. **The Android app is the update path, and the unit is reached only over BLE — never over
     HTTP.** A phone has no way to reach a unit that has no IP, the HTTP updater was deleted with
     the server that served it (`UpdateFetcher`, `UpdateRepository`, `UpdateScreens` are gone), and
     **nothing in the app talks to a unit over a socket**. What the app *does* do since 2026-11-09,
     at the maintainer's explicit request, is fetch the image itself: the firmware card carries a
     version dropdown backed by this project's GitHub releases
     (`update/FirmwareReleases.kt` → `api.github.com`), downloads the chosen release's
     `firmware.bin` into the app's cache, and hands it to the same `OtaController.pickFile()` door
     a hand-picked file comes through. That widened "the app ships no downloader" and nothing else:
     there is still no HTTP server or updater **on the unit**, no HTTP client pointed at a unit
     anywhere in the app, and no `.sig` / SHA-256 verification (rule 3). The local file picker
     (Storage Access Framework, `CHOOSE FILE`) stays as the second source. Both doors read size /
     CRC-32 / `DPPVER` and push over the OTA characteristic; the card is
     `ui/screens/FirmwareOtaControl.kt` (it lives on **App Settings**, not the unit's settings
     page, since 2026-11-09), the unit's side is `src/bt_ota.cpp`
     (`ota.begin` / `ota.end` / `ota.status` / `ota.abort`). Never add an HTTP updater to the unit,
     and never point a phone-side fetcher at a unit address. The contract (phase **2b** of
     `Implementation plans/BLE-ONLY-ARCHITECTURE-PLAN.md`, §4.7 — now code on both ends):
     JSON `begin {size, crc32}` → `begin_ok` with the offset and the MTU-capped chunk size,
     `offset (4 B LE) + payload` writes, `ack` every 4 KiB (one flash sector: an ACK is a claim
     about flash, so the unit never ACKs further ahead than it has actually drained), `end` →
     CRC-32 + `esp_ota_end()` verify → flip to the spare slot → tagged reboot. **`ota.end` drains
     the handoff ring before it counts bytes** — the link keeps the frames and the RPC in order, but
     `btTask` takes the RPC up before its next poll pass, and skipping that drain reports a complete
     image as `bad_length` (bench, 2026-11-03). **120 s** silence window on both ends
     (`src/bt_ota.cpp` `OTA_SILENCE_MS` = `OtaController.RESUME_WINDOW_MS`); mid-install the
     unit answers **only** `ping` and `ota.*` (`ota_in_progress` for the rest) **and the dashboard
     is parked** (`otaQuietActive` holds `loop()` and the sensor task at their tops; the update
     screen ticks at ~120 ms, and a refused install restores the producers, the sprite and the font
     on its own); an image must
     fit the app slot, which is why the build gate is **1.80 MB**. **No signing anywhere**
     (rule 3) — that CRC-32 is the only integrity check on the wire, and the unit has no
     version gate: the app shows both versions and asks before reflashing the same one.
     There is **no** cloud pull on the unit: it never fetches firmware by itself, and there
     is no self-update code left.

## 6. Implementation Plans — Never in Git
- All implementation plans must be written/exported into the `Implementation plans/` folder at the repo root.
- That folder is **private/local-only**: never commit it and never push it to GitHub. It is in `.gitignore`; keep it there and do not force-add its contents.

## 7. Unknowns: Search, Don't Hallucinate
- If you don't know something, **do a web search** — you have the tools for it. Verify facts (APIs, datasheets, register maps, library behavior) against sources instead of guessing or making things up.

## 8. Android App — Always Produce a Testable APK
- While working in `android/`, the work is **not done** until a **usable APK is built for testing**.
- Ship the **debug build** (per the distribution decision in the app implementation plan): from `android/`, run `gradlew.bat assembleDebug` → APK lands in `android/app/build/outputs/apk/debug/`.
- A code-only change that was not built into an APK is incomplete.

## 9. There Is No WebUI — the Settings Surface Is the Schema Pipeline (retired 2026-11-02)
- `src/webui.html`, `src/web.cpp`, `src/webui_html_gz.h` and `scripts/gzip_webui.py` are
  **deleted**: nothing is gzipped into the firmware and the unit serves no page. Do not
  reintroduce an embedded page, an HTTP server or a `src_filter` that excludes one without
  asking first (rule 1).
- The settings surface today is the **Android app rendering a generated schema**:
  `android/app/src/main/assets/config_schema.json`, produced from the `CFG_TABLE` in
  `src/config.cpp` plus `scripts/config_schema_presentation.json` by
  `scripts/gen_config_schema.py`.
- Touching that pipeline is not done until: `python scripts/gen_config_schema.py --check`
  is green, the app's unit tests pass, the APK builds (rule 8) and `pio run` passes
  (rule 25). Group icons come from the same tree via `scripts/icons_to_drawables.py`.

## 10. Pins Are Sacred
- The README pinout matrix reflects the real board wiring. No GPIO reassignment or repurposing without user approval.

## 11. FreeRTOS Task Map
- Respect the core split: Core 0 = GNSS + the BLE link (`GpsTaskCore0`, `BtTaskCore0`), Core 1 = vehicle sensors + display (`SensorTaskCore1`); one CPU probe per core.
- Protect shared state with `g_stateMutex`; never block in ISRs.

## 12. NVS Wear Discipline
- Throttle flash writes: accumulate in RAM, write only on significant change (odometer pattern: write at full 1 km increments).
- Never write NVS in tight loops.

## 13. No Surprise Dependency Bumps
- Do not upgrade PlatformIO, the Arduino core, LovyanGFX, ArduinoJson, or TinyGPSPlus without asking first.

## 14. RAM Discipline
- ESP32 memory is tight (520 KB SRAM): no new per-frame or per-request heap allocations; use static / fixed-size buffers.
- Watch the build size — 4 MB flash with the custom `partitions.csv` layout.

## 15. Generated Files Are Off-Limits
- Never hand-edit generated files: `android/app/src/main/assets/config_schema.json` (from `scripts/gen_config_schema.py`), `android/app/src/main/res/drawable/ic_grp_*.xml` (from `scripts/icons_to_drawables.py`) or `include/*_vlw.h` (from `scripts/vlw_to_header.py`).
- Always regenerate them: the schema and icons by running their script, the fonts via `pio run`. (`src/webui_html_gz.h` and `scripts/gzip_webui.py` were deleted with the WebUI — rule 9.)

## 16. English Only in Code
- All code comments, log strings, and UI text in English.

## 17. BLE RPC Compatibility
- The Android app depends on the BLE link: RPC method names, the frame types in `src/bt_proto.h` (`BT_F_REQ` … `BT_F_CHUNK`, 1–10, mirrored verbatim in `android/app/src/main/java/com/alefinot/dashboardpp/ble/BleProtocol.kt`), the 8-byte frame header, the FIRST/LAST reassembly rules and every field inside a telemetry snapshot. Changing/renaming/adding/removing one requires updating **every consumer in the same change** — `src/bt.cpp` / `src/bt_rpc.cpp` / `src/bt_proto.h` on the unit, `BleProtocol.kt` / `BleLink.kt` / `BleApi.kt` in the app, the protocol tests on both sides, and the README method table.
- The REST endpoint table in the README is **historical**: it describes units still running a Wi-Fi firmware (≤ 1.4.x). Nothing in this tree serves HTTP; do not add an endpoint.

## 18. New NVS Config Param Checklist
- A new config param needs **all of these in one commit**: default + `CFG_TABLE` row in `src/config.cpp`, an entry in `scripts/config_schema_presentation.json` (label, group, control type), the regenerated `config_schema.json` asset (rule 15), and a README reference. Backups need no template — a backup file is written from the table itself.
- New NVS keys are renamed-and-carried, not silently re-defaulted: follow `cfgCarryRenamedKeys()` in `src/config.cpp` when a key changes name so an owner's stored value survives the upgrade.

## 19. The Settings UI Carries Its Own Data
- The app renders its settings form from the bundled schema asset — it must never fetch UI structure, labels or icons over the network. Weather is the one deliberate network exception: the phone fetches it and pushes the result to the unit (`weather.set`), because the unit has no radio.
- (The old rule was "no external CDN in `webui.html`" — that file is deleted, rule 9.)

## 20. Keep Demo Mode Working
- Simulation / demo mode is the primary no-hardware test path; UI/sensor changes must not break it.
- It is enabled through the app (Dashboard settings → **System & General** → **General** card → *Enable Sensor Demo Mode*, key `ENABLE_DEMO_MODE`) or a `config.set` over the BLE link — not through a Web page.

## 21. No Secrets in the Repo
- Never commit credentials, WiFi passwords, or API keys.

## 22. Commit Message Style
- Imperative mood, matching existing history (e.g. `Fix hall speed spikes, add tunable filters, bump to 1.3.2`).
- Release commits include the version bump ("bump to X.Y.Z").

## 23. Push Policy
- **Always push when committing** — every commit on `main` is pushed to GitHub right after it is made.
- **Which remote (decided 2026-11-05):** push to **`origin` = `alefinot/Dashboard-V2`** and nothing
  else. That repository is a progress-savings mirror; the project's intended final home is the pre-BLE
  repository `alefinot/Dashboard-for-ESP32`, still wired into this checkout as the remote **`old`**.
  The maintainer chose **"switch once at the end"** over mirroring as you go: **do not** push commits,
  tags or releases to `old` while work continues, and do not delete the remote.
- **The move is a fast-forward, verified:** `old/main` = `0c64c586` is an ancestor of `main`, so the
  whole V2 line can be published later with `git push old main` followed by `git push old --tags`.
  Nothing needs grafting or squashing — do not invent a history-rewrite step.
- **At switch time**, confirm with the maintainer and then: duplicate the `V2.0.x` releases into the
  old repo with both artifacts (rules 27/28; 2.0.2's `firmware.bin` and APK are the ones this tree
  builds, earlier ones re-download from V2 or rebuild from their tags), re-point the three repo-name
  strings — the clone block in `README.md`, the tile **User-Agent** policy string in
  `android/.../ui/map/RouteMap.kt`, and the releases URL + User-Agent in
  `android/.../update/FirmwareReleases.kt` (the app's firmware-version dropdown reads
  `api.github.com/repos/alefinot/Dashboard-V2/releases`; after the switch a phone would otherwise
  keep listing the archived repository's images) — all three app changes mean an APK rebuild +
  `versionCode` bump (rules 8/24);
  then `git remote rename origin v2 && git remote rename old origin` so this rule keeps pointing at
  the real home, and archive the V2 repository.

## 24. Android versionCode
- Bump `versionCode` in `android/app/build.gradle.kts` on every shared/test APK (per the app implementation plan).

## 25. Leave No Broken Build
- Every task ends green: `pio run` must pass (plus the APK build when `android/` was touched).

## 26. Plan-First for Big Features
- Non-trivial features: first write an implementation plan into `Implementation plans/` and get the user's sign-off before coding starts.

## 27. Release Assets — Always Ship firmware.bin and the APK
- Every GitHub release must attach **both** artifacts: the ESP firmware binary (`.pio/build/esp32dev/firmware.bin`) and the Android app debug APK (`android/app/build/outputs/apk/debug/app-debug.apk`). A release missing either is incomplete.
- **Do not attach a `.sig` asset** — no device can verify one (rule 3). Ship exactly **one** `.bin` asset per release: a second one only makes a human guessing which image to flash.

## 28. GitHub Release Title Format
- Release titles are **just `Vx.x.x`** (e.g. `V1.3.3`) — no extra text, matching how they were titled before.

## 29. Graphify Output — Code-Only Sync
- When the **code** of the project changes, update the graph with the **AST path only**: `graphify update .` (local AST, no API key, no LLM tokens). It refreshes `graphify-out/` (`graph.json`, `graph.html`, `GRAPH_REPORT.md`) for the code in the repo. Run it **once, at the end of the code change** — not after every file edit, and not at all on doc-only work.
- **Never run `graphify . --update`** as part of normal work: it queues doc/paper/image files for LLM semantic extraction, needs an API key (`GEMINI_API_KEY` / `ANTHROPIC_API_KEY` / …), and aborts without writing anything when no key is set. Semantic extraction and community naming (`graphify label`) only when the user explicitly asks for it.
- Doc / non-code file changes (`AGENTS.md`, `README.md`, images, plans, etc.) do **not** trigger any graph update.
- `graphify-out/` and `graphify-watch.bat` are gitignored local-only — never commit them. For live updates while coding, run `graphify-watch.bat` (`graphify watch .`, also AST-only, no LLM).

## 30. PCB Work — KiCad's DRC Is the Referee
- In `hardware/`, the **only** clearance/courtyard/edge/text verdict that counts is KiCad's own:
  `kicad-cli pcb drc --severity-all -o drc-report.txt hardware/dashboard-plusplus-carrier.kicad_pcb`
  (`scripts/make_pcb.py` runs it at the end; target = exit 0, `Found 0 DRC violations`,
  `unconnected_items` allowed while nothing is routed).
- **Do not build or "fix" a second geometry checker** to predict DRC results. The bounding-box
  notes printed by `make_pcb.py` (`OUTSIDE BOARD` / `OVERLAP` / `SHORT` / `HOLES TOO CLOSE`) are
  hints to aim at, nothing more — never spend time making them agree with KiCad
  (circles/arc courtyards, mirrored text and back-layer swaps make an exact clone expensive).
- Read the DRC report (it names the exact pad/edge and coordinates), fix that, re-run. Iterate
  on KiCad's output, not on a local model of it.

## 31. Android App — Verify on a Real Device via ADB
- The APK build (rule 8) is **necessary but not sufficient**: whenever an agent works in `android/`,
  the work is not done until the app has been **installed, launched and verified directly on a real
  Android device** connected to the PC over ADB — wired USB or wireless, either is acceptable.
- Device priority:
  1. **Xiaomi Mi 9 Lite** (codename `pyxis`, same hardware as the Mi CC9) — the preferred bench phone.
     Identify it with `adb devices -l` or `adb shell getprop ro.product.marketname` (reports
     `Mi 9 Lite` / `CC9`).
  2. If the Mi 9 Lite is not connected, **scan for other devices** (`adb devices`) and use one of them;
     if several are connected and the choice is unclear, ask the user (rule 1).
  3. If **no device** is online in `adb devices`, **stop and ask the user to connect one** (rule 1) —
     never silently skip on-device verification or report it as done.
- The verification loop: `adb install -r app/build/outputs/apk/debug/app-debug.apk` (from `android/`) →
  `adb shell am start -n com.alefinot.dashboardpp/.MainActivity` → exercise the screens/behaviour the change
  touched → check `adb logcat` for crashes, ANRs and BLE/link errors. Demo mode (rule 20) makes this
  possible without a vehicle or ESP unit attached.
- Wireless ADB is a valid fallback for the cable: Developer options → Wireless debugging, then
  `adb pair <ip>:<pairing-port>` + `adb connect <ip>:<port>`; or, after one USB connection,
  `adb tcpip 5555` followed by `adb connect <phone-ip>:5555`.

## 32. Update Process Backend — Change Only on the Maintainer's Explicit Order
- **Never change the backend of the update process unless the maintainer tells you to, for that task.**
  The backend is the machinery that moves and installs a firmware/APK image: on the unit,
  `src/bt_ota.cpp` and the `ota.*` RPC methods; in the app, `ble/OtaController.kt`, `ble/OtaCore.kt`,
  `ble/OtaFrames`/OTA write logic, `service/OtaGuardService.kt`, and the `update/` package
  (`FirmwareReleases.kt`, `AppUpdate.kt`, downloaders). The card UI and its prose
  (`ui/screens/FirmwareOtaControl.kt`, `AppUpdateControl.kt`) are not backend — they may change as
  long as nothing below them does.
- No opportunistic touches: do not retune pacing/windows/ACK logic, do not refactor, do not
  "improve" an update path in passing. If a bug's honest fix appears to need a backend change,
  **stop and ask** (rule 1) — the working install path is considered settled ground.

## Project Quick Reference
- **Firmware:** ESP32 WROOM-32, PlatformIO project in repo root (`platformio.ini`, env `esp32dev`), Arduino framework, LovyanGFX 4.0" ILI9488 480×320 display.
- **Build:** `pio run` (the pre-script compiles the VLW fonts into headers; the post-script repairs the upload layout and reports the image tag — run `pio run`, not a plain compile).
- **Version:** `FW_VERSION` in `src/config.cpp` (see rule 3).
- **Docs:** `README.md` is the full technical reference (architecture, pinout, BLE RPC reference, historical REST table for ≤ 1.4.x units, NVS params, changelog).
- **Plans:** `Implementation plans/` — local-only (never committed/pushed; see rule 6).
- **Android app:** `android/` — companion app (Kotlin/Compose, Gradle). Always finish with a debug APK build (rule 8) **and** on-device verification over ADB, Mi 9 Lite first (rule 31).
- **Transport:** BLE GATT service `d9a1f000-6c1e-4b8a-9f2d-3e7c1a5b9d40`, characteristics `…f001` telemetry (notify), `…f002` RPC (write + notify), `…f003` OTA (write-nr); framed by `src/bt.cpp` with the wire format in `src/bt_proto.h`, methods served by `src/bt_rpc.cpp`.
- **Settings schema:** `android/app/src/main/assets/config_schema.json` — generated from `CFG_TABLE` + `scripts/config_schema_presentation.json` (rules 9, 15, 18).
- **Config backups:** NVS backup/restore through the Android app; a backup file is written from `CFG_TABLE`, there is no hand-kept template.
- **Demo mode:** app → Dashboard settings → **System & General** → **General** card → *Enable Sensor Demo Mode*; synth telemetry for bench testing.
- **Graphify:** `graphify-out/` knowledge graph — local-only, code-only AST updates (`graphify update .`, once per code change; never `graphify . --update` in normal work — rule 29); `graphify-watch.bat` runs the live AST watcher.
- **Hardware:** `hardware/` — 85×55 mm carrier PCB generated by `hardware/scripts/make_pcb.py` (board + `footprints/dpp.pretty` + DRC report in one run). Board-side reference: `hardware/PINOUT.md`. Rules referee = KiCad DRC (rule 30).
