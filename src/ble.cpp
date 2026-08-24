// BLE GATT transport for the phone companion. See src/ble.h and
// Implementation plans/bluetooth-weather-and-phone-notifications-plan.md.
//
// The ESP is a GATT *server* (peripheral): it advertises a 16-bit service
// (0xD11A) and two characteristics. The phone (central) writes framed
// payloads to RX (0xD101); the ESP reassembles the frame and dispatches it
// (WEATHER / NOTIFICATION / NAV / MEDIA / PING / ACK). STATUS is pushed back
// over TX (0xD102, notify + read).
#include "ble.h"
#include "bar.h"
#include "nav.h"
#include <NimBLEDevice.h>
#include <ArduinoJson.h>
#include <Arduino.h>
#include <WiFi.h>
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
#include <freertos/semphr.h>
#include <string.h>

// ----------------------------------------------------------------------------
// GATT objects
// ----------------------------------------------------------------------------
static NimBLEServer *g_server = nullptr;
static NimBLEService *g_svc = nullptr;
static NimBLECharacteristic *g_rxChar = nullptr;  // phone -> ESP (write)
static NimBLECharacteristic *g_txChar = nullptr;  // ESP -> phone (notify+read)
static volatile bool g_connected = false;

// ----------------------------------------------------------------------------
// Frame reassembly (the phone is the writer; the ESP accumulates chunks)
// ----------------------------------------------------------------------------
static uint8_t rxAccum[1024];
static uint16_t rxAccumLen = 0;

static void bleDispatch(uint8_t type, const uint8_t *payload, uint16_t len);

// Accumulate a BLE write chunk, then parse any complete frames. Resync-safe:
// a bogus header drops the leading byte and retries (a mid-frame disconnect
// just discards the partial; the phone re-sends on reconnect).
static void rxAppend(const uint8_t *data, uint16_t len) {
  if (len == 0) return;
  if (rxAccumLen + len > sizeof(rxAccum))
    rxAccumLen = 0;  // overflow: resync from scratch
  memcpy(&rxAccum[rxAccumLen], data, len);
  rxAccumLen += len;
  while (rxAccumLen >= 3) {
    uint8_t type = rxAccum[0];
    uint16_t plen = (uint16_t)(rxAccum[1] | (rxAccum[2] << 8));
    if (type > BLE_TYPE_MEDIA || plen > 512) {
      // Bad header: drop the first byte and resync.
      memmove(&rxAccum[0], &rxAccum[1], rxAccumLen - 1);
      rxAccumLen--;
      continue;
    }
    if (3 + plen > rxAccumLen) break;  // incomplete, wait for more
    uint8_t payload[512];
    memcpy(payload, &rxAccum[3], plen);
    bleDispatch(type, payload, plen);
    memmove(&rxAccum[0], &rxAccum[3 + plen], rxAccumLen - (3 + plen));
    rxAccumLen -= (3 + plen);
  }
}

// ----------------------------------------------------------------------------
// Copy a JSON string field (or its default) into a fixed char[] (NUL-safe).
// dst is a char[N] field; cap = its element count.
// ----------------------------------------------------------------------------
static inline void copyDtoStr(char *dst, const char *src, int cap) {
  int n = (int)strlen(src);
  if (n > cap - 1) n = cap - 1;
  if (n < 0) n = 0;
  memcpy(dst, src, n);
  dst[n] = 0;
}

// ----------------------------------------------------------------------------
// Dispatch: parse the JSON payload, update ESP state via the state APIs
// ----------------------------------------------------------------------------
static void bleDispatch(uint8_t type, const uint8_t *payload, uint16_t len) {
  char buf[520];
  int n = len;
  if (n >= (int)sizeof(buf)) n = (int)sizeof(buf) - 1;
  memcpy(buf, payload, n);
  buf[n] = 0;

  JsonDocument doc;
  DeserializationError e = deserializeJson(doc, buf);
  if (e) return;  // malformed JSON: drop (no crash, no state change)

  switch (type) {
    case BLE_TYPE_WEATHER: {
      // Fill g_weatherData (the single source the widget reads). Same units
      // as the old Open-Meteo ingest: wind is km/h (the widget /3.6 to m/s).
      if (xSemaphoreTake(g_stateMutex, pdMS_TO_TICKS(10)) == pdTRUE) {
        g_weatherData.temperature = doc["temp"] | 0.0f;
        g_weatherData.humidity = doc["hum"] | 0;
        g_weatherData.windSpeed = doc["wind"] | 0.0f;
        g_weatherData.windDirection = doc["windDir"] | 0.0f;
        g_weatherData.weatherCode = doc["code"] | 0;
        g_weatherData.cloudCover = doc["cloud"] | 0;
        g_weatherData.sunriseTime = doc["sun"] | "--:--";
        g_weatherData.sunsetTime = doc["sunset"] | "--:--";
        g_weatherData.cityName = doc["city"] | "";
        g_weatherData.valid = true;
        g_weatherData.lastUpdated = millis();
        xSemaphoreGive(g_stateMutex);
      }
      break;
    }
    case BLE_TYPE_NOTIFICATION: {
      NtfDto dto;
      copyDtoStr(dto.app, doc["app"] | "", (int)sizeof(dto.app));
      copyDtoStr(dto.title, doc["title"] | "", (int)sizeof(dto.title));
      copyDtoStr(dto.body, doc["body"] | "", (int)sizeof(dto.body));
      copyDtoStr(dto.icon, doc["icon"] | "", (int)sizeof(dto.icon));
      barPush(dto);
      break;
    }
    case BLE_TYPE_MEDIA: {
      MediaDto dto;
      copyDtoStr(dto.artist, doc["artist"] | "", (int)sizeof(dto.artist));
      copyDtoStr(dto.song, doc["song"] | "", (int)sizeof(dto.song));
      copyDtoStr(dto.icon, doc["icon"] | "", (int)sizeof(dto.icon));
      dto.active = doc["active"] | true;
      barSetSong(dto);
      break;
    }
    case BLE_TYPE_NAV: {
      NavDto dto;
      dto.on = doc["on"] | false;
      copyDtoStr(dto.act, doc["act"] | "", (int)sizeof(dto.act));
      dto.d = doc["d"] | 0.0;
      copyDtoStr(dto.u, doc["u"] | "", (int)sizeof(dto.u));
      copyDtoStr(dto.road, doc["road"] | "", (int)sizeof(dto.road));
      copyDtoStr(dto.eta, doc["eta"] | "", (int)sizeof(dto.eta));
      dto.arrival = doc["arr"] | false;
      navPush(dto);
      break;
    }
    case BLE_TYPE_PING: {
      bleSendStatus();
      break;
    }
    case BLE_TYPE_ACK:
      // Optional phone->ESP ack of an ESP event: no state to update.
      break;
    default:
      break;  // unknown type: drop
  }
}

// ----------------------------------------------------------------------------
// STATUS (ESP -> phone): heap / fps / wifi / bt / nq / ver
// ----------------------------------------------------------------------------
void bleSendStatus() {
  if (!g_txChar) return;
  char buf[200];
  int n = snprintf(buf, sizeof(buf),
                   "{\"t\":\"st\",\"heap\":%lu,\"fps\":%d,\"wifi\":%s,"
                   "\"bt\":%s,\"nq\":%u,\"ver\":\"%s\"}",
                   (unsigned long)ESP.getFreeHeap(), (int)currentAverageFps,
                   WiFi.status() == WL_CONNECTED ? "true" : "false",
                   g_connected ? "true" : "false",
                   (unsigned int)barQueueDepth(),
                   OTA_CURRENT_VERSION);
  g_txChar->notify((const uint8_t *)buf, n);
}

bool bleIsLinked() { return g_connected; }

// ----------------------------------------------------------------------------
// GATT callbacks
// ----------------------------------------------------------------------------
class RxCallbacks : public NimBLECharacteristicCallbacks {
public:
  void onWrite(NimBLECharacteristic *pChar, NimBLEConnInfo &connInfo) override {
    (void)connInfo;
    NimBLEAttValue v = pChar->getValue();
    uint16_t len = v.length();
    const uint8_t *data = reinterpret_cast<const uint8_t *>(v.c_str());
    rxAppend(data, len);
  }
};

class ServerCB : public NimBLEServerCallbacks {
public:
  void onConnect(NimBLEServer *pServer, NimBLEConnInfo &connInfo) override {
    (void)pServer;
    (void)connInfo;
    g_connected = true;
    bleSendStatus();
  }
  void onDisconnect(NimBLEServer *pServer, NimBLEConnInfo &connInfo, int reason) override {
    (void)pServer;
    (void)connInfo;
    (void)reason;
    g_connected = false;
    // Re-advertise: the phone re-scans and reconnects.
    NimBLEDevice::startAdvertising();
  }
};

static RxCallbacks rxCallbacks;
static ServerCB serverCallbacks;

// ----------------------------------------------------------------------------
// bleTask: pinned to core 0, low priority. Does the NimBLE init + GATT setup
// + advertise, then idles (the NimBLE host task runs the GATT callbacks off
// the hot pixel path).
// ----------------------------------------------------------------------------
void bleTask(void *pv) {
  (void)pv;
  NimBLEDevice::init("Dashboard++");
  g_server = NimBLEDevice::createServer();
  g_server->setCallbacks(&serverCallbacks);
  g_svc = g_server->createService(NimBLEUUID(BLE_SVC_UUID16));
  // RX (phone -> ESP): write-without-response. 512-byte max attr value.
  g_rxChar = g_svc->createCharacteristic(NimBLEUUID(BLE_RX_UUID16),
                                         NIMBLE_PROPERTY::WRITE |
                                             NIMBLE_PROPERTY::WRITE_NR,
                                         512);
  g_rxChar->setCallbacks(&rxCallbacks);
  // TX (ESP -> phone): notify + read.
  g_txChar = g_svc->createCharacteristic(NimBLEUUID(BLE_TX_UUID16),
                                         NIMBLE_PROPERTY::READ |
                                             NIMBLE_PROPERTY::NOTIFY,
                                         512);
  g_txChar->setValue("");

  NimBLEAdvertising *adv = NimBLEDevice::getAdvertising();
  adv->setName("Dashboard++");
  adv->addServiceUUID(NimBLEUUID(BLE_SVC_UUID16));
  adv->start();

  for (;;) vTaskDelay(pdMS_TO_TICKS(1000));  // idle (pinned core 0)
}
