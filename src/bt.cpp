// Bluetooth Low Energy link - the unit's only external interface.
//
// Phase 0b skeleton: one GATT service with three characteristics (telemetry
// notify, RPC write, OTA write), LE Secure Connections pairing with a passkey
// shown on the panel, and a 1 Hz telemetry pump that pushes the same JSON the
// old /api/snapshot endpoint served. RPC dispatch and the OTA state machine
// land in phase 2; the characteristics are registered now so the app can
// discover them and the flash/heap cost is measured against the real layout.
//
// Memory rules (AGENTS.md 14): the telemetry frame is one static buffer, the
// snapshot serializer is allocation-free, and nothing here builds a String.

#include "dashboard.h"

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
static volatile bool s_pairing = false;
static volatile uint32_t s_passkey = 0;

volatile unsigned long btLoopCount = 0;

// OTA state that used to live in web.cpp. The upload path is reimplemented on
// the OTA characteristic in phase 2b; these are the two symbols the rest of the
// firmware still reads.
volatile bool otaUpdateSuccess = false;
bool otaFlashWriteActive() { return false; }

// The update screen asks these three for "why did the last install stop".
// Phase 2b's OTA state machine owns them; until then a BLE-only build never
// starts an install, so the screen can only be reached with nothing to say.
const char *otaInstallReason() { return "none"; }
const char *otaInstallDetail() { return ""; }
void otaInstallBytesSnapshot(long *bytesDone, long *bytesTotal) {
  *bytesDone = 0;
  *bytesTotal = 0;
}

// ----------------------------------------------------------------------------
// Security
// ----------------------------------------------------------------------------
class BtSecurityCallbacks : public BLESecurityCallbacks {
  uint32_t onPassKeyRequest() override { return s_passkey; }

  void onPassKeyNotify(uint32_t pass_key) override {
    s_passkey = pass_key;
    s_pairing = true;
    logPrintf("BT: pairing passkey %06lu - type it on the phone\n",
              (unsigned long)pass_key);
  }

  bool onSecurityRequest() override { return true; }

  bool onConfirmPIN(uint32_t) override { return true; }

  void onAuthenticationComplete(esp_ble_auth_cmpl_t cmpl) override {
    s_pairing = false;
    s_passkey = 0;
    logPrintf("BT: auth %s (key %s)\n", cmpl.success ? "ok" : "FAILED",
              cmpl.key_present ? "stored" : "none");
  }
};

class BtServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *server, esp_ble_gatts_cb_param_t *param) override {
    s_connected = true;
    s_advertising = false;
    logPrintf("BT: phone connected (conn %u)\n", (unsigned)param->connect.conn_id);
    // Ask for the encrypted link immediately; the passkey prompt follows. This
    // is the peripheral-side request the stack exposes (there is no
    // esp_ble_gap_security_req in this IDF): MITM so SC pairing runs, and the
    // bond lets the next connection skip the code.
    esp_ble_set_encryption(param->connect.remote_bda, ESP_BLE_SEC_ENCRYPT_MITM);
  }

  void onDisconnect(BLEServer *server) override {
    s_connected = false;
    s_pairing = false;
    s_passkey = 0;
    server->startAdvertising();
    s_advertising = true;
    logPrintf("BT: phone disconnected, advertising again\n");
  }
};

// ----------------------------------------------------------------------------
// Telemetry
// ----------------------------------------------------------------------------
// Allocation-free append with a running cursor: writes at most cap-1 bytes in
// total, so a long list can never walk off the end of the caller's buffer.
// Moved here from web.cpp with the snapshot serializer it serves.
static void btCatf(char *buf, size_t cap, size_t *used, const char *fmt, ...) {
  if (*used >= cap - 1) return;
  va_list args;
  va_start(args, fmt);
  int n = vsnprintf(buf + *used, cap - *used, fmt, args);
  va_end(args);
  if (n <= 0) return;
  *used += (size_t)n;
  if (*used > cap - 1) *used = cap - 1;
}

// Same field names and ordering as the old GET /api/snapshot, so the app's
// Snapshot parser works unchanged. ~360 bytes worst case.
static size_t btBuildSnapshot(char *buf, size_t cap) {
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

// 1 Hz pump: latest-only by construction - the buffer is overwritten every
// second, so a stalled link never queues frames (AGENTS.md 14).
static void btTask(void *pvParameters) {
  static char telemBuf[512];
  unsigned long lastStackLog = 0;
  for (;;) {
    if (s_connected && s_telem != nullptr) {
      size_t n = btBuildSnapshot(telemBuf, sizeof(telemBuf));
      if (n > 0) {
        s_telem->setValue((uint8_t *)telemBuf, n);
        s_telem->notify();
      }
    }
    // Stack headroom: the snapshot serializer runs vsnprintf with floats, so
    // this is the one task whose 4 KB could be too thin. Logged every 30 s
    // until a perf channel exists (phase 2a moves it to the RPC surface).
    if (millis() - lastStackLog >= 30000) {
      lastStackLog = millis();
      logPrintf("BT: stack free %u B, heap %lu, mtu %u\n",
                (unsigned)uxTaskGetStackHighWaterMark(NULL),
                (unsigned long)ESP.getFreeHeap(), (unsigned)BLEDevice::getMTU());
    }
    btLoopCount = btLoopCount + 1;
    vTaskDelay(pdMS_TO_TICKS(1000));
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

uint32_t btPairingPasskey() { return s_passkey; }

void btClearPairingPasskey() {
  s_pairing = false;
  s_passkey = 0;
}

void btInit() {
  char name[16];
  uint64_t mac = ESP.getEfuseMac();
  snprintf(name, sizeof(name), "DPP-%04X", (unsigned)(mac & 0xFFFF));

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
  s_rpc->setAccessPermissions(ESP_GATT_PERM_WRITE_ENC_MITM);

  s_ota = svc->createCharacteristic(OTA_UUID, BLECharacteristic::PROPERTY_WRITE_NR);
  s_ota->setAccessPermissions(ESP_GATT_PERM_WRITE_ENC_MITM);

  svc->start();

  BLEAdvertising *adv = BLEDevice::getAdvertising();
  adv->addServiceUUID(SVC_UUID);
  adv->setScanResponse(true);
  adv->setMinPreferred(0x06);  // Android's connection interval hint
  adv->start();
  s_advertising = true;

  xTaskCreatePinnedToCore(btTask, "BtTaskCore0", 4096, NULL, 1, NULL, 0);
  logPrintf("BT: advertising as %s (MTU %u)\n", name, (unsigned)BLEDevice::getMTU());
}
