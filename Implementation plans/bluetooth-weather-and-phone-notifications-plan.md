# Dashboard++ — Phone-Relayed Weather + BLE Notification Bar (song + alerts)

Detailed implementation plan for two new capabilities that share one new transport:

1. **Weather relay** — the Android companion app fetches the forecast **directly from Open-Meteo on the phone** (not the ESP's Wi-Fi fetch), parses it, and pushes it to the ESP32 over a new **Bluetooth Low Energy (BLE)** link. The ESP renders it into the **existing weather widget** with zero visual change.
2. **Notification bar** — the app forwards **incoming Android notifications** **and** the **current song** over the same BLE link; the ESP renders a **thin bar** in the free top band (icon left). The **song is persistent** (the default); a **notification/SMS/call takes over for 7 s** (then returns to the song) **while the song plays**; when **no song** is playing, a notification **stays** (no timeout).

This is a **plan, not a build**. It mirrors the structure and conventions of the other documents in this folder (`Dashboard-Android-App-Implementation-Plan.md`, `IMPLEMENTATION_PLAN.md`).

---

## 0. How to use this document

- **§1** restates the goal and the four hard constraints that shape every decision below.
- **§2** is the **verified ground truth** from the repo (exact symbols, endpoints, the fact that BLE does not exist yet).
- **§3** fixes the **architecture** (who is GATT server, why) — the one decision that locks in everything else.
- **§4** is the **BLE protocol** (service/characteristic UUIDs, framing, JSON shapes).
- **§5–§7** are the three workstreams, each with concrete files, symbols, and integration points:
  - **§5** Android: fetch + parse + BLE central.
  - **§6** Android: notification listener + parser + outbox **(+ §6.5 the Google Maps nav parser — step 2)**.
  - **§7** ESP32: GATT server task, weather ingest, the **top notification bar (song + alerts)**, and the **§7.4 nav widget (left of the speed — step 2)**.
- **§8** is the **NVS / Web UI / config** surface (new params + the exact `processConfig` pattern).
- **§9** is **memory & coexistence risk** (the real constraint on WROOM-32).
- **§10** is **manifest / build wiring** (Android manifest + permissions, `platformio.ini`).
- **§11** is **edge cases & robustness**.
- **§12** is the **build order** (phases) and a **testing checklist**.
- **§13** is **out of scope** (explicitly deferred, with reasons).

Read §2 before touching code — every symbol it names exists in the tree today.

---

## 1. Goal and the four hard constraints

Build two BLE-relayed features on the existing Dashboard++ device (**step 1**), plus one **heap-gated step 2**:

1. **Weather**: phone is the source of truth for Open-Meteo; ESP is a display.
2. **Notifications**: phone is the source of truth for the notification feed; ESP is a display.
3. **Google Maps turn-by-turn nav (step 2)**: the phone parses the **Google Maps nav notification** (the persistent notification Google Maps posts while navigating) and pushes it over the *same* BLE link; the ESP renders a **small arrow + distance + road widget left of the speed**. It appears only while nav is active, and disappears when the user stops. Built **only if the §9 free-heap gate stays green** after step 1.

Four constraints shape every choice below and are worth stating up front:

- **C1 — The ESP must not need Wi-Fi to do either job — and it no longer fetches weather at all.** Weather is fetched by the phone (which has internet) and pushed over BLE; notifications come from the phone over the same link. The ESP's Wi-Fi weather fetch is **removed**, not demoted: the phone is the only source of weather data. This is the user's core requirement ("directly from the phone instead of using the ESP").
- **C2 — One new transport, two payloads.** Both features ride the **same** BLE GATT link. Do not build a second radio path.
- **C3 — The ESP display is dirty-rendered, 480×320 ILI9488 (LovyanGFX), ~130 KB free heap** (measured on the current build, before any of this plan's changes). Anything the bar draws must be RAM-cheap and must not disturb the existing dirty pipeline. This is the main reason the bar is a *thin* top band (y=0–32, the free space above the date/time row), not a full-screen mode.
- **C4 — The ESP already knows how to show the weather.** `g_weatherData` (a `WeatherData`) is already the single source the weather widget reads. The phone just has to *fill* it. We do not re-implement the widget.

Everything else is detail.

---

## 2. Verified ground truth (state of the repo)

These are facts read from the tree, not assumptions. Any symbol you do not see here is not yours to invent.

### 2.1 ESP32 firmware (`src/`)

- **`src/web.cpp` — `updateWeather()`** does the Open-Meteo HTTP fetch against
  `https://api.open-meteo.com/v1/forecast?latitude=..&longitude=..&current=temperature_2m,relative_humidity_2m,weather_code,cloud_cover,wind_speed_10m,wind_direction_10m&daily=sunrise,sunset&timezone=auto&forecast_days=1`.
  The phone-side parser must produce **exactly** the fields the ESP already knows how to store (§7.2).
- **`src/dashboard.h` — `struct WeatherData`** (fields verified): `temperature, humidity, windSpeed, windDirection, weatherCode, cloudCover, sunriseTime, sunsetTime, cityName[48], valid, lastUpdated`. `extern WeatherData g_weatherData;` is the shared struct.
- **Weather ingest point**: `updateWeather()` runs on `weatherTask` (core 1, pinned) and, on success, fills `g_weatherData` **under `g_stateMutex`** and sets `valid=true`, `lastUpdated=millis()`. It is driven from the `webServerTask` loop: gated on `WiFi.status()==WL_CONNECTED`, with `startWeatherFetch()` / `weatherTaskRunning` / `lastWeatherCheck` / `WEATHER_REFRESH_MIN` and a 30 s hung-task guard. This is the block we **remove** (see §7.2); the ESP no longer fetches weather over Wi-Fi at all.
- **`src/ui.cpp` — weather widget**: `drawWeatherWidget(int x,int y,const SensorSnapshot&,bool forceDraw)` and `drawWeatherIcon(display,code,x,y)` are the only renderers. The widget is drawn in `updateBigDisplay()` at `wx = BIG_CENTER_X + OFFSET_WEATHER_X - 240`, `wy = BIG_CENTER_Y + OFFSET_WEATHER_Y - 14`, and is gated on a `weatherChanged` dirty check that reads `g_weatherData` (`temperature/humidity/weatherCode/sunsetTime/sunriseTime/cityName/valid/lastUpdated`) and `weatherIsNight(snap)`. **So writing `g_weatherData` is sufficient — the widget redraws itself.**
- **Frame pipeline (`src/main.cpp` `loop()`)**: `updateBigDisplay(snap); drawFpsOverlay(); drawGpsDebugOverlay(); checkNightMode(snap);` — the bar is added as another post-`updateBigDisplay` overlay in the same place.
- **Display**: 480×320 ILI9488, LovyanGFX, partial-buffer dirty rendering. `forceFullRedraw` + per-element dirty checks are the redraw contract. Free heap steady-state is **~130 KB** — **confirmed** with all current features active and **Wi-Fi up** (before any of this plan's changes; the code has no hard-coded number, it logs `ESP.getFreeHeap()` live).
- **REST API (still live, still needed for config/OTA)**: `/api/config` (GET/POST), `/api/odo`, `/api/time`, `/api/health`, `/api/perf`, `/api/ambient`, `/api/ota`, `/api/reboot`, `/api/sleep`, `/api/reset`. The BLE channel does **not** replace this; it is a parallel data channel.
- **Config pattern**: NVS-backed params in `config.cpp` via `CFG_BOOL/CFG_INT/CFG_STR/CFG_FLT`; `processConfig()` builds the `/api/config` JSON, and the Web UI card is the HTML in `webui.html` (weather card exists today).
- **No BLE exists anywhere in the firmware.** `grep -r BLE` returns nothing. `platformio.ini` has no BLE `lib_dep`.

### 2.2 Android app (`android/`)

- **Compose single-activity app**: `MainActivity.kt` hosts `DashboardRoot(activity, vm)`; `MainActivity` is a thin shell that also hosts a `DashboardWebScreen` (a `WebView` pointed at the ESP's Web UI over HTTP).
- **`ConnectionViewModel.kt`** is the state machine: `ConnectionUiState` (sealed: `Booting, CheckingCache, CheckingSoftAp, Discovering, ManualEntryNeeded, ConnectionLost, NoWifi, Connected(ip, version)`). Discovery is **Wi-Fi/NSD-based** (`NsdDiscoverer`, `SubnetScanner`) and the "device" it finds is the ESP's HTTP endpoint.
- **HTTP**: OkHttp is already a dependency; `WeatherData.kt`-style DTOs already exist; there is an existing `weather` fetch in the app today (the `Weather` config + the `updateWeather` path).
- **Manifest today** (`AndroidManifest.xml`): permissions `INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE, CHANGE_WIFI_MULTICAST_STATE`; **no** Bluetooth permissions, **no** `NotificationListenerService` declaration.
- **Build**: `app/build.gradle.kts` is Kotlin-JVM + Compose (`com.android.application` 8.8.1, `kotlin.android` 2.1.0, `kotlin.plugin.compose` 2.1.0), `compileSdk 35 / minSdk 26 / targetSdk 35`.

**Consequence**: BLE is brand-new on *both* sides. There is no existing BLE code to extend, no GATT to inherit, no notification listener to wire in. The only existing "weather" code on the phone is a fetch+render, not a parser-over-a-wire.

### 2.3 Step-2 reference (what the nav widget must match — cited only)

A working reference for the *end result* is the open-source **Chronos** project (`fbiego`): the **Chronos app** (Android) + the **ChronosESP32** ESP32 library, and its **Navio** navigation display. Navio's stated model: *"receives navigation instructions from the Chronos app over Bluetooth Low Energy... a focused, glanceable interface for **directions, distance, progress, and arrival** information... See the **next maneuver, remaining distance, route progress, and destination status** at a glance."* Its `navigation.ino` example confirms the exact data shape: a **nav active/inactive state** (`CF_NAV_DATA` → `a ? "Active" : "Inactive"`) plus a `Navigation` record of **`directions` (the maneuver text), `eta`, `duration`, `distance`, `title` (the road), `speed`**, and a monochrome **`icon`** (with an `iconCRC`).

- **We do not use the Chronos app or the ChronosESP32 library** — the user wants it built **inside the single companion app** (the Dashboard++ Android app), reusing this plan's BLE link + the §6 notification listener. Chronos is the *reference point for the end result*, not a dependency.
- **What Google Maps actually posts** (the parser's input): while navigating, Google Maps runs a **persistent** system notification showing *"distance until next turn, next street name, etc."* (e.g. "In 300 m, turn right", "In 1.2 km, bear right onto Avenue Foch", "In 400 m, arrive at destination", "Arrived at destination"). That notification text is the only source — the phone parses *it* (no Maps SDK, no API key).

---

## 3. Architecture (the one decision that locks everything in)

**Phone = BLE Central (GATT client). ESP32 = BLE Peripheral (GATT server).**

- The **ESP advertises** a fixed 16-bit **service UUID** and a human name (`"Dashboard++"`). It does not pair (no PIN) — it is a *peripheral* that accepts one central.
- The **phone scans** for that service UUID, connects, discovers, subscribes to the ESP→phone notify characteristic, and writes to the phone→ESP write characteristic.
- **Why this direction**: the device is the *sink* for data the phone already has (weather + notifications). A peripheral that accepts one client is the simplest, lowest-RAM, no-pairing GATT shape on ESP32 (NimBLE-Arduino). The phone already does BLE centrally for everything else.
- **One service, two characteristics** (defined in §4). Weather and notifications are *messages on the same pipe*, distinguished by a type byte — not two services.

**Data flow (steady state):**

```
Open-Meteo ──(HTTPS, phone has internet)──▶ Phone parser ──▶ BleLink.write(WEATHER json)
                                                        ──▶ ESP ingest ──▶ g_weatherData ──▶ existing widget
Android NotificationManager ──▶ NotificationListenerService ──▶ NotifOutbox ──▶ BleLink.write(NOTIF + MEDIA json)
                                                        ──▶ ESP ingest ──▶ bar queue ──▶ bar render (song + alerts)
ESP ──(BLE notify)──▶ BleLink.read STATUS json ──▶ app health/ACK chip
```

The ESP's **Wi-Fi + Web UI stay up** the whole time (config, OTA, manual entry). BLE is a *parallel* channel to the same physical device, not a replacement for the Web UI.

**The ESP's own Wi-Fi weather fetch is removed, not suppressed.** The phone is the only source of weather data. `startWeatherFetch()` / `updateWeather()` and the periodic fetch block in the `webServerTask` loop are removed (see §7.2); `g_weatherData` is now written only by the BLE ingest. The weather widget is unchanged — it shows the last-received data, or "no data" until the phone pushes. The accepted trade-off: with no phone / BLE link the device shows no (new) weather, and no Open-Meteo traffic ever leaves the ESP.

---

## 4. BLE GATT protocol (v1)

All values are **provisional** — finalize the exact bytes in one place (§4.1) and keep them there; do not scatter UUIDs.

### 4.1 Provisional identifiers (finalize before shipping)

- **Service UUID (16-bit)**: `0xD11A` — *"D"ashboard*.
- **Characteristic A — `RX` (phone→ESP, write-without-response)**: UUID `0xD101`.
- **Characteristic B — `TX` (ESP→phone, notify + read)**: UUID `0xD102`.
- **Advertise name**: `"Dashboard++"`. **Advertise data**: the service UUID (16-bit) so the phone can filter.

> The exact bytes are not functionally important as long as they are unique on the phone and stable across firmware versions. Change them in one constant block and ship.

### 4.2 Framing (RX, phone→ESP)

Each BLE write carries one **frame**; the ESP reassembles a logical message from the byte stream. Frame:

```
+--------+--------+----------------+
| type   | len    | payload        |
| 1 byte | 2 bytes(LE) | JSON bytes |
+--------+--------+----------------+
```

- `type` = `0x01` WEATHER, `0x02` NOTIFICATION (**a temporary bar override** — 7 s when the song is playing; else it stays), `0x03` PING (keep-alive / link-check), `0x04` ACK (optional, phone→ESP ack of an ESP event), **`0x05` NAV** (step 2 — the §4.3 NAV frame; consumed by the §7.4 widget), `0x06` **MEDIA** (the §4.3 MEDIA frame — the **persistent** now-playing song; consumed by the §7.3 bar).
- `len` = 16-bit little-endian length of the **JSON payload** (not including the 3-byte header).
- `payload` = UTF-8 JSON. The ESP accumulates bytes in a ring until `len` bytes are present, then parses. A mid-frame disconnect is harmless (partial frame is discarded on length mismatch / type change).

**Why a header+JSON instead of a raw JSON stream**: BLE MTU chunking splits writes at arbitrary byte offsets; a length-prefixed frame gives the ESP an unambiguous message boundary and a cheap type dispatch. The JSON is human-readable on the wire (you can `tcpdump`-equivalent it from the phone with nRF Connect for debugging).

### 4.3 JSON shapes (the only two the phone sends)

**WEATHER** (fields are **exactly** what the ESP's weather ingest stores in `g_weatherData` — no new fields):

```json
{"t":"wx","city":"Lisbon","lat":38.72,"lon":-9.14,
 "temp":21.4,"hum":58,"wind":13.2,"windDir":225,
 "code":2,"cloud":40,
 "sun":"07:12","sunset":"19:48","epoch":1735000000}
```

Mapping (phone → `g_weatherData`): `temp→temperature`, `hum→humidity`, `wind→windSpeed` (km/h, as Open-Meteo sends; the widget's `/3.6` to m/s is untouched), `windDir→windDirection`, `code→weatherCode`, `cloud→cloudCover`, `sun→sunriseTime`, `sunset→sunsetTime`, `city→cityName`, `epoch→lastUpdated` (converted). `valid=true`.

> `wind` is **km/h** in the JSON because Open-Meteo `wind_speed_10m` is km/h and the widget's comment says it stores km/h and divides by 3.6 for the metric m/s display. Do **not** pre-convert in the phone.

**NOTIFICATION**:

```json
{"t":"ntf","app":"com.whatsapp","title":"New message",
 "body":"Hey, are we still on for tonight?","epoch":1735000001234,"icon":"msg"}
```

- `app` = package name (drives the monogram / color). `title` = `Notification.Extras.TITLE`. `body` = `TEXT` (or `TITLE_BIG`/`TEXT_BIG` when present). `icon` = a **short token** (`"msg"`, `"call"`, `"mail"`, or the app's first letter), *not* a bitmap — a 480×320 device cannot pull app icons over BLE cheaply; the ESP draws a colored circle with the initial (see §7.3). **Temporary**: this is a **bar override** — the ESP shows it for 7 s (§7.3) **when the song is playing**, then the bar returns to the song; **when no song** is playing, the alert **stays** (no timeout).

**MEDIA** (the current song — the **persistent** bar content):

```json
{"t":"media","artist":"Beethoven","song":"Moonlight","icon":"mus"}
```

- `artist` = the artist name, `song` = the track title (from the media notification's `EXTRA_TITLE` / `EXTRA_TEXT`). `icon` = a **short token** (`"mus"` for a music-note glyph, or the album-art first-letter) — *not* a bitmap (a 480×320 device cannot pull album art over BLE cheaply; the ESP draws a small music-note glyph / monogram circle, §7.3).
- **Persistent**: the ESP shows this in the bar (the default bar content); a `NOTIFICATION` frame temporarily overrides the bar (7 s when the song is playing; else it stays).

**NAV** (step 2 — the Google Maps turn-by-turn frame; §6.5 produces it, §7.4 consumes it):

```json
{"t":"nav","on":true,"act":"right","d":300,"u":"m",
 "road":"Avenue Foch","eta":"12 min","arr":false}
```

- `on` = **nav active** (true while navigating / showing a maneuver, false when stopped / between sessions). Drives the §7.4 **show/hide** — the widget is visible only while `on`.
- `act` = the **maneuver** (the arrow): one of `left`, `right`, `straight`, `uturn`, `merge`, `arrive`. The ESP draws the arrow glyph from this (§7.4) — **not** from an icon bitmap.
- `d` = distance to the next maneuver (number), `u` = the unit (`"m"` or `"km"`). The ESP formats `d`+`u` (`300 m`, `1.2 km`).
- `road` = next street name (may be empty). `eta` = remaining ETA string (optional). `arr` = arrival flag (true for "Arrived at destination").
- **Idempotent + stateful**: `on=false` is the "stop" signal (it *hides* the widget); `on=true` is a "live" signal (it *shows* it). The phone re-sends a `NAV` frame whenever the parsed notification changes (the distance/road count down); a mid-drive disconnect just loses the next update (the last value stays shown until a new frame or `on=false`).

**TX (ESP→phone) STATUS** (sent on connect + on demand):

```json
{"t":"st","heap":41234,"fps":60,"wifi":true,"bt":true,"nq":2,"ver":"1.3.4"}
```

- The phone uses this to (a) confirm the link, (b) show a small health chip, (c) drive a "BLE is carrying the dashboard" indicator. `nq` = current bar notification-queue depth (0 = empty).

### 4.4 Security

- **No pairing / no encryption** in v1 (the device is a *sink*; there is nothing on the ESP worth protecting, and the phone-side BLE is already a trust boundary the user owns). The ESP's `STATUS` contains no secrets. If a future revision puts config on the wire, **re-evaluate** and add MITM-protected bonding *then* — do not add it speculatively.
- **Access control is "last client wins"**: a single peripheral accepts one central. Reconnect logic is on the phone (see §5.3).

---

## 5. Android — weather fetch + parse + BLE central (workstream A)

New package **`ble`** and **`weather`** under `com.alefinot.dashboardpp`. All files are new; nothing existing is rewritten, only *wired in* (the wiring list is in §5.4).

### 5.1 `ble/Protocol.kt` (object)

- Owns the **§4.1** constants (service UUID, characteristic UUIDs, advertise name) and the **§4.2** frame codec.
- `fun encode(type: Byte, json: String): ByteArray` — header + JSON.
- `fun parseFrame(bytes: ByteArray, offset: Int, cb: (type: Byte, json: String) -> Unit): Int` — returns bytes consumed (0 if incomplete). **This is the single parser shared by any future inbound path**; today only the ESP uses it, but keep it one function.
- JSON (de)serialization: the app already has a JSON library (the Web shell / DTOs); use the same one. **Do not** add a second JSON lib.

### 5.2 `weather/OpenMeteoClient.kt`

- `suspend fun fetch(lat: Double, lon: Double): WeatherDto` — an **OkHttp** GET to the **exact** URL/fields in §2.1 (same `current`/`daily`/`timezone`/`forecast_days`), parse to `WeatherDto` (the app already has a `weather` DTO shape — reuse it).
- There is no second weather client in the app — this client **is** the only fetch path. One fetch, one sink: the BLE push to `g_weatherData`.
- **No API key in v1**: Open-Meteo's free tier needs no key for the base forecast. If a key is added later, it's a config value on the phone (DataStore), not on the ESP.

### 5.3 `ble/BleLink.kt`

- **Central** manager. Lifecycle:
  1. `startScan()` — `ScanFilter` on the §4.1 service UUID; prefer the `Dashboard++` name; on match, **stop scanning** and proceed.
  2. `connect(device)` — `BluetoothGatt`, request MTU, `discoverServices`, find the service by UUID, find `RX`/`TX` characteristics.
  3. `subscribeTx()` — enable notifications on `TX` (the `STATUS` stream).
  4. `sendFrame(frame: ByteArray)` — write to `RX` (write-without-response). Reassemble from the phone side is not needed (phone is the writer); the ESP reassembles (§4.2).
- **Reconnect**: on `onConnectionStateChange(DISCONNECTED)`, back off and re-scan (the ESP re-advertises). Keep a single `Coroutine`/`Job` for the connect loop; do not spawn unbounded retries (the firmware's own weather code has a "2 s backoff so it can't spin" comment — mirror that discipline).
- **Thread/queue**: BLE callbacks arrive on a binder thread; marshal to a `Channel`/coroutine. Do **not** touch `g_`-style ESP state from here (there is no shared state; the phone has none).

### 5.4 Wiring (what existing files gain, nothing changes semantically)

- **`AndroidManifest.xml`**: add the Bluetooth runtime permissions (§10) — but the *service/permission list* lives in §10; here we only note the app now *uses* BLE.
- **`MainActivity.kt`**: nothing (it stays a shell). The BLE + weather + notification work is driven by ViewModels / Services, not the Activity.
- **`DashboardRoot.kt` / a new `CompanionViewModel`**: a small **`CompanionViewModel`** (new) owns `BleLink`, the weather timer, and the `NotifOutbox`; it exposes `LinkUiState` (idle/scanning/connected/failed) + the latest `WeatherDto` to the UI. The existing `ConnectionViewModel` keeps owning the **Wi-Fi/Web** discovery and `ConnectionUiState`. Do not merge the two — they are two links to the same device and their lifecycles are independent.
- **UI**: a compact **link chip / row** in the existing HUD (the `DashboardWebScreen` settings area) showing `BLE: linked / scanning / —`, a "push weather now" action, and the §6 notification toggle. No new full screen in v1 — the HUD already has the chrome.

### 5.5 Weather scheduling on the phone

- On connect: fetch once. Then fetch every `WEATHER_REFRESH_MIN` (read from the ESP's `/api/config`, or a phone-local default — the ESP's `WEATHER_REFRESH_MIN` is now the source of truth for the *phone's* interval, so the phone mirrors it).
- On fetch success: `BleLink.sendFrame(encode(WEATHER, json))`. **This is the entire "ESP is a display" contract** — one write.
- If BLE is not linked, the phone does **not** fetch (there is no sink) — the weather card simply holds the last-pushed value.

---

## 6. Android — notification listener + parser + outbox (workstream B)

New package **`notify`**. This is the part that is *gated on a user permission*, so it has its own onboarding.

### 6.1 `notify/NotificationReceiver.kt` (`: NotificationListenerService`)

- Declared in the manifest with the `BIND_NOTIFICATION_LISTENER_SERVICE` permission (§10). The user grants **"Display notification history"** (the system "Notification access" screen) once.
- Override `onNotificationPosted(pn)` (and seed from `getActiveNotifications()` on bind) → hand each to `NotifOutbox.enqueue(pn)` (the outbox classifies **media** vs. **regular** — §6.3).
- **Do not** call `cancelNotification` / `delete` — the app is a *relay*, not a manager. Never touch the notification's lifecycle.

### 6.2 `notify/NotificationParser.kt` (the **regular** / non-media notification parser)

- Pure function `fun parse(pn: StatusBarNotification): NtfDto`.
- Extract, defensively (every field is nullable / may be empty):
  - `app = pn.packageName` (the package — drives the ESP monogram).
  - `title = pn.notification.extras.getCharSequence(Notification.EXTRA_TITLE, null) ?: ""` — fall back to `TITLE_BIG`.
  - `body = TEXT` then `TEXT_BIG` (the longer wins).
  - `icon = a short token` — first letter of the app label, or a category (`"msg"/"call"/"mail"` by package allow-list). **Not** a bitmap (see §4.3 note).
  - `epoch = pn.postTime`.
- **De-dupe**: keep a `Map<key, lastSentMs>` (key = package+title+body hash) and drop re-posts within a small window (notifications re-post on lock/unlock). This is the single biggest source of BLE spam, so the dedupe is **required**, not optional.

### 6.2b `notify/MediaParser.kt` (the current-song parser)

- `fun isMedia(pn): Boolean` — true when `pn.notification.category == Notification.CATEGORY_MEDIA` (or the extras carry `EXTRA_MEDIA_KEY` / `EXTRA_DURATION`).
- `fun parse(pn): MediaDto` — extract `artist` (`EXTRA_TITLE` / `EXTRA_TEXT`), `song` (`EXTRA_TEXT` / the media text), `icon` (a short token — a music-note glyph `"mus"`, or the album-art first-letter; **not** a bitmap, §4.3).
- **Persistent**: a `MEDIA` frame is sent whenever the song / artist changes (the phone dedupes on the song+artist hash). It is the bar's **default** content (the persistent now-playing state); a `NOTIFICATION` frame temporarily overrides the bar (7 s when the song is playing; else it stays).

### 6.3 `notify/NotifOutbox.kt`

- `fun enqueue(pn: StatusBarNotification)` — first **classify** (`MediaParser.isMedia(pn)`: `Notification.CATEGORY_MEDIA` / the media extras). If **media** (the current song), parse with `MediaParser.parse(pn)` and `BleLink.sendFrame(encode(MEDIA, json))` (persistent). Else, parse with `NotificationParser.parse(pn)` (dedupe), then `BleLink.sendFrame(encode(NOTIFICATION, json))` (a temporary bar override on the ESP — 7 s when the song is playing; else it stays). **Buffer** (bounded, e.g. 8) when not linked so a reconnect flushes a short backlog (drop oldest on overflow).
- This is the only place that talks to BLE for notifications **and** the song; `NotificationReceiver` never touches `BleLink` directly.

### 6.4 Onboarding

- First launch (or first BLE link) → a one-line card: *"Grant notification access to relay alerts to your dashboard"* + a button that `startActivity(Intent(Settings.ACTION_NOTIFICATION_ACCESS_SETTINGS))`. Track the granted state with `Settings.Secure.getString(..., "enabled_notification_listeners")` so the card can verify, not just assume.

### 6.5 `nav/MapsNavParser.kt` (step 2 — the Google Maps nav parser)

Reuses the §6 listener; adds **no** manifest / permission / service (the §6 `NotificationListenerService` is already declared). A new package **`nav`**.

- **`MapsNavParser.kt`** — a pure function `fun parseMapsNav(pn: StatusBarNotification): NavDto?`:
  - Gate: `pn.packageName == "com.google.android.apps.maps"`. Otherwise **return null** (the general §6.2 parser handles the rest; nav is a *separate* consumer of the same listener).
  - **Classify** the notification as a *nav* notification: while navigating, Google Maps runs a **persistent** notification whose text is the live instruction (e.g. "In 300 m, turn right", "In 1.2 km, bear right onto Avenue Foch", "In 400 m, arrive at destination", "Arrived at destination"). Detect it by the text shape (a distance + a maneuver verb) **and** the package — not by a stable ID (Google Maps does not guarantee one).
  - **Parse** the text into the §4.3 `NAV` fields:
    - `on = true`.
    - `act` = the **arrow** from the maneuver verb: "turn right"→`right`, "turn left"→`left`, "bear left"/"bear right"→`left`/`right`, "go straight"/"continue"/"keep"→`straight`, "U-turn"→`uturn`, "merge"/"on to"→`merge`, "arrive at"/"arrival"→`arrive`.
    - `d` + `u` = the leading distance ("In **300 m**" → `d=300,u="m"`; "In **1.2 km**" → `d=1.2,u="km"`).
    - `road` = the trailing street name (the "onto Avenue Foch" / "on Avenue Foch" tail; empty if none).
    - `arr` = true iff the text is the arrival ("Arrived at destination"); in that case `act="arrive"`.
    - `eta` = an optional trailing ETA if present ("in 12 min"); else empty.
  - **De-dupe** on the **content hash** (package + text + postTime-minute) — a re-post of the same text (lock/unlock, a Maps refresh) must not double-push. Keep a `Map<hash, lastSentMs>` like §6.2.
- **Stop / cancel**: override **`onNotificationCancelled(pn, replace)`** on the §6 `NotificationReceiver`: when the *Maps nav* notification is cancelled (navigation stopped, or the "Arrived at destination" is dismissed), send **`NAV{on:false}`**. This is the **stop** signal that hides the §7.4 widget. (Arrival is handled by the parser first — `act="arrive"` — then the cancel sends `on:false`.)
- **Outbox**: a small `NavOutbox` (or a second bounded slot on the existing §6.3 outbox) that, when linked, `BleLink.sendFrame(encode(NAV, json))`; when not linked, keep the *last* nav state (depth 1 — the newest only) so a reconnect re-seeds the widget. **No** backlog (nav is live, not a queue of history).
- **No** nav of its own: this parser does **not** start/stop navigation, does **not** read GPS, and does **not** call any Google Maps SDK. It only *reads* the notification text. (C2: one BLE link; this is a second payload on it, like NOTIFICATION.)

---

## 7. ESP32 — GATT server, weather ingest, notification bar (workstream C)

New files in `src/`: **`ble.cpp` / `ble.h`** (GATT server + ingest) and **`bar.cpp` / `bar.h`** (the top notification bar: song state + alert queue + render pass). The `bleTask` is the only new task (the bar render is a pass in the display task, §7.3). `web.cpp` *loses* code (the §2.1 fetch path is removed, §7.2); `ui.cpp` gains nothing (the widget already reads `g_weatherData`).

### 7.1 `ble.cpp` — GATT server (new task, core 0, pinned)

- A **`bleTask`** (FreeRTOS task, **pinned to core 0**, low priority, **below** the Wi-Fi/STA and the `gpsTask`/display tasks — same isolation philosophy as the existing pinned tasks). It owns:
  - NimBLE-Arduino `BLE291Request`/server: advertise the §4.1 service (16-bit), `RX` (write) + `TX` (notify+read) characteristics, the §4.3 `STATUS` payload.
  - The **§4.2 frame reassembly** over the `RX` write callback (bytes → ring → dispatch on `type`).
- **Why core 0 / pinned**: the firmware's existing pinned tasks (`gpsTask` core 0, the display task) are the pattern for "this runs regardless of load." BLE callbacks are latency-sensitive (a dropped frame is a dropped weather push) and must not be starved by the display or Wi-Fi stacks. Pinning it to core 0, below the display, keeps the GATT callbacks off the hot pixel path.
- **Ingest dispatch** (the only thing the GATT write callback may do): push into the two queues below; **no** parsing of *display* state, **no** `display` access here — the render happens in the display task via the queues (§7.2, §7.3). This keeps the GATT callback short and safe.

### 7.2 `ble.cpp` — weather ingest → `g_weatherData`

On a `WEATHER` frame, parse the §4.3 JSON and, **under `g_stateMutex`**, write `g_weatherData` (same fields, same units — km/h in, `/3.6` left to the widget), set `valid=true`, `lastUpdated=millis()`.

- **The ESP's Wi-Fi weather fetch is removed** (not gated, not a fallback). Concretely:
  - Delete `updateWeather()` and `startWeatherFetch()` from `web.cpp` / `dashboard.h` (the Open-Meteo HTTP fetch + its URL/field construction go away).
  - Delete the fetch machinery: `weatherTask` / `weatherTaskRunning` / `weatherTaskHandle` / `weatherTaskStartedMs` / `weatherFetchMutex` / `weatherRefreshRequested`.
  - Delete the periodic fetch block in the `webServerTask` loop (the `lastWeatherCheck` / `WEATHER_REFRESH_MIN` block, the 30 s hung-task guard, and the `weatherRefreshRequested` handling) and the `startWeatherFetch()` call inside the STA-connect block.
  - Keep: `g_weatherData` (now written **only** by the BLE ingest), the `WEATHER_*` NVS params (they now parameterize the *phone's* fetch — the phone reads them from `/api/config`), and the widget in `ui.cpp`.
- **No fallback exists, by design**: with no BLE link (or the phone's internet down) the widget shows its last-received value, then "no data" as `lastUpdated` goes stale. This is the accepted trade-off of making the phone the sole weather source — do **not** re-add a Wi-Fi fetch to "fix" it.
- **The widget is untouched.** `drawWeatherWidget` already reads `g_weatherData` and dirties on change. No `ui.cpp` change for weather.

### 7.3 `bar.cpp` — the top notification bar (song + alerts)

**State** (owned by `bar.cpp`, consumed by the display task):
- **Song** (the persistent now-playing state): `songActive` (bool), `songArtist` (string), `songTitle` (string), `songIcon` (token) — set by a `MEDIA` frame.
- **Alerts** (a bounded FIFO, e.g. **depth 8**, drop-oldest on overflow): each entry `{app, title, body, epoch, iconToken, enterMs}` — set by a `NOTIFICATION` frame.
- `bar.cpp` exposes **`barPush(NtfDto)`** (a `NOTIFICATION` frame) and **`barSetSong(MediaDto)`** (a `MEDIA` frame) — called from the `ble.cpp` ingest, under a small mutex — and **`barTick(nowMs)`** / **`barDraw(display)`** (called from the frame pipeline).

**Render contract:**

The bar is a **pass** `drawNotificationBar(display)` called from `loop()` in `main.cpp`, **after** `updateBigDisplay(snap)` and **before** `drawFpsOverlay()` — the same slot the `drawFpsOverlay`/`drawGpsDebugOverlay` pair occupies (and the §7.4 nav widget's slot). It is **not** a new screen mode; it is a *pass* over the existing dirty frame.

- **Region** `R_bar = (0, 0, 480, 32)` — a thin band at the very top. The topmost dashboard content is the date/time row at `y≈49–75` (the time at `x` offset +107, the date at `x` offset −131, both baseline `y=69`), so the free top band is `y=0–~45`; the bar lives in `y=0–32` and **never** covers the dashboard. (Contrast the old shade's `R=(0,0,480,180)`, which covered the dashboard and needed a reveal.)
- **Content logic** (per frame, from the state; the bar shows **one** item):
  - If an **alert is active**:
    - **If `songActive`** (music is playing): the alert takes over for `BAR_TIMEOUT_MS` (default 7000 ms), then returns to the song.
    - **If `!songActive`** (no music): the alert **stays** (no timeout) — the bar keeps showing the **newest** alert until a new alert arrives, or the song starts.
  - Else, if **`songActive`**: show the **song** (icon left + `artist`/`song`, 1 line).
  - Else: the bar is **empty** (a 1 px dim line, or hidden).
- **The 7 s timeout is conditional**: it fires **only when the song is playing** (the bar has the song to return to). When **no song** is playing, an alert **stays visible** (no timeout) — it remains on the bar until a new alert arrives, or the song starts (a `MEDIA` frame → `songActive`). (Not a slide — the bar just swaps content; no animation state machine.)
- **The icon** (left, ~16 px): for an alert, a **filled circle** (the app's accent color, or a **monogram** = the app's first letter); for the song, a **music-note glyph** (drawn with `drawLine`/`drawTriangle`) or a small circle. *Not* the real app icon / album art (they cannot cross BLE cheaply; §4.3).
- **The text** (right of the icon, 1 line, ellipsized): the alert's `title` (or `app` + `title`), or the song's `artist`/`song`.
- **RAM-cheap, in the dirty pipeline**: draw **in place** (a small partial buffer / `startWrite`), **not** a full-screen sprite. Only redraw when the bar content changes (a new `MEDIA` / `NOTIFICATION` frame, or the 7 s timeout fires) — event-driven, not every frame.
- **No reveal problem** (unlike the old shade): `R_bar` is free space (y=0–32); the dashboard (y≥49) is never covered, so no forced dashboard redraw is needed. The bar just redraws its own band on a content change.
- **Fonts**: reuse the loaded LovyanGFX fonts. **No** new font asset.

**Integration (the calls):**
- `loop()` in `main.cpp`: after `updateBigDisplay(snap)`, add `drawNotificationBar(display);` (the same slot as the §7.4 nav widget — they do not overlap: the bar is at y=0–32, the nav box at y=124–176).
- The **display task** owns `barTick(nowMs)` (expire an alert whose `BAR_TIMEOUT_MS` elapsed) — called from the frame context, **not** from the BLE task.

**`g_stateMutex` discipline**: the bar state is guarded by its own small mutex (owned by `bar.cpp`); the weather ingest uses `g_stateMutex` (already the contract). Do **not** reach into `display` or `g_weatherData` from the `bleTask` — only via the state.

### 7.4 `nav.cpp` — the nav widget (left of the speed; step 2)

New file **`nav.cpp` / `nav.h`** (state + ingest + a draw pass; **no** new task). Consumes the §4.3 `NAV` frame; owns the show/hide + the draw.

**State** (guarded by its own small mutex, owned by `nav.cpp`):
- `navOn` (bool), `navAct` (the `act` token), `navD` (number), `navU` (`"m"`/`"km"`), `navRoad` (string, truncated to ~16 chars), `navEta` (string), `navArr` (bool). A `navChanged` dirty flag set by the ingest (like the bar's, like `g_weatherData`).

**Ingest** (from the `ble.cpp` GATT dispatch, **no** display access): on a `NAV` frame, parse the JSON and, under the `nav` mutex, store the fields + set `navChanged`. `on=false` sets `navOn=false` (and sets the dirty so the hide is drawn once).

**Show/hide (the hard part, read before coding):**
- The widget is drawn **only while `navOn`**. When a `NAV` frame flips `navOn` true→false (nav stopped / arrived), the widget **hides**: clear its region and **force a one-frame redraw of that region** (do not leave a stale frame — the same discipline the §7.3 bar applies to its own band). When it goes false→true, it **appears** (a one-frame entrance, optional).
- **It is a pass over the existing frame**, not a new screen mode: `drawNavWidget(display)` is called from `loop()` in `main.cpp`, in the **same slot** as the §7.3 bar — after `updateBigDisplay(snap)`, before `drawFpsOverlay()`. It is a *second* post-`updateBigDisplay` overlay (the §7.3 bar is at y=0–32; the nav widget is a small left-of-speed box at y=124–176 — they do not overlap).

**Region (left of the speed):**
- The large speed number is centered at `x≈240` (`BIG_CENTER_X + OFFSET_BIG_SPEED_NUM_X`), `y≈157` (`BIG_CENTER_Y + OFFSET_BIG_SPEED_NUM_Y`). **"Left of the speed"** = a small box in the left band of the speed row: **`R_nav = (8, 124, 112, 52)`** (x 8..120, y 124..176) — to the *left* of the number, at the same vertical band. It never overlaps the speed number (x≥130) or the lower dashboard band. (The §7.3 bar is at y=0–32; the nav box at y 124–176 does not overlap it — they coexist.)

**Render (RAM-cheap, in the dirty pipeline):**
- Draw **in place** (a small partial buffer / `startWrite`), **not** a full-screen sprite:
  - **Arrow glyph** (drawn with `drawLine`/`drawTriangle` — **no** font, **no** icon bitmap): `right` = a right-pointing arrow (→, a horizontal line + arrowhead), `left` = its mirror (←), `straight` = up (↑), `uturn` = a 180° hook (a curve + arrowhead), `merge` = a right-curving arrow, `arrive` = a small flag / dot. Drawn at the top-left of `R_nav`, ~24 px.
  - **Distance** (below / right of the arrow): format `navD`+`navU` (`300 m`, `1.2 km`) — one line, ~10 px font.
  - **Road** (one line, truncated, ellipsized): `navRoad`.
  - **ETA** (optional, dim): `navEta`.
  - **Arrival**: if `navArr`, a full-width "Arrived" read (the arrow is `arrive`).
- **Redraw is event-driven**: the widget redraws only when `navChanged` is set (a new `NAV` frame) — it does **not** redraw every frame. Between updates it is static (the dirty pipeline skips it, like the speed sprite).
- **Fonts**: reuse the loaded LovyanGFX fonts (the widget text path). **No** new font asset, **no** new sprite buffer.

**`g_stateMutex` discipline**: the nav state is guarded by its own small mutex (owned by `nav.cpp`); do **not** reach into `display` from the `bleTask` — only via the state. The render is in the display task (the frame pipeline).

---

## 8. NVS / Web UI / config surface

New params, following the **exact** `config.cpp` pattern (`CFG_BOOL/CFG_INT/CFG_STR/CFG_FLT` in `processConfig()`, persisted in NVS, surfaced in `webui.html`). Each gets a **Web UI card** (the Web UI already has a weather card; add a **Bluetooth / Phone** card next to it).

| Param | Type | Default | Meaning |
|---|---|---|---|
| `BLE_ENABLED` | bool | `1` | Master: when 0 the ESP does not advertise / does not start `bleTask`; the phone shows "BLE off". |
| `BAR_ENABLED` | bool | `1` | Master for the bar (0 = swallow NOTIFICATION / MEDIA frames, queue but don't render). |
| `BAR_TIMEOUT_MS` | int | `7000` | How long a notification overrides the bar before it returns to the song. |
| `BAR_SHOW_APP` | bool | `1` | Show the icon/monogram circle on the left (0 = text-only). |

**The `processConfig()` change** is the same shape as the existing `WEATHER_*` block: add the four keys to the `cfg` JSON you build (GET) and to the parser (POST), persist via the existing `CFG_*` macros, and clamp `BAR_TIMEOUT_MS` to sane bounds (e.g. `BAR_TIMEOUT_MS ∈ [1000, 30000]`). **Do not** add a new persistence path — reuse the one `config.cpp` already has.

**Existing `WEATHER_*` params are kept, their meaning changes**: `WEATHER_REFRESH_MIN` now parameterizes the *phone's* fetch interval (the phone reads it from `/api/config`; the ESP no longer reads it), and `WEATHER_LAT/LON/CITY/LOCALE` are the *phone's* fetch location (the phone reads them from `/api/config` and fetches there). No new location params — the ESP's existing NVS values are the location of record.

**Web UI (`webui.html`)**: one **Phone / Bluetooth** card with: the `BLE_ENABLED` switch and the `BAR_*` controls, read/write the same way the weather card is (POST `/api/config`). No new endpoint. The existing weather card is **kept** — its lat/lon/city/refresh fields now parameterize the *phone's* fetch (no label change required beyond a one-line note that they are "used by the phone").

> The BLE channel does **not** carry config. Config stays on HTTP `/api/config` (it has to work when the BLE phone is away, e.g. from the browser). BLE is data only.

**Step-2 nav needs no new param.** The §7.4 widget is driven entirely by the `NAV` frame's `on` flag (show/hide) — there is no NVS toggle for it, and nothing to surface in `webui.html`. If a "nav off" master is ever wanted, it is a later, optional param — not v1.

---

## 9. Memory & coexistence risk (the real constraint on WROOM-32)

This is where the plan can die, so it is called out explicitly.

- **RAM**: WROOM-32 has 520 KB SRAM; the dashboard's steady-state free heap is **~130 KB** *with Wi-Fi up* (measured on the current build, before any of this plan's changes). BLE (NimBLE-Arduino) is a **one-time** addition — a static (linked) allocation for the BT host + controller, plus a smaller dynamic (heap) slice — so the real question is not 'does the dashboard fit' (it does, comfortably) but 'does **BLE + Wi-Fi coexistence** fit on 520 KB, and how much free heap is left.' That is **measured at P0** (§12.1), not assumed. **Net**: with ~130 KB to start, step 1 *and* step 2 are **comfortable, not tight**; the SoftAP-drop fallback below is a documented, *likely-unneeded* safety net. (The bar is kept a *thin* top band (y=0–32) and the BLE task pinned/lean — cheap insurance, now low-risk.)
- **Coexistence**: ESP32 supports BLE + Wi-Fi concurrent (the ESP-IDF ships a `bleprph_wifi_coex` example for exactly this). It is *supported*, not exotic. The cost is RAM and a little flash. The device already runs Wi-Fi STA + SoftAP + HTTP + mDNS + OTA pull; adding a BLE GATT server on top is the **most** RAM this device will ever hold, so:
  - **Budget check (do this first, before anything else)**: with `BLE_ENABLED=1` and Wi-Fi STA connected, confirm free heap stays **≥ ~20 KB** in steady state (the device's low-heap watchdog arms at 30 KB / reboots below — do not run *below* that). Given the ~130 KB start, this is expected to be **green with margin**; the fallback below applies only if it is not. **Baseline confirmed**: the ~130 KB is the steady state with **all current features active + Wi-Fi up** (no BLE yet) — the coexistence case, the one that matters. The only unknown is the **BLE delta** itself, which P0 measures.
  - **Flash**: NimBLE-Arduino adds a few hundred KB of flash (BT host + controller). 4 MB flash has headroom; the `platformio.ini` `lib_deps` line is the only change (§10).
- **Flash / build**: `lib_deps` += `h2zero/NimBLE-Arduino@^2.x` (the library the rest of the ESP32-BLE community uses; verify the exact version pin against the ESP32 Arduino core the `platformio.ini` targets). This is a **build-time** dependency, not a runtime one.

**Decision the build order forces**: implement BLE *first* as a bring-up (a PING-only link), measure free heap, and **only then** layer the two payloads. Do not build the bar on top of an unmeasured BLE baseline.

**Step-2 (nav) is heap-gated, not assumed.** The §7.4 nav widget is drawn *in the existing dirty pipeline* (a few lines + text, **no** new full-screen buffer / sprite — same RAM discipline as the §7.3 bar), so its ESP heap cost is ~0 *beyond* the already-counted BLE host. It is therefore **only built if, after step 1 (BLE + weather + bar) is running, the §9 steady-state free heap is still ≥ ~20 KB.** If it is not, step 2 is **deferred** (the phone simply does not send `NAV` frames / the nav card is hidden) — it is a feature the heap *may* allow, not one it must.

---

## 10. Manifest / build wiring

### 10.1 `platformio.ini` (ESP32)

- **`lib_deps`** += `h2zero/NimBLE-Arduino` (pinned to a version compatible with the ESP32 Arduino core already in use — verify against the core pin before shipping).
- No `board_build` change; the part is already `ESP32-WROOM-32` (4 MB / 520 KB) per the README.

### 10.2 `AndroidManifest.xml` (additions)

**Permissions** (runtime, Android 12+ where noted):
- `BLUETOOTH`, `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` (the BLE central needs these; `BLUETOOTH_SCAN` is a runtime permission on API 31+).
- `NEARBY_WIFI_DEVICES` is **not** needed (we do not do Wi-Fi D2D here) — skip it.
- (No new manifest *service* beyond the listener below.)

**Components**:
- A **`<service>`** for `notify/NotificationReceiver` with
  ```xml
  <intent-filter><action android:name="android.service.notification.NotificationListenerService" /></intent-filter>
  <permission android:name="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" />
  ```
  and the application-level `<uses-permission>` for `BIND_NOTIFICATION_LISTENER_SERVICE`.
- **No** new `<activity>` (the BLE + notification work is ViewModel/Service-driven; the HUD chip is in the existing screen).

### 10.3 `build.gradle.kts` (Android)

- **No new dependency** for BLE (the `android.bluetooth` API is in the platform). **No** new dependency for the listener (it is a framework class). If the JSON lib is not yet a dep, it already is (the Web shell uses it) — reuse it.
- **Step-2 nav adds nothing here** (§6.5 reuses the §6 `NotificationListenerService` + the §5 BLE link; the nav parser is plain Kotlin + the existing JSON lib). No new permission, no new service, no new activity.

---

## 11. Edge cases & robustness

- **No phone / BLE off** → no new weather data: the widget holds its last-received value, then "no data" as `lastUpdated` goes stale; no bar (`BAR_ENABLED` or no feed). Config / OTA / Web UI are unchanged (they are HTTP, and remain so). **This is the default-safe state.**
- **BLE linked, Wi-Fi down on the ESP** → BLE keeps working (BLE does not need Wi-Fi). Weather/notifications still flow; only config/OTA are blocked (they are HTTP). The ESP's Web UI is unreachable, but the *data* path is independent — that is the point of C1.
- **Phone Wi-Fi down (airplane mode), BLE up** → BLE still up (BLE is independent of the phone's Wi-Fi). Notifications still flow. Weather does **not**: the phone has no internet to fetch, and the ESP has no fetch path at all (§7.2) → the widget holds its last-received value / "no data". **Expected and correct** — weather now has exactly one source (the phone's internet), and that is the phone. Do not paper over it.
- **MTU / chunking**: the §4.2 length-prefix makes partial writes harmless. A disconnect mid-frame drops the partial; the next frame (or a reconnect) re-sends. **Idempotency**: a `WEATHER` frame is idempotent (the ESP just overwrites `g_weatherData`); a `NOTIFICATION` re-send within the dedupe window is dropped (§6.2). So retransmitting is safe.
- **Notification spam / lock-screen re-posts**: dedupe (§6.2) is **required**; the bounded outbox (§6.3) caps backlog.
- **ESP heap collapse with BLE up**: the §9 budget + the optional "drop SoftAP when BLE linked" fallback. The low-heap watchdog already reboots below its threshold; do not lower that threshold to make room for BLE.
- **Two phones**: a single peripheral takes one client. Last-writer-wins is fine; do **not** add multi-connection in v1.
- **Stale `g_weatherData` after BLE drops**: `lastUpdated` goes stale → the widget's "no data" path (it already keys off `valid`/`lastUpdated`). The bar queue drains to empty (`nq=0` in `STATUS`).
- **Bar: no song + no alert** → the bar is empty (a 1 px dim line, or hidden). No dashboard impact.
- **Bar: alert during a song** → the bar shows the alert for `BAR_TIMEOUT_MS` (7 s), then returns to the song (the persistent default); the alert's `enterMs` is set on arrival.
- **Bar: alert, no song** → the alert **stays** (no timeout) — the bar keeps showing it until a new alert arrives, or the song starts.
- **Nav: Google Maps not running / never started** → no nav notification exists → the phone sends no `NAV` frame (or a one-time `on=false`) → the §7.4 widget is **hidden** (absent). Default-safe.
- **Nav: navigation stops** → Google Maps **cancels** the persistent nav notification; the phone's `onNotificationCancelled(pn, replace)` fires for the Maps nav notification → the phone sends **`NAV{on:false}`** → the widget **hides** and the region is cleared. (This is the *stop* signal — not a timeout.)
- **Nav: arrival** → Google Maps posts "Arrived at destination" → `NAV{on:true, act:"arrive", arr:true}`; then the nav notification is cancelled → `on:false` hides it.
- **Nav: reroute** → Google Maps re-posts the nav notification with new text → the parser re-parses → a new `NAV` frame (the arrow/distance/road update). The phone de-dupes on the *content hash* (so a re-post of the same text is not a double frame).
- **Nav: units** → Google Maps shows meters or km depending on the phone's locale. The phone sends `d`+`u` as-is; the ESP formats (`300 m` / `1.2 km`) — it does **not** convert.
- **Nav: distance counts down** → the phone only pushes a new `NAV` frame when the parsed text changes (deduped); between updates the ESP keeps the last value. A BLE drop mid-nav just loses the next update (the last value stays; on reconnect the phone re-seeds the last nav state).

---

## 12. Build order & testing

### 12.1 Phases (order matters — do not reorder)

- **P0 — BLE bring-up (measure before building)**: a `ble.cpp` that **only** advertises + does a PING/ACK loop (no weather, no notifications). Confirm: free heap **≥ ~20 KB** with Wi-Fi STA up (the §9 gate); the phone can connect and read `STATUS`. **Ship nothing until this number is green.**
- **P1 — RX framing + WEATHER**: add the §4.2 reassembly + the `WEATHER` ingest → `g_weatherData`, and **remove** the ESP's Wi-Fi fetch (§7.2). The phone fetches and pushes; the widget shows phone-sourced data.
- **P2 — NOTIFICATION + MEDIA + bar**: add the `NOTIFICATION` + `MEDIA` ingest + the bar queue + `drawNotificationBar` (the song + alerts). The phone's listener pushes; the bar renders.
- **P2b — step 2: Google Maps nav (heap-gated)**: *only if the §9 gate is still green* (steady-state free heap **≥ ~20 KB** with BLE + Wi-Fi after P2). Add the §4.2 `0x05` framing + the §6.5 `MapsNavParser` (phone) + the §7.4 nav widget (ESP, left of the speed, show/hide on `on`). The phone parses the Google Maps nav notification and pushes; the ESP shows the arrow + distance + road.
- **P3 — Android glue**: the `CompanionViewModel`, the HUD chip, the notification onboarding card, the reconnect / dedupe. The `webui.html` **Phone/Bluetooth** card + the §8 params. (Step 2: the nav card is hidden when the §9 gate failed.)
- **P4 — harden**: the §11 cases, the §9 SoftAP fallback if needed, the Web UI card read/write round-trip.

### 12.2 Testing checklist

**Firmware / BLE**
- [ ] `BLE_ENABLED=0` → device boots, advertises nothing, no `bleTask`. (Regression: Wi-Fi/Web/OTA unchanged.)
- [ ] PING round-trip with nRF Connect (write a `PING` frame, see it echoed / `STATUS` updated).
- [ ] Free heap with BLE+Wi-Fi **≥ ~20 KB** (the §9 gate) — recorded.
- [ ] No self-fetch: with Wi-Fi STA connected and no BLE link, **no** Open-Meteo request leaves the device (tcpdump, or simply the absence of any weather task in the log); the widget shows no-data / last-received value.
- [ ] `grep -rn "updateWeather\|startWeatherFetch" src/` returns nothing — the fetch path is gone; the widget still renders BLE data.
- [ ] A `WEATHER` JSON lands in `g_weatherData` (verify the widget shows it; verify `wind` is still km/h in → m/s on the widget).
- [ ] A malformed / partial frame (truncated JSON, bad length) → dropped, no crash, no `g_weatherData` change.

**Android**
- [ ] BLE connect/disconnect/reconnect is stable across a screen off/on.
- [ ] `WEATHER` fetch → parse → push; the widget on the ESP updates within the refresh window.
- [ ] Notification onboarding: first-run card → grants → `onNotificationPosted` fires → a card appears on the ESP.
- [ ] Dedupe: lock the phone, a single notification does **not** produce N BLE frames.
- [ ] No new dependency in `build.gradle.kts` (the JSON lib is reused).

**ESP bar (the correctness one)**
- [ ] The bar shows the **song** (persistent) when no alert is active; the icon is on the left.
- [ ] An alert **takes over** the bar for `BAR_TIMEOUT_MS` (7 s), then the bar **returns to the song** (the persistent default). Verify the 7 s by a timer. (Only when the song is playing; when **no song**, the alert **stays** — verify it does not time out.)
- [ ] The bar is at y=0–32 and **never** covers the dashboard (the date/time row at y≈49–75 is untouched); the dashboard under the bar is always live.
- [ ] `BAR_ENABLED=0` → NOTIFICATION / MEDIA frames are consumed (queued) but **never** rendered; the bar is always empty.
- [ ] Font: 1 line, ellipsized; the icon is on the left (monogram / music note).

**Step 2 — nav (only if the §9 gate is green)**
- [ ] Free heap after step 1 (BLE + weather + bar) is **≥ ~20 KB** — recorded; only then is this block built.
- [ ] Google Maps nav active → a `NAV{on:true,...}` frame; the §7.4 widget appears **left of the speed** (arrow + distance + road).
- [ ] Stop the navigation (Google Maps cancels the nav notification) → `onNotificationCancelled` → `NAV{on:false}` → the widget **disappears** and the region is cleared.
- [ ] "Arrived at destination" → `act:"arrive"` / `arr:true` shown, then hidden on cancel.
- [ ] Reroute → new `NAV` frame (arrow/distance/road update); a re-post of the *same* text does **not** double-push (dedupe).
- [ ] Units: meters and km both format correctly (`300 m`, `1.2 km`); the ESP does not convert.
- [ ] No `NAV` frame ever changes `g_weatherData` or the bar queue (isolated ingest).

**Config / Web UI**
- [ ] The **Phone/Bluetooth** card round-trips through `/api/config` (GET reflects the POST).
- [ ] The existing weather card still round-trips (lat/lon/city/refresh now parameterize the phone's fetch); changing it and re-reading `/api/config` reflects the change.
- [ ] `processConfig()` clamps `BAR_*` to the §8 bounds.

**Build**
- [ ] `platformio` build is green with the new `lib_dep` (and the flash/RAM budget is within the 4 MB / 520 KB part).
- [ ] Android build is green with **no** new dependency and **no** new activity.

---

## 13. Out of scope (explicitly deferred, with reasons)

- **Multi-connection / pairing** — a single peripheral, no PIN (C4 / §4.4). Re-evaluate only if config moves onto the wire.
- **Real app icons over BLE** — a 480×320 device cannot pull icon bitmaps cheaply; the monogram circle is the v1 look (§4.3, §7.3).
- **A real-time gaussian blur / true frosted glass on the ESP** — not feasible at 480×320 / 60 fps; the §7.3 bar is a semi-transparent band + a 1 px highlight (the *approximated* look), not a blur.
- **Media / action controls on the bar (play/pause/skip from the ESP)** — the bar shows the song (read-only); it is a relay, not a control surface (§7.3); the user acts on the phone.
- **Carrying config over BLE** — config stays on HTTP `/api/config` (it must work when the phone is away).
- **Wi-Fi D2D / `NEARBY_WIFI_DEVICES`** — not needed; BLE is the channel.
- **ESP Wi-Fi weather fetch** — removed, not deferred: the phone is the only weather source (§7.2, §12.2).
- **Real nav icon bitmaps over BLE** (a Chronos-style `icon`/`iconCRC` monochrome bitmap) — the §7.4 widget draws an **arrow glyph** from the `act` token, not a bitmap; a 480×320 device cannot pull a per-maneuver icon cheaply. (The Chronos reference sends a bitmap; we intentionally do not.)
- **Route progress / ETA / duration / speed** beyond the arrow + distance + road — the `NAV` frame *carries* `eta` (and could carry `duration`/`speed`), but the v1 widget shows only the arrow, distance, and road. ETA/progress are later, optional.
- **Step 2 when the heap is not green** — if, after step 1, steady-state free heap is **< ~20 KB**, the nav widget is **not built** (the phone stops pushing `NAV` / the nav card is hidden). It is heap-gated, not deferred-for-later.
