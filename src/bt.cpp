// Bluetooth Low Energy link - the unit's only external interface.
//
// One GATT service with three characteristics (telemetry notify, RPC
// write+notify, OTA write), LE Secure Connections pairing with a passkey
// shown on the panel, a 1 Hz telemetry pump that pushes the same JSON the old
// /api/snapshot endpoint served, and the framing layer that carries RPC
// requests and responses (bt_rpc.cpp holds the methods themselves).
//
// Memory rules (AGENTS.md 14): the telemetry frame is one static buffer, the
// snapshot serializer is allocation-free, and the only heap the RPC path uses
// is the transient JSON document a config read or write needs.

#include "dashboard.h"
#include "bt_proto.h"
#include "heapdiag.h"

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEService.h>
#include <BLECharacteristic.h>
#include <BLE2902.h>
#include <BLESecurity.h>
#include <esp_gap_ble_api.h>
#include <esp_gatt_defs.h>

#include <stdarg.h>

// ----------------------------------------------------------------------------
// GATT profile
// ----------------------------------------------------------------------------
static const char *SVC_UUID   = "d9a1f000-6c1e-4b8a-9f2d-3e7c1a5b9d40";
static const char *TELEM_UUID = "d9a1f001-6c1e-4b8a-9f2d-3e7c1a5b9d40";
static const char *RPC_UUID   = "d9a1f002-6c1e-4b8a-9f2d-3e7c1a5b9d40";
static const char *OTA_UUID   = "d9a1f003-6c1e-4b8a-9f2d-3e7c1a5b9d40";

static BLEServer *s_server = nullptr;
static BLECharacteristic *s_telem = nullptr;
static BLECharacteristic *s_rpc = nullptr;
static BLECharacteristic *s_ota = nullptr;

static volatile bool s_connected = false;
static volatile bool s_advertising = false;
// A link that drops goes quiet for this long before pairing is offered again. The
// peer that just fell off often reconnects inside a second and takes the only peer
// slot, which is how swapping a unit between two phones turns into a search that
// finds nothing; a phone holding a link it never uses (an abandoned pairing) was
// worse still. 10 s is the bench-usable compromise: long enough for a deliberate
// hand-over, short enough that the next phone just waits for the card to appear.
static constexpr uint32_t BT_PAIR_DELAY_MS = 10000;
static volatile uint32_t s_advResumeAt = 0;
static volatile bool s_pairing = false;
// The name this unit advertises, kept after btInit() so the pairing panel can
// print the same string the phone's scan list is about to show.
static char s_adName[16] = "";
static volatile uint32_t s_passkey = 0;
static volatile unsigned long s_pairingSince = 0;

static TaskHandle_t s_btTask = nullptr;

volatile unsigned long btLoopCount = 0;

// ----------------------------------------------------------------------------
// Receive path (Bluedroid callback task) -> RPC task handoff
// ----------------------------------------------------------------------------
// One message at a time in each direction: the protocol is request/response, so
// a second in-flight request means the app is broken, not that we need a queue.
// `s_rxBusy` is held for the whole of btRpcHandle, which is what stops a new
// first fragment from overwriting a request being decoded.
static char s_rx[BT_MAX_MSG];
static volatile bool s_rxOpen = false;
static volatile bool s_rxReady = false;
static volatile bool s_rxBusy = false;
static volatile size_t s_rxLen = 0;
static volatile uint16_t s_rxSeq = 0;
static volatile bool s_rxBadLogged = false;  // one complaint per connection about junk
static int s_rpcNotifyLogged = 0;          // RPC notify attempts logged this link

// Largest single fragment this link can carry (MTU - ATT header - frame header).
// What the current link actually negotiated. Starts at the ATT default of 23 B
// and is replaced by the peer's MTU exchange (onMtuChanged); BLEDevice::getMTU()
// only reports what we asked for, and building a 517 B frame for a link that
// settled smaller loses it in the controller without any error reaching us.
static volatile uint16_t s_mtu = 23;

static size_t btMaxPayload() {
  uint16_t mtu = s_mtu;
  if (mtu < 23) mtu = 23;
  size_t n = (size_t)mtu - 3u - BT_FRAME_HDR;
  if (n > 512u) n = 512u;  // static frame buffer is header + 512
  return n;
}

// 1 = the phone asked for this stream on this link, 0 = it did not, -1 = the
// characteristic has no CCCD at all. The library consults exactly this before it
// will let a notification out.
static int cccdFlag(BLECharacteristic *c) {
  if (!c) return -1;
  BLE2902 *d = (BLE2902 *)c->getDescriptorByUUID((uint16_t)0x2902);
  return d ? (d->getNotifications() ? 1 : 0) : -1;
}

// Splits one message across as many notifications as the MTU needs. Paced: the
// controller's TX buffers are small, and back-to-back notifications on a
// 30-50 ms connection interval overflow them (the stack logs
// ESP_GATT_CONGESTED and drops the frame).
bool btSendMessage(uint8_t type, uint16_t seq, const char *data, size_t len) {
  BLECharacteristic *out = nullptr;
  switch (type) {
  case BT_F_RESP:
  case BT_F_PONG:
  case BT_F_LOG:
  case BT_F_CHUNK:
  case BT_F_OTA:
    // Install events ride the RPC characteristic too: it is the only one that
    // can notify, and the app parses them from the same subscription it gets
    // answers on (ble/BleLink.kt). The OTA characteristic below is write-only,
    // so it has nothing to answer on.
    out = s_rpc;
    break;
  default:
    out = s_telem;
    break;
  }
  if (out == nullptr || !s_connected) return false;

#ifdef BT_CFG_VERIFY
  // The bench driver in bt_rpc.cpp sees every message on its way out, before
  // fragmentation: a hand-built body that is not valid JSON shows up there
  // instead of as a client that stops responding on the other end of the link.
  btVerifyFrame(type, seq, data, len);
#endif

  static char frame[BT_FRAME_HDR + 512];
  const size_t chunk = btMaxPayload();
  size_t off = 0;
  int frames = 0;
  do {
    size_t n = len - off;
    if (n > chunk) n = chunk;
    bool first = (off == 0);
    bool last = (off + n >= len);
    frame[0] = (char)BT_PROTO_VERSION;
    frame[1] = (char)type;
    frame[2] = (char)((first ? BT_FLAG_FIRST : 0) | (last ? BT_FLAG_LAST : 0));
    frame[3] = 0;
    frame[4] = (char)(seq & 0xFF);
    frame[5] = (char)(seq >> 8);
    frame[6] = (char)(n & 0xFF);
    frame[7] = (char)(n >> 8);
    if (n) memcpy(frame + BT_FRAME_HDR, data + off, n);
    out->setValue((const uint8_t *)frame, BT_FRAME_HDR + n);
    out->notify();
    off += n;
    frames++;
    if (!last) vTaskDelay(pdMS_TO_TICKS(12));
  } while (off < len);
  // How big an RPC answer actually is, and what it cost to get over: a body that
  // outgrew its buffer is clamped in silence by btCatf, and a central missing the
  // LAST fragment waits out its own request timeout and looks like a dead link.
  if (type == BT_F_RESP)
    logPrintf("BT: RESP seq %u: %u B over %d frame(s), payload cap %u B\n", (unsigned)seq,
              (unsigned)len, frames, (unsigned)chunk);
  return true;
}

bool btLinkUp() { return s_connected; }

// What the current link actually negotiated, for the chunk size handed to a
// phone at ota.begin. BLEDevice::getMTU() reports what we ASKED for, which on a
// central that exchanged smaller would be an over-large write.
uint16_t btLinkMtu() { return s_mtu; }

// Feeds one ATT write into the reassembly buffer. Runs on the Bluedroid task,
// so it copies and returns - the JSON work happens on the BLE task.
static void btRxFragment(const uint8_t *data, size_t n) {
  if (n < BT_FRAME_HDR) return;
  if (data[0] != BT_PROTO_VERSION) {
    logPrintf("BT: dropped frame, protocol v%u\n", (unsigned)data[0]);
    return;
  }
  if (data[1] != BT_F_REQ) {
    // The unit never accepts other types. Saying so once per connection is the
    // difference between a two-minute debug and an evening: unframed JSON (the
    // header left off) otherwise disappears without a trace.
    if (!s_rxBadLogged) {
      s_rxBadLogged = true;
      logPrintf("BT: ignoring frame type %u (expected %u)\n", (unsigned)data[1], (unsigned)BT_F_REQ);
    }
    return;
  }
  const uint8_t flags = data[2];
  const uint16_t seq = (uint16_t)(data[4] | (data[5] << 8));
  const size_t len = (size_t)(data[6] | (data[7] << 8));
  if (BT_FRAME_HDR + len > n) return;

  if (s_rxBusy) {
    // A new FIRST while the previous request is still being handled is the one
    // case worth a log line: the app should never send two requests at once.
    if (flags & BT_FLAG_FIRST) logPrintf("BT: request %u dropped, link busy\n", seq);
    return;
  }

  if (flags & BT_FLAG_FIRST) {
    s_rxLen = 0;
    s_rxSeq = seq;
    s_rxOpen = true;
    s_rxReady = false;
  }
  if (!s_rxOpen || seq != s_rxSeq) return;
  if (s_rxLen + len > BT_MAX_MSG) {
    logPrintf("BT: request %u aborted, %u B exceeds the %u B buffer\n",
              seq, (unsigned)(s_rxLen + len), (unsigned)BT_MAX_MSG);
    s_rxOpen = false;
    return;
  }
  memcpy(s_rx + s_rxLen, data + BT_FRAME_HDR, len);
  s_rxLen += len;
  // Deepest point the reassembly buffer has ever reached (heap-recovery plan:
  // whether BT_MAX_MSG can ever shrink is a measurement, not a guess).
  btNoteRxUse(s_rxLen);
  if (flags & BT_FLAG_LAST) {
    s_rxOpen = false;
    s_rxReady = true;  // handed to btTask below
  }
}

// OTA state that used to live in web.cpp. The install itself is reimplemented on
// the OTA characteristic in src/bt_ota.cpp (phase 2b); this one global stays
// here because it is the display loop's reboot trigger, not install state.
volatile bool otaUpdateSuccess = false;

// ----------------------------------------------------------------------------
// Security
// ----------------------------------------------------------------------------
// How many bonds the stack has stored. This is the number that decides whether
// the next connect needs the six digits again, and it is the only Bluedroid
// reading that says so - the auth-complete event's own key fields describe the
// legacy link key and lie about LE Secure Connections.
static int btBondCount() {
  esp_ble_bond_dev_t list[4];
  int n = 4;  // capacity in, count out
  if (esp_ble_get_bond_device_list(&n, list) != ESP_OK) return -1;
  return n < 0 ? 0 : n;
}

static void btLogBondCount(const char *when) {
  int n = btBondCount();
  if (n < 0) logPrintf("BT: %s, bond list read failed\n", when);
  else logPrintf("BT: %s, %d bonded device(s)\n", when, n);
}

class BtSecurityCallbacks : public BLESecurityCallbacks {
  uint32_t onPassKeyRequest() override { return s_passkey; }

  void onPassKeyNotify(uint32_t pass_key) override {
    s_passkey = pass_key;
    s_pairing = true;
    s_pairingSince = millis();
    logPrintf("BT: pairing passkey %06lu - type it on the phone\n",
              (unsigned long)pass_key);
  }

  bool onSecurityRequest() override { return true; }

  bool onConfirmPIN(uint32_t) override { return true; }

  void onAuthenticationComplete(esp_ble_auth_cmpl_t cmpl) override {
    s_pairing = false;
    s_passkey = 0;
    // key_present is the legacy link-key field: with LE Secure Connections the
    // LTK travels a different way, so it reads false even when the bond landed.
    // The stored-device list is what actually answers "will the next connect
    // skip the code", so it is printed here instead.
    logPrintf("BT: auth %s (reason %d, key type %u, auth req 0x%02x)\n",
              cmpl.success ? "ok" : "FAILED", (int)cmpl.fail_reason,
              (unsigned)cmpl.key_type, (unsigned)cmpl.auth_mode);
    btLogBondCount("after auth");
  }
};

// RPC characteristic writes: framing only, the JSON is handled by btTask.
class BtRpcCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *, esp_ble_gatts_cb_param_t *param) override {
    if (param->write.is_prep) return;  // we never use prepared writes
    btRxFragment(param->write.value, param->write.len);
  }

  // Answers the question "the unit built the answer and it never arrived": every
  // notification this characteristic attempts is reported, up to four per link,
  // so the log says whether an answer left the unit at all and what the stack
  // thought of it. The library is otherwise silent about a refused send.
  void onStatus(BLECharacteristic *c, BLECharacteristicCallbacks::Status s, uint32_t code) override {
    if (s_rpcNotifyLogged >= 4) return;
    if (s == BLECharacteristicCallbacks::Status::SUCCESS_NOTIFY ||
        s == BLECharacteristicCallbacks::Status::SUCCESS_INDICATE) {
      if (s_rpcNotifyLogged++ != 0) return;  // successes after the first are quiet
      logPrintf("BT: RPC notify status legend (1=ok, 3=notify-disabled, 4=gatt-error, "
                "5=no-client, 6=no-subscriber)\n");
    } else {
      s_rpcNotifyLogged++;
    }
    logPrintf("BT: RPC notify #%d status %u rc %u CCCD %d handle 0x%04x\n", s_rpcNotifyLogged,
              (unsigned)s, (unsigned)code, cccdFlag(c), (unsigned)c->getHandle());
  }
};

// The OTA data characteristic: raw bytes, no framing - offset(4, little endian)
// then payload. This callback runs on the Bluedroid task, so it copies into
// bt_ota.cpp's ring and returns: flash writes, the CRC fold and the ACK events
// all happen on BtTask, the task that also answers RPC. Doing the write here
// would stall the GATT stack for the length of an erase-and-program, and the
// callback task's stack is not sized for the flash driver.
class BtOtaCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *, esp_ble_gatts_cb_param_t *param) override {
    if (param->write.is_prep) return;  // prepared writes are not part of this contract
    btOtaFeed(param->write.value, param->write.len);
  }
};

class BtServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *server, esp_ble_gatts_cb_param_t *param) override {
    s_connected = true;
    s_advertising = false;
    s_rpcNotifyLogged = 0;
    s_mtu = 23;  // reset: the negotiated value belongs to the link that just ended
    logPrintf("BT: phone connected (conn %u)\n", (unsigned)param->connect.conn_id);
    // Ask for the encrypted link immediately; the passkey prompt follows. This
    // is the peripheral-side request the stack exposes (there is no
    // esp_ble_gap_security_req in this IDF): MITM so SC pairing runs, and the
    // bond lets the next connection skip the code.
    esp_ble_set_encryption(param->connect.remote_bda, ESP_BLE_SEC_ENCRYPT_MITM);
  }

  void onMtuChanged(BLEServer *server, esp_ble_gatts_cb_param_t *param) override {
    // What the link can actually carry. Everything the unit sends is sized from
    // this, and a frame built for 517 B on a link that settled at 251 B simply
    // never arrives - silently.
    s_mtu = param->mtu.mtu;
    logPrintf("BT: MTU now %u (payload %u B per frame)\n", (unsigned)s_mtu,
              (unsigned)btMaxPayload());
  }

  void onDisconnect(BLEServer *server, esp_ble_gatts_cb_param_t *param) override {
    s_connected = false;
    s_pairing = false;
    s_passkey = 0;
    // Anything half-received belongs to the link that just died.
    s_rxOpen = false;
    s_rxReady = false;
    s_rxBadLogged = false;
    s_rxLen = 0;
    // Not "advertising again": pairing is held back for BT_PAIR_DELAY_MS, then
    // btTask brings it up. See the constant.
    BLEDevice::getAdvertising()->stop();
    s_advertising = false;
    s_advResumeAt = millis() + BT_PAIR_DELAY_MS;
    // The HCI reason is the clue when a client connects and vanishes: 0x3 is an
    // supervision timeout, 0x16 the client's own disconnect, 0x3F the local host.
    logPrintf("BT: phone disconnected (reason 0x%02x), pairing in %u ms\n",
              (unsigned)param->disconnect.reason, (unsigned)BT_PAIR_DELAY_MS);
  }
};

// ----------------------------------------------------------------------------
// Telemetry
// ----------------------------------------------------------------------------
// Allocation-free append with a running cursor: writes at most cap-1 bytes in
// total, so a long list can never walk off the end of the caller's buffer.
// Returns false when the text did not fit - the buffer still holds as much as
// did, so callers can close the JSON they were building and say so.
// Moved here from web.cpp with the snapshot serializer it serves, and shared
// with the RPC handlers in bt_rpc.cpp.
bool btCatf(char *buf, size_t cap, size_t *used, const char *fmt, ...) {
  if (*used >= cap - 1) return false;
  va_list args;
  va_start(args, fmt);
  int n = vsnprintf(buf + *used, cap - *used, fmt, args);
  va_end(args);
  if (n <= 0) return false;
  const bool fits = (size_t)n < cap - *used;  // vsnprintf had room for its NUL
  *used += (size_t)n;
  if (*used > cap - 1) *used = cap - 1;
  return fits;
}

// Same field names and ordering as the old GET /api/snapshot, so the app's
// Snapshot parser works unchanged. ~360 bytes worst case. Also answers the
// `snapshot` RPC method on demand.
size_t btBuildSnapshotJson(char *buf, size_t cap) {
  size_t used = 0;

  // Fallback values first: single 32-bit atoms, so reading them unlocked is
  // safe (no tearing, only a slightly older value). If the mutex take fails we
  // answer from these and never block the BLE pump.
  float spd = currentCachedSpeed, avg = averageSpeed, peak = maxSpeed;
  float ikml = instantKml, akml = averageKml, tripL = tripFuelConsumedLiters;
  float tripKm = (float)tripDistanceSnapshot();
  float fuelL = fuelLiters, volts = batteryVoltage, temp = engineTemperature;
  float tmrS = accelResultTime;
  int odoInt = 0, odoDec = 0;
  int fuelPct = fuelPercentage, src = heldSpeedSourceMode;
  int tmrSt = (int)accelState;
  double odo = odoGet();  // locked accessor: the double never tears
  if (odo < 0.0) odo = 0.0;
  if (odo > 16000000.0) odo = 16000000.0;
  double odoR = odo * 100.0 + 0.5;
  odoInt = (int)(odoR / 100.0);
  odoDec = (int)(odoR - (double)odoInt * 100.0);

  if (xSemaphoreTake(g_stateMutex, pdMS_TO_TICKS(10)) == pdTRUE) {
    // The guarded group: one tick's worth of values, so no cell disagrees with
    // its neighbour, and odo comes from the snapshot the display is drawing.
    spd = g_sensorData.currentSpeed;
    avg = g_sensorData.averageSpeed;
    peak = g_sensorData.maxSpeed;
    ikml = g_sensorData.instantKml;
    akml = g_sensorData.averageKml;
    tripL = tripFuelConsumedLiters;
    fuelL = g_sensorData.fuelLiters;
    volts = g_sensorData.batteryVoltage;
    temp = g_sensorData.engineTemperature;
    tmrS = g_sensorData.accelResultTime;
    fuelPct = g_sensorData.fuelPercentage;
    src = g_sensorData.speedSourceMode;
    tmrSt = (int)g_sensorData.accelState;
    odo = g_sensorData.totalDistanceKm;
    xSemaphoreGive(g_stateMutex);
    if (odo < 0.0) odo = 0.0;
    if (odo > 16000000.0) odo = 16000000.0;
    odoR = odo * 100.0 + 0.5;
    odoInt = (int)(odoR / 100.0);
    odoDec = (int)(odoR - (double)odoInt * 100.0);
  }

  // GNSS state comes only from the published snapshot - gps.* belongs to gpsTask
  // and its value()/kmph()/hdop() calls consume a one-shot update flag (issue #9).
  GpsFixSnapshot fix;
  bool haveFix = gpsSnapshotCopy(fix) && fix.valid;

  btCatf(buf, cap, &used,
         "{\"ver\":\"%s\",\"up\":%lu,\"spd\":%.1f,\"src\":%d,\"avg\":%.1f,"
         "\"max\":%.1f,\"trp_km\":%.2f,\"trp_l\":%.2f,\"ikml\":%.1f,"
         "\"akml\":%.1f,\"odo\":%d.%02d,\"fuel_l\":%.2f,\"fuel_pct\":%d,"
         "\"fuel_st\":%d,\"volts\":%.2f,\"temp\":%.1f,\"amb\":%d,\"sats\":%d,\"hdop\":",
         FW_VERSION, (unsigned long)(millis() / 1000UL), spd, src, avg, peak,
         tripKm, tripL, ikml, akml, odoInt, odoDec, fuelL, fuelPct,
         (int)fuelInputState, volts, temp, ambientLightValue, fix.satellites);
  if (fix.hdopValid) btCatf(buf, cap, &used, "%.2f", fix.hdop);
  else btCatf(buf, cap, &used, "-1");
  btCatf(buf, cap, &used, ",\"alt\":");
  if (fix.altValid) btCatf(buf, cap, &used, "%.0f", fix.altitudeM);
  else btCatf(buf, cap, &used, "-1");
  btCatf(buf, cap, &used,
         ",\"lat\":%.6f,\"lon\":%.6f,\"fix\":%d,\"tmr_st\":%d,\"tmr_s\":%.2f,"
         "\"fps\":%.1f,\"heap\":%lu,\"ble\":%d}",
         haveFix ? fix.lat : 0.0, haveFix ? fix.lon : 0.0, haveFix ? 1 : 0,
         tmrSt, tmrS, currentMeasuredFps, (unsigned long)ESP.getFreeHeap(),
         btLinkState());
  return used;
}

// Pump. Runs fast (10 ms) because it is also the RPC executor: a config save
// must not wait for the next telemetry beat. Telemetry itself stays at 1 Hz and
// its buffer is overwritten every second, so a stalled link never queues
// frames (AGENTS.md 14).
//
// One 8 KB receive buffer and one 512 B telemetry buffer are the task's static
// cost; the RPC handlers in bt_rpc.cpp free whatever they allocate.
static void btTask(void *pvParameters) {
  static char telemBuf[512];
  unsigned long lastTelem = 0;
  unsigned long lastStackLog = 0;
  unsigned long lastLinkLog = 0;
  for (;;) {
    const unsigned long now = millis();

    // Pairing resumes on a delay, never from the GAP callback (blocking there
    // stalls the whole stack). The unit is uncontactable while this is pending.
    if (!s_connected && s_advResumeAt && now >= s_advResumeAt) {
      s_advResumeAt = 0;
      BLEDevice::getAdvertising()->start();
      s_advertising = true;
      logPrintf("BT: pairing mode on, %u ms after the link dropped\n",
                (unsigned)BT_PAIR_DELAY_MS);
    }

    if (s_rxReady) {
      s_rxBusy = true;
      const size_t len = s_rxLen;
      const uint16_t seq = s_rxSeq;
      logPrintf("BT: request %u, %u B\n", seq, (unsigned)len);
      s_rx[len < BT_MAX_MSG ? len : BT_MAX_MSG - 1] = 0;
      btRpcHandle(s_rx, len, seq);
      s_rxReady = false;
      s_rxBusy = false;
      s_rxLen = 0;
    }

    if (now - lastTelem >= 1000) {
      lastTelem = now;
      // The quiet window stops the dashboard stream: on this link the image and
      // the dashboard compete for the same airtime, and during an install the
      // only traffic the phone needs is the install's own (ACK events and the
      // occasional status question). The snapshot values are parked too, so a
      // stream here would be a stream of frozen numbers.
      if (s_connected && s_telem != nullptr && !otaQuietActive) {
        size_t n = btBuildSnapshotJson(telemBuf, sizeof(telemBuf));
        if (n > 0) btSendMessage(BT_F_TELEM, (uint16_t)(now / 1000), telemBuf, n);
      }
    }

    // The install: drain whatever the Bluedroid task copied off the OTA
    // characteristic into flash, fold the CRC, ACK, and enforce the resume and
    // total-duration windows. This 10 ms pace is also the fastest a 496 B write
    // can be consumed at, and the ring is what absorbs a burst between passes.
    btOtaPoll();

    // The phase 2a bench driver is not started from here: it is an RPC method
    // ("bench.run", see the dispatcher in bt_rpc.cpp) so the central measuring it
    // is already connected, paired and subscribed when it runs.

    // The stack hands out a random passkey with a 30 s entry window. If the
    // phone never answers, drop the code so the panel returns to the dashboard
    // instead of sitting on a code that can no longer be used.
    if (s_pairing && now - s_pairingSince > 60000) {
      s_pairing = false;
      s_passkey = 0;
      logPrintf("BT: pairing window expired\n");
    }

    // The link's own vital signs, every 10 s while a phone is connected. Two
    // things live here: whether the unit still believes the link is open (a link
    // the phone walked away from would otherwise sit here forever and never start
    // advertising again), and the CCCD flags the BLE library reads before it will
    // let a notification out. A stream the phone never subscribed to is dropped by
    // the library without a word, which is the difference between "the answer was
    // never built" and "the answer left and was ignored".
    if (s_connected && now - lastLinkLog >= 10000) {
      lastLinkLog = now;
      logPrintf("BT: link %lus up, peers %d, cccd telem=%d rpc=%d, mtu %u\n",
                (unsigned long)(now / 1000), s_server ? (int)s_server->getConnectedCount() : -1,
                cccdFlag(s_telem), cccdFlag(s_rpc), (unsigned)s_mtu);
    }

    // Stack headroom: the RPC path runs ArduinoJson and printf on this task, so
    // its stack is the one worth watching. Logged every 30 s until the perf
    // method surfaces it to the app.
    if (now - lastStackLog >= 30000) {
      lastStackLog = now;
      // `rx_high` is the deepest the reassembly buffer has ever been, not its
      // length right now: an idle BtTask reads 0 for the length even a minute
      // after a 4.8 KB settings save went through, and that depth is the number
      // the heap plan needs to decide whether BT_MAX_MSG can shrink.
      logPrintf("BT: stack free %u B, heap %lu, mtu %u, rx_high %u/%u B\n",
                (unsigned)uxTaskGetStackHighWaterMark(NULL),
                (unsigned long)ESP.getFreeHeap(), (unsigned)s_mtu,
                (unsigned)btRxHighWater(), (unsigned)BT_MAX_MSG);
    }

    btLoopCount = btLoopCount + 1;
    vTaskDelay(pdMS_TO_TICKS(10));
  }
}

// ----------------------------------------------------------------------------
// Public API
// ----------------------------------------------------------------------------

int btLinkState() {
  if (s_connected) return 2;
  return s_advertising ? 1 : 0;
}

bool btPairingActive() { return s_pairing; }

void btAdvertisedName(char *out, size_t n) {
  snprintf(out, n, "%s", s_adName[0] ? s_adName : "DPP-????");
}

uint16_t btStackFree() {
  return s_btTask ? (uint16_t)uxTaskGetStackHighWaterMark(s_btTask) : 0;
}

// The handle, for the heapdiag stack table (which reports every task the same
// way rather than special-casing this one).
void *btTaskHandleForDiag() { return (void *)s_btTask; }

// Forgets every pairing. A factory reset has to do this or the unit comes back
// owned by whoever paired it last: the GATT permissions demand an encrypted
// link, and only a bonded phone can build one.
void btClearBonds() {
  esp_ble_bond_dev_t list[8];
  int n = (int)(sizeof(list) / sizeof(list[0]));
  if (esp_ble_get_bond_device_list(&n, list) != ESP_OK) {
    logPrintf("BT: bond list unavailable - pairings not cleared\n");
    return;
  }
  int removed = 0;
  for (int i = 0; i < n; i++)
    if (esp_ble_remove_bond_device(list[i].bd_addr) == ESP_OK) removed++;
  logPrintf("BT: cleared %d of %d bond(s)\n", removed, n);
}

uint32_t btPairingPasskey() { return s_passkey; }

void btClearPairingPasskey() {
  s_pairing = false;
  s_passkey = 0;
}

void btInit() {
  char name[16];
  uint64_t mac = ESP.getEfuseMac();
  snprintf(name, sizeof(name), "DPP-%04X", (unsigned)(mac & 0xFFFF));
  snprintf(s_adName, sizeof(s_adName), "%s", name);

  BLEDevice::init(name);
  BLEDevice::setMTU(517);
  BLEDevice::setSecurityCallbacks(new BtSecurityCallbacks());

  BLESecurity::setCapability(ESP_IO_CAP_OUT);  // we display, the phone types
  BLESecurity::setAuthenticationMode(ESP_LE_AUTH_REQ_SC_MITM_BOND);
  BLESecurity::setKeySize(16);
  BLESecurity::setInitEncryptionKey(ESP_BLE_ENC_KEY_MASK | ESP_BLE_ID_KEY_MASK);
  BLESecurity::setRespEncryptionKey(ESP_BLE_ENC_KEY_MASK | ESP_BLE_ID_KEY_MASK);

  s_server = BLEDevice::createServer();
  s_server->setCallbacks(new BtServerCallbacks());

  BLEService *svc = s_server->createService(SVC_UUID);

  s_telem = svc->createCharacteristic(TELEM_UUID, BLECharacteristic::PROPERTY_NOTIFY);
  s_telem->addDescriptor(new BLE2902());
  s_telem->setAccessPermissions(ESP_GATT_PERM_READ_ENC_MITM);

  s_rpc = svc->createCharacteristic(
      RPC_UUID, BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR |
                    BLECharacteristic::PROPERTY_NOTIFY);
  // The CCCD is not optional: without it a central has nothing to write to turn
  // notifications on, and every answer the unit sends (responses and config
  // chunks both travel here) would be dropped. Windows says so bluntly - "Write
  // Not Permitted" on subscribe.
  s_rpc->addDescriptor(new BLE2902());
  // Without this the ATT layer answers the write and the bytes go nowhere: the
  // reassembler in btRxFragment is only ever fed from this callback.
  s_rpc->setCallbacks(new BtRpcCallbacks());
  s_rpc->setAccessPermissions(ESP_GATT_PERM_WRITE_ENC_MITM);

  s_ota = svc->createCharacteristic(OTA_UUID, BLECharacteristic::PROPERTY_WRITE_NR);
  s_ota->setAccessPermissions(ESP_GATT_PERM_WRITE_ENC_MITM);
  // Write-only on purpose: no CCCD, because an install never answers on this
  // characteristic - its events go out on RPC (see btSendMessage).
  s_ota->setCallbacks(new BtOtaCallbacks());

  svc->start();

  BLEAdvertising *adv = BLEDevice::getAdvertising();
  // The name goes in BOTH the advertisement and the scan response, written as an
  // explicit payload. Left to the library, the primary packet carries flags +
  // TX power + a 128-bit service UUID + the name, which is 33 B in a 31 B
  // packet - Bluedroid then drops the name, and a phone scanning for "DPP-XXXX"
  // finds a unit it cannot name (seen from Windows as intermittent "no DPP-*
  // advertising"). Flags + name + UUID is 30 B and always fits.
  {
    BLEAdvertisementData advData, scanData;
    advData.setFlags(0x06);  // LE general discoverable, no bonding state bits
    advData.setName(name);
    advData.setCompleteServices(BLEUUID(SVC_UUID));
    scanData.setName(name);
    scanData.setCompleteServices(BLEUUID(SVC_UUID));
    adv->setAdvertisementData(advData);
    adv->setScanResponseData(scanData);
  }
  adv->setScanResponse(true);
  adv->setMinPreferred(0x06);  // Android's connection interval hint
  adv->start();
  s_advertising = true;

  // 8 KB, not the 4 KB this task started with: the RPC handlers parse JSON on
  // this stack (bt_rpc.cpp), and Phase 0b measured 3,336 B free with the pump
  // alone, which left no room for an ArduinoJson visitor walk plus a printf.
  xTaskCreatePinnedToCore(btTask, "BtTaskCore0", 8192, NULL, 1, &s_btTask, 0);
  logPrintf("BT: advertising as %s (MTU %u, %d bond(s))\n", name, (unsigned)BLEDevice::getMTU(),
            btBondCount());
}
