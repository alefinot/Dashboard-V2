// RPC methods - what the HTTP API endpoints became.
//
// The app writes {"m":"<method>","id":<n>,"p":{...}} to the RPC characteristic
// and reads back {"id":<n>,"ok":true,"r":{...}} or {"id":<n>,"ok":false,
// "e":"<code>"}. One request is in flight at a time (bt.cpp refuses a second
// while this function runs), so every handler here can share one transmit
// buffer, and nothing in this file allocates except the transient ArduinoJson
// documents a config read or write needs (AGENTS.md 14).
//
// Bodies are built with printf appenders rather than a JsonDocument +
// serializeJson for the same reason the old /api/live and /api/health were:
// a fixed buffer, no allocator, and the response shape is visible in the
// format string. The config paths are the exception - a 200-key object is not
// something to hand-write, and there the parse is the point.
//
// The endpoints that had no BLE counterpart (wifi.scan/join/forget, index,
// debug) are gone with the radio that served them, and the OTA methods arrive
// with phase 2b: the image travels on its own characteristic, not as JSON.

#include "dashboard.h"
#include "bt_proto.h"
#include "heapdiag.h"
#include "noisebench.h"
#include "bootinfo.h"

#include <BLEDevice.h>  // the negotiated MTU, reported by `perf`
#include <LittleFS.h>
#include <esp_ota_ops.h>
#include <esp_partition.h>

#include <stdarg.h>
#include <string.h>
#include <time.h>

// One response buffer, reused by every method. Safe because the RPC path is
// strictly serial: bt.cpp holds s_rxBusy across btRpcHandle, and the telemetry
// pump has its own buffer. 4 KB is sized by the largest reply this file builds
// by hand (perf with its partition table, ~1.1 KB) plus the bounded log tail.
static char s_out[4096];

// A chunk of the config stream is {"id":req,"i":n,"last":bool,"d":{...}}. The
// envelope prefix is bounded by this; it buys a probe pass over ~40 rows
// instead of buffer gymnastics (see methodConfigGet).
#define BT_CHUNK_PREFIX_MAX 96
#define BT_CHUNK_ROWS 40

// The live log window, in bytes of raw log. The old /api/serial handed over the
// whole 4 KB ring as text/plain; a JSON string has to escape it, and the plan
// calls for a bounded tail, so the tail is capped and the response says whether
// anything was left behind.
#define BT_LOG_TAIL 1500

// ---------------------------------------------------------------------------
// Response plumbing
// ---------------------------------------------------------------------------

static void rpcErr(uint16_t seq, uint32_t id, const char *code) {
  size_t used = 0;
  if (!btCatf(s_out, sizeof(s_out), &used, "{\"id\":%lu,\"ok\":false,\"e\":\"%s\"}",
              (unsigned long)id, code))
    return;
  btSendMessage(BT_F_RESP, seq, s_out, used);
}

// Opens an envelope in s_out and returns the offset its body must start at.
// Body writers then pass `sizeof(s_out) - 1` as their cap: that leaves the two
// bytes (closing brace + NUL) rpcFinish needs.
static size_t rpcBegin(uint32_t id) {
  size_t used = 0;
  btCatf(s_out, sizeof(s_out), &used, "{\"id\":%lu,\"ok\":true,\"r\":", (unsigned long)id);
  return used;
}

// A chunk is not an answer, it is one slice of a bigger one, so it does not get
// the "ok"/"r" envelope: the frame body is {"id":req,"i":n,"last":bool,
// "d":{...}} and the stream closes with an ordinary rpcFinish response
// (bt_proto.h, BT_F_CHUNK).
static size_t rpcBeginChunk(uint32_t id) {
  size_t used = 0;
  btCatf(s_out, sizeof(s_out), &used, "{\"id\":%lu,", (unsigned long)id);
  return used;
}

static void rpcFinish(uint16_t seq, size_t prefix, size_t bodyLen) {
  if (prefix + bodyLen + 2 > sizeof(s_out)) {
    rpcErr(seq, 0, "too_large");
    return;
  }
  size_t used = prefix + bodyLen;
  s_out[used++] = '}';
  s_out[used] = 0;
  btSendMessage(BT_F_RESP, seq, s_out, used);
}

// The common case: a body already assembled as text.
static void rpcSend(uint16_t seq, uint32_t id, const char *body) {
  const size_t prefix = rpcBegin(id);
  size_t used = prefix;
  if (!btCatf(s_out, sizeof(s_out) - 1, &used, "%s", body)) {
    rpcErr(seq, id, "too_large");
    return;
  }
  rpcFinish(seq, prefix, used - prefix);
}

// Same for a body that lives in a String (bootinfo_json), with the length the
// String already knows.
static void rpcSendStr(uint16_t seq, uint32_t id, const String &s) {
  const size_t prefix = rpcBegin(id);
  const size_t len = s.length();
  if (prefix + len + 2 > sizeof(s_out)) {
    logPrintf("RPC: response for id %lu does not fit (%u + %u B)\n", (unsigned long)id,
              (unsigned)prefix, (unsigned)len);
    rpcErr(seq, id, "too_large");
    return;
  }
  memcpy(s_out + prefix, s.c_str(), len);
  rpcFinish(seq, prefix, len);
}

// ---------------------------------------------------------------------------
// Read-only mirrors of the old GET endpoints
// ---------------------------------------------------------------------------

// GET /api/live - the live tab's readings in one atomic answer.
static void methodLive(uint16_t seq, uint32_t id) {
  float v = 0.0f, t = 0.0f;
  if (xSemaphoreTake(g_stateMutex, pdMS_TO_TICKS(10)) == pdTRUE) {
    v = g_sensorData.batteryVoltage;
    t = g_sensorData.engineTemperature;
    xSemaphoreGive(g_stateMutex);
  }
  const float ohm = fuelMeasuredOhms > 9999.9f ? 9999.9f : fuelMeasuredOhms;
  const size_t prefix = rpcBegin(id);
  size_t used = prefix;
  btCatf(s_out, sizeof(s_out) - 1, &used,
         "{\"ambient\":%d,\"odo\":%.2f,\"fuel_raw\":%d,\"fuel_ohm\":%.1f,"
         "\"fuel_st\":%d,\"volts\":%.2f,\"temp\":%.1f}",
         ambientLightValue, odoGet(), rawFuelADC, ohm, (int)fuelInputState, v, t);
  rpcFinish(seq, prefix, used - prefix);
}

// GET /api/health
static void methodHealth(uint16_t seq, uint32_t id) {
  refreshNvsStats(5000);
  const size_t prefix = rpcBegin(id);
  size_t used = prefix;
  btCatf(s_out, sizeof(s_out) - 1, &used,
         "{\"heap\":%lu,\"maxalloc\":%lu,\"minheap\":%lu,\"mem_saver\":%d,"
         "\"nvs_available\":%u,\"nvs_used\":%u,\"nvs_total\":%u,\"uptime\":%lu}",
         (unsigned long)ESP.getFreeHeap(), (unsigned long)ESP.getMaxAllocHeap(),
         (unsigned long)ESP.getMinFreeHeap(), memSaverActive ? 1 : 0,
         (unsigned)nvsStatsAvailable, (unsigned)nvsStatsUsed, (unsigned)nvsStatsTotal,
         (unsigned long)(millis() / 1000));
  rpcFinish(seq, prefix, used - prefix);
}

// GET /api/sensors
static void methodSensors(uint16_t seq, uint32_t id) {
  float v = 0.0f, t = 0.0f;
  if (xSemaphoreTake(g_stateMutex, pdMS_TO_TICKS(10)) == pdTRUE) {
    v = g_sensorData.batteryVoltage;
    t = g_sensorData.engineTemperature;
    xSemaphoreGive(g_stateMutex);
  }
  const size_t prefix = rpcBegin(id);
  size_t used = prefix;
  btCatf(s_out, sizeof(s_out) - 1, &used, "{\"v\":%.2f,\"t\":%.1f}", v, t);
  rpcFinish(seq, prefix, used - prefix);
}

// GET /api/fuel
static void methodFuel(uint16_t seq, uint32_t id) {
  float liters = 0.0f;
  int pct = 0;
  if (xSemaphoreTake(g_stateMutex, pdMS_TO_TICKS(10)) == pdTRUE) {
    liters = g_sensorData.fuelLiters;
    pct = g_sensorData.fuelPercentage;
    xSemaphoreGive(g_stateMutex);
  }
  const float ohm = fuelMeasuredOhms > 9999.9f ? 9999.9f : fuelMeasuredOhms;
  const size_t prefix = rpcBegin(id);
  size_t used = prefix;
  btCatf(s_out, sizeof(s_out) - 1, &used, "{\"raw\":%d,\"liters\":%.1f,\"pct\":%d,\"ohm\":%.1f,\"st\":%d}",
         rawFuelADC, liters, pct, ohm, (int)fuelInputState);
  rpcFinish(seq, prefix, used - prefix);
}

// GET /api/ambient - the raw sensor reading the calibration screens watch.
static void methodAmbient(uint16_t seq, uint32_t id) {
  const size_t prefix = rpcBegin(id);
  size_t used = prefix;
  btCatf(s_out, sizeof(s_out) - 1, &used, "{\"raw\":%d}", ambientLightValue);
  rpcFinish(seq, prefix, used - prefix);
}

// GET /api/snapshot - on demand. The 1 Hz stream is the normal source; this is
// what a client that has just subscribed uses to fill its first frame.
static void methodSnapshot(uint16_t seq, uint32_t id) {
  const size_t prefix = rpcBegin(id);
  const size_t n = btBuildSnapshotJson(s_out + prefix, sizeof(s_out) - prefix - 1);
  rpcFinish(seq, prefix, n);
}

// GET /api/noisematrix
static void methodNoise(uint16_t seq, uint32_t id, JsonVariantConst p) {
  if (p["start"].is<bool>() && p["start"].as<bool>()) {
    const uint8_t r = noiseBenchStart();
    if (r == NOISE_BENCH_START_OK) rpcSend(seq, id, "{\"status\":\"started\",\"runs\":4,\"samples\":4000}");
    else if (r == NOISE_BENCH_START_BUSY) rpcSend(seq, id, "{\"status\":\"busy\"}");
    else rpcSend(seq, id, "{\"status\":\"failed\"}");
    return;
  }
  NoiseBenchResult res;
  noiseBenchSnapshot(&res);
  const char *status = res.state == NOISE_BENCH_RUNNING ? "running"
                       : res.state == NOISE_BENCH_DONE  ? "done"
                                                        : "never";
  const size_t prefix = rpcBegin(id);
  size_t used = prefix;
  btCatf(s_out, sizeof(s_out) - 1, &used, "{\"status\":\"%s\",\"runs\":%u,\"job_ms\":%lu,\"list\":[",
         status, (unsigned)res.completed, (unsigned long)res.jobMs);
  for (uint8_t i = 0; i < res.completed; i++) {
    const NoiseBenchRun &run = res.run[i];
    btCatf(s_out, sizeof(s_out) - 1, &used,
           "%s{\"id\":\"%s\",\"samples\":%lu,\"interval_ms\":%.2f,\"ch\":[", i ? "," : "",
           run.id, (unsigned long)run.samples, (double)run.intervalMs);
    for (uint8_t c = 0; c < NOISE_BENCH_CHANNELS; c++) {
      const NoiseBenchChannel &ch = run.ch[c];
      btCatf(s_out, sizeof(s_out) - 1, &used,
             "%s{\"id\":\"%s\",\"mean_mv\":%.2f,\"sigma_mv\":%.2f,\"pp_mv\":%.2f,"
             "\"mean_lsb\":%.2f,\"sigma_lsb\":%.2f,\"pp_lsb\":%.0f}",
             c ? "," : "", ch.id, (double)ch.meanMv, (double)ch.sigmaMv, (double)ch.ppMv,
             (double)ch.meanLsb, (double)ch.sigmaLsb, (double)ch.ppLsb);
    }
    btCatf(s_out, sizeof(s_out) - 1, &used, "]}");
  }
  btCatf(s_out, sizeof(s_out) - 1, &used, "]}");
  rpcFinish(seq, prefix, used - prefix);
}

// GET /api/perf. The Wi-Fi and web-server fields are gone with the radio and
// the server that produced them (wifi_clients, lan_ip, web_serve_ms_*,
// web_slow_iters); the BLE block replaces them with the link the unit actually
// has, and BtTaskCore0 replaces WebTaskCore0 in stack_free.
static void methodPerf(uint16_t seq, uint32_t id) {
  refreshNvsStats(5000);
  const uint64_t efuseMac = ESP.getEfuseMac();
  char macStr[18];
  // Same octet order the app has always seen: getEfuseMac() puts octet 0 in the
  // LOW byte, so the shifts run 0..40 to print A4:CF:12:3B:9E:07.
  snprintf(macStr, sizeof(macStr), "%02X:%02X:%02X:%02X:%02X:%02X", (uint8_t)efuseMac,
           (uint8_t)(efuseMac >> 8), (uint8_t)(efuseMac >> 16), (uint8_t)(efuseMac >> 24),
           (uint8_t)(efuseMac >> 32), (uint8_t)(efuseMac >> 40));

  const size_t prefix = rpcBegin(id);
  size_t used = prefix;
  btCatf(s_out, sizeof(s_out) - 1, &used,
         "{\"cpu_freq\":%d,\"cpu_temp\":%.1f,\"cpu_dynamic\":%d,\"cpu_usage\":%.1f,"
         "\"uptime_s\":%lu,\"mac\":\"%s\",\"free_heap\":%lu,\"min_free_heap\":%lu,",
         getCpuFrequencyMhz(), (float)temperatureRead(), ENABLE_DYNAMIC_CPU ? 1 : 0,
         (double)cpuUsagePct, (unsigned long)(millis() / 1000), macStr,
         (unsigned long)ESP.getFreeHeap(), (unsigned long)ESP.getMinFreeHeap());
  btCatf(s_out, sizeof(s_out) - 1, &used,
         "\"stack_free\":{\"SensorTaskCore1\":%u,\"GpsTaskCore0\":%u,\"BtTaskCore0\":%u},",
         (unsigned)uxTaskGetStackHighWaterMark(sensorTaskHandle),
         (unsigned)uxTaskGetStackHighWaterMark(gpsTaskHandle), (unsigned)btStackFree());
  // Heap-recovery plan (Phase 0): the stack high-water marks of every task, in
  // BYTES (this core's uxTaskGetStackHighWaterMark is byte-based, see
  // src/heapdiag.h), plus the internal-DRAM numbers the static trims are priced
  // against. Additive keys - the app reads perf by name and ignores the rest.
  btCatf(s_out, sizeof(s_out) - 1, &used, "\"stack_free_hwm_b\":{");
  used += heapDiagStackJson(s_out + used, sizeof(s_out) - used - 1);
  btCatf(s_out, sizeof(s_out) - 1, &used, "},\"heap_diag\":{");
  used += heapDiagHeapJson(s_out + used, sizeof(s_out) - used - 1);
  btCatf(s_out, sizeof(s_out) - 1, &used, ",\"ring_high\":%u,\"ring_gap_ms\":%lu},",
         (unsigned)btOtaRingHighWater(), (unsigned long)btOtaDrainGapMaxMs());
  btCatf(s_out, sizeof(s_out) - 1, &used,
         "\"mem_saver\":%d,\"heap_size\":%lu,\"psram_size\":%lu,\"psram_free\":%lu,"
         "\"flash_total\":%lu,\"flash_free\":%lu,",
         memSaverActive ? 1 : 0, (unsigned long)ESP.getHeapSize(),
         (unsigned long)ESP.getPsramSize(), (unsigned long)ESP.getFreePsram(),
         (unsigned long)ESP.getFlashChipSize(), (unsigned long)ESP.getFreeSketchSpace());

  const char *knownLabels[] = {"nvs", "otadata", "app0", "app1", "spiffs"};
  esp_partition_type_t knownTypes[] = {ESP_PARTITION_TYPE_DATA, ESP_PARTITION_TYPE_DATA,
                                       ESP_PARTITION_TYPE_APP, ESP_PARTITION_TYPE_APP,
                                       ESP_PARTITION_TYPE_DATA};
  esp_partition_subtype_t knownSubtypes[] = {
      ESP_PARTITION_SUBTYPE_DATA_NVS, ESP_PARTITION_SUBTYPE_DATA_OTA,
      ESP_PARTITION_SUBTYPE_APP_OTA_0, ESP_PARTITION_SUBTYPE_APP_OTA_1,
      ESP_PARTITION_SUBTYPE_DATA_SPIFFS};
  uint32_t flashUsed = 0;
  int partIdx = 0;
  const esp_partition_t *running = esp_ota_get_running_partition();
  btCatf(s_out, sizeof(s_out) - 1, &used, "\"partitions\":[");
  for (int i = 0; i < 5; i++) {
    const esp_partition_t *part =
        esp_partition_find_first(knownTypes[i], knownSubtypes[i], knownLabels[i]);
    if (!part) continue;
    uint32_t used32;
    if (strcmp(part->label, "spiffs") == 0)
      used32 = LittleFS.usedBytes();
    else if (part == running)
      used32 = part->size;
    else
      used32 = 0;
    flashUsed += used32;
    btCatf(s_out, sizeof(s_out) - 1, &used,
           "%s{\"label\":\"%s\",\"type\":%d,\"subtype\":%d,\"size\":%lu,\"addr\":%lu,\"used\":%lu}",
           partIdx++ ? "," : "", part->label, (int)part->type, (int)part->subtype,
           (unsigned long)part->size, (unsigned long)part->address, (unsigned long)used32);
  }
  btCatf(s_out, sizeof(s_out) - 1, &used, "],\"flash_used\":%lu,", (unsigned long)flashUsed);

  // Settings-storage budget in ENTRIES, not bytes - the unit that actually runs
  // out (see refreshNvsStats).
  btCatf(s_out, sizeof(s_out) - 1, &used,
         "\"nvs_bytes\":%lu,\"nvs_entries_used\":%lu,\"nvs_entries_available\":%lu,"
         "\"nvs_entries_total\":%lu,",
         (unsigned long)nvsStatsBytes, (unsigned long)nvsStatsUsed,
         (unsigned long)nvsStatsAvailable, (unsigned long)nvsStatsTotal);

  btCatf(s_out, sizeof(s_out) - 1, &used,
         "\"fps_current\":%.1f,\"fps_average\":%.1f,\"fps_target\":%d,"
         "\"refresh_ms\":%lu,\"spi_speed\":%lu,\"ambient_light\":%d,"
         "\"resolution\":\"%dx%d\",",
         (double)currentMeasuredFps, (double)currentAverageFps, TARGET_FPS,
         (unsigned long)DISPLAY_REFRESH_MS, (unsigned long)SPI_BUS_SPEED, ambientLightValue,
         DISPLAY_WIDTH, DISPLAY_HEIGHT);

  // The link the unit actually has. RSSI is deliberately absent: reading the
  // connected peer's signal needs a GAP callback of our own, and the core owns
  // the one Bluedroid hands out - it will come with a controller-side hook
  // rather than by hijacking the scanner's callback.
  btCatf(s_out, sizeof(s_out) - 1, &used,
         "\"ble_connected\":%d,\"ble_mtu\":%u,\"ble_pairing\":%d,\"ble_state\":%d}",
         btLinkUp() ? 1 : 0, (unsigned)BLEDevice::getMTU(), btPairingActive() ? 1 : 0,
         btLinkState());
  // That closes "r"; rpcFinish adds the envelope's own brace.
  rpcFinish(seq, prefix, used - prefix);
}

// GET /api/boot
static void methodBoot(uint16_t seq, uint32_t id) { rpcSendStr(seq, id, bootinfo_json()); }

// GET /api/odo
static void methodOdoGet(uint16_t seq, uint32_t id) {
  const size_t prefix = rpcBegin(id);
  size_t used = prefix;
  btCatf(s_out, sizeof(s_out) - 1, &used, "{\"km\":%.2f}", odoGet());
  rpcFinish(seq, prefix, used - prefix);
}

// ---------------------------------------------------------------------------
// config.get - the streamed one
// ---------------------------------------------------------------------------
// GET /api/config used to need 25 KB of free heap (a JsonDocument of every key
// plus a 30-40 KB serialized string) and waited up to a second for memory-saver
// mode to make room. Over the link the same keys travel in table order, a
// bounded window per frame, through s_out: no JsonDocument, no heap spike, no
// wait. The app merges the `d` objects and stops at the chunk that says last.
static void methodConfigGet(uint16_t seq, uint32_t id) {
  const int total = cfgTableRowCount();
  int first = 0;
  int chunks = 0;
  int keys = 0;

  // Does the extras chunk (fuelCalOhms, ambientLightValue, core_version,
  // build_version) exist at all? It decides which chunk may claim to be last: an
  // app stops reading at the first chunk that says so, so a row chunk must not
  // say it while the extras are still to come - that is how four settings went
  // missing from a config.get. Staged into scratch here only to learn the size;
  // the real copy is serialized again after the loop, because the row chunks
  // overwrite that part of the buffer.
  size_t probeExtras = 0;
  const bool haveExtras = cfgSerializeExtras(s_out + BT_CHUNK_PREFIX_MAX,
                                             sizeof(s_out) - 1 - BT_CHUNK_PREFIX_MAX,
                                             &probeExtras) && probeExtras > 0;

  while (first < total) {
    // Which chunk is the last one is only known after the rows are packed, and
    // "last" sits in front of the object it describes - so the rows get packed
    // once as a probe (into the same buffer, past the maximum prefix length, so
    // it cannot touch the envelope) and again for real. Two passes over ~40
    // snprintf calls cost less than the buffer arithmetic they replace.
    size_t probeLen = 0;
    int rows = cfgSerializeChunk(s_out + BT_CHUNK_PREFIX_MAX,
                                 sizeof(s_out) - 1 - BT_CHUNK_PREFIX_MAX, first,
                                 BT_CHUNK_ROWS, &probeLen);
    if (rows <= 0) {
      // cfgSerializeChunk already logged it. Advancing keeps a row that is
      // larger than the window from hanging the loop; the app sees the gap in
      // the key count.
      first += BT_CHUNK_ROWS;
      continue;
    }
    const bool last = !haveExtras && (first + rows >= total);
    const size_t prefix = rpcBeginChunk(id);
    size_t used = prefix;
    btCatf(s_out, sizeof(s_out) - 1, &used, "\"i\":%d,\"last\":%s,\"d\":", chunks,
           last ? "true" : "false");
    size_t bodyLen = 0;
    rows = cfgSerializeChunk(s_out + used, sizeof(s_out) - 1 - used, first, rows, &bodyLen);
    if (rows <= 0) {
      rpcErr(seq, id, "chunk_failed");
      return;
    }
    used += bodyLen;
    // Closes the {"id" envelope the body was written inside.
    if (!btCatf(s_out, sizeof(s_out) - 1, &used, "}")) {
      rpcErr(seq, id, "chunk_too_large");
      return;
    }
    // The chunk frames carry the request's seq so the app's reassembler pairs
    // them with the request without a second correlation table.
    if (!btSendMessage(BT_F_CHUNK, seq, s_out, used)) {
      logPrintf("RPC: config chunk %d lost, link down\n", chunks);
      return;
    }
    first += rows;
    keys += rows;
    chunks++;
    if (last) break;
  }

  size_t extrasLen = 0;
  if (cfgSerializeExtras(s_out + BT_CHUNK_PREFIX_MAX, sizeof(s_out) - 1 - BT_CHUNK_PREFIX_MAX,
                         &extrasLen) &&
      extrasLen > 0) {
    const size_t prefix = rpcBeginChunk(id);
    size_t used = prefix;
    btCatf(s_out, sizeof(s_out) - 1, &used, "\"i\":%d,\"last\":true,\"d\":", chunks);
    // memmove, not memcpy: the extras were staged further along the same buffer
    // and the two ranges overlap.
    memmove(s_out + used, s_out + BT_CHUNK_PREFIX_MAX, extrasLen);
    used += extrasLen;
    if (!btCatf(s_out, sizeof(s_out) - 1, &used, "}")) {
      rpcErr(seq, id, "chunk_too_large");
      return;
    }
    if (btSendMessage(BT_F_CHUNK, seq, s_out, used)) {
      keys += 4;  // fuelCalOhms, ambientLightValue, core_version, build_version
      chunks++;
    }
  }

  const size_t prefix = rpcBegin(id);
  size_t used = prefix;
  btCatf(s_out, sizeof(s_out) - 1, &used, "{\"chunks\":%d,\"keys\":%d,\"rows\":%d}", chunks,
         keys, total);
  rpcFinish(seq, prefix, used - prefix);
  logPrintf("RPC: config.get streamed %d chunk(s), %d key(s)\n", chunks, keys);
}

// How much free heap a settings save needs before it will run. Priced on the bench
// (heap plan Phase 2.1) instead of inherited from the WebUI era: the payload copy
// that used to dominate this path cost 11.6 KB on a full 183-key write (33,064 ->
// 21,456 B), and the save itself costs ~1.1 KB over the request document the RPC
// layer had already allocated. 12 KB covers that document (~10 KB for the largest
// write the app sends) plus the NVS session's working set; the old 45,000 threshold
// asked for four times what the path needs and so dropped the display assets on
// every save, which is what the app experienced as a failed write.
static constexpr uint32_t CONFIG_SAVE_HEAP_FLOOR = 12000;

// ---------------------------------------------------------------------------
// config.set
// ---------------------------------------------------------------------------
// The app sends only the keys the user changed, so a settings tweak costs one
// small message rather than a 30 KB round trip. POST /api/config wrote the
// parameters as one group with painting paused, and that part carries over
// unchanged; the weather-refresh request it also fired is gone with the fetch
// loop that consumed it.
static void methodConfigSet(uint16_t seq, uint32_t id, JsonObjectConst p) {
// The save parses an object and rewrites ~90 NVS keys. Since Phase 2.1 of the
// heap-recovery plan it reads the posted keys through a const view of the payload
// the RPC layer already parsed, so there is no second document on this path; the
// gate below is what is left of the old memory-saver handshake.
  unsigned long memT0 = millis();
  // CONFIGSET lines carry the entry heap, the heap the save ended at, and how many
  // 50 ms waits the gate cost - gate G2 of the plan is "zero waits", which is not
  // checkable without this.
  const unsigned long heapEntry = (unsigned long)ESP.getFreeHeap();
  const unsigned long maxAllocEntry = (unsigned long)ESP.getMaxAllocHeap();
  unsigned gateWaits = 0;
  while ((unsigned long)ESP.getFreeHeap() < CONFIG_SAVE_HEAP_FLOOR &&
         (millis() - memT0) < 3000) {
    if (!memSaverRequested) {
      logPrintf("RPC config.set: heap %lu B, requesting memory-saver\n",
                (unsigned long)ESP.getFreeHeap());
      memSaverRequested = true;
    }
    gateWaits++;
    vTaskDelay(pdMS_TO_TICKS(50));
  }

  configSaveStartMs = millis();
  configSaveInProgress = true;
  // No copy: `p` IS the settings object (the request's "p" member), so the root of
  // the {"m","id","p"} envelope is never mistaken for a key the way it would be if
  // the whole request document were handed over.
  processConfigView(2, p);
  const unsigned long heapApplied = (unsigned long)ESP.getFreeHeap();
  recalculateDerivedParams();
  configSaveInProgress = false;
  pendingApplyBusConfig = true;
  pendingCpuReeval = true;

  const size_t prefix = rpcBegin(id);
  size_t used = prefix;
  if (cfgNvsWriteErrors)
    btCatf(s_out, sizeof(s_out) - 1, &used,
           "{\"nvsErrors\":%u,\"nvsFailedKeys\":\"%s\",\"nvsAvailable\":%u}",
           (unsigned)cfgNvsWriteErrors, cfgNvsFailedKeys, (unsigned)nvsStatsAvailable);
  else
    btCatf(s_out, sizeof(s_out) - 1, &used, "{\"nvsErrors\":0}");
  rpcFinish(seq, prefix, used - prefix);

  // The measurement above, in one line. `payload` is the size of the object that is
  // now read in place instead of copied (measureJson of the request's p).
  logPrintf("CONFIGSET keys=%u payload=%u B heap %lu(entry)->%lu(applied) "
            "maxalloc %lu->%lu gate_waits=%u gate_ms=%lums\n",
            (unsigned)p.size(), (unsigned)measureJson(p), heapEntry, heapApplied,
            maxAllocEntry, (unsigned long)ESP.getMaxAllocHeap(), gateWaits,
            (unsigned long)(millis() - memT0));

  // The save asked for the display assets only if the gate actually ran out of
  // room; hand them back here (after the response is flushed) so a single write
  // never leaves the panel on the fallback path until the next reboot.
  memSaverExit();

  // Same as the POST handler: the new geometry, digit counts and colours belong
  // together, so repaint the whole panel once they have all landed.
  forceFullRedraw = true;
  pendingInvertDisplay = true;
  if (!ENABLE_AUTO_BRIGHTNESS) pendingBacklightValue = BACKLIGHT_BRIGHTNESS;
}

// config.reset - one key back to its compiled default.
static void methodConfigReset(uint16_t seq, uint32_t id, JsonVariantConst p) {
  if (!p["key"].is<const char *>()) {
    rpcErr(seq, id, "bad_param");
    return;
  }
  const char *key = p["key"].as<const char *>();
  const int row = cfgFindRow(key);
  if (row < 0 || !cfgResetRow(row)) {
    rpcErr(seq, id, "unknown_key");
    return;
  }
  pendingApplyBusConfig = true;
  pendingCpuReeval = true;
  forceFullRedraw = true;
  logPrintf("RPC: %s reset to its default\n", key);
  const size_t prefix = rpcBegin(id);
  size_t used = prefix;
  btCatf(s_out, sizeof(s_out) - 1, &used, "{\"key\":\"%s\"}", key);
  rpcFinish(seq, prefix, used - prefix);
}

// ---------------------------------------------------------------------------
// Writes the old POST endpoints did
// ---------------------------------------------------------------------------

static void methodOdoSet(uint16_t seq, uint32_t id, JsonVariantConst p) {
  if (!p["km"].is<double>() && !p["km"].is<int64_t>()) {
    rpcErr(seq, id, "bad_param");
    return;
  }
  setOdometerKm(p["km"].as<double>());
  forceFullRedraw = true;
  const size_t prefix = rpcBegin(id);
  size_t used = prefix;
  btCatf(s_out, sizeof(s_out) - 1, &used, "{\"km\":%.2f}", odoGet());
  rpcFinish(seq, prefix, used - prefix);
}

// cal.ambient - {"which":"dark"|"bright"}. POST /api/ambient/cal-dark and
// -cal-bright, merged: the app has one button per calibration step and the
// only difference between the two is which bound it sets.
static void methodCalAmbient(uint16_t seq, uint32_t id, JsonVariantConst p) {
  const char *which = p["which"] | "";
  const bool bright = (strcmp(which, "bright") == 0);
  if (!bright && strcmp(which, "dark") != 0) {
    rpcErr(seq, id, "bad_param");
    return;
  }
  bool adjusted = false;
  if (bright) {
    LIGHT_SENSOR_BRIGHT_VAL = ambientLightValue;
    if (LIGHT_SENSOR_BRIGHT_VAL <= LIGHT_SENSOR_DARK_VAL) {
      if (LIGHT_SENSOR_BRIGHT_VAL <= 0) LIGHT_SENSOR_BRIGHT_VAL = 1;
      LIGHT_SENSOR_DARK_VAL = LIGHT_SENSOR_BRIGHT_VAL - 1;
      adjusted = true;
    }
  } else {
    LIGHT_SENSOR_DARK_VAL = ambientLightValue;
    if (LIGHT_SENSOR_DARK_VAL >= LIGHT_SENSOR_BRIGHT_VAL) {
      if (LIGHT_SENSOR_DARK_VAL >= 4095) LIGHT_SENSOR_DARK_VAL = 4094;
      LIGHT_SENSOR_BRIGHT_VAL = LIGHT_SENSOR_DARK_VAL + 1;
      adjusted = true;
    }
  }
  bool saved = false;
  {
    NvsSession s("cfg", false);
    if (s.opened()) {
      if (bright) {
        saved = !nvsWriteFailed("LIGHT_BRIGHT",
                                s.nvs.putInt("LIGHT_BRIGHT", LIGHT_SENSOR_BRIGHT_VAL));
        if (adjusted && nvsWriteFailed("LIGHT_DARK",
                                       s.nvs.putInt("LIGHT_DARK", LIGHT_SENSOR_DARK_VAL)))
          saved = false;
      } else {
        saved = !nvsWriteFailed("LIGHT_DARK",
                                s.nvs.putInt("LIGHT_DARK", LIGHT_SENSOR_DARK_VAL));
        if (adjusted &&
            nvsWriteFailed("LIGHT_BRIGHT",
                           s.nvs.putInt("LIGHT_BRIGHT", LIGHT_SENSOR_BRIGHT_VAL)))
          saved = false;
      }
    }
  }
  const size_t prefix = rpcBegin(id);
  size_t used = prefix;
  // The calibration is live in RAM either way; say when it did not reach NVS so
  // the app can warn instead of letting the user assume it persisted.
  btCatf(s_out, sizeof(s_out) - 1, &used, "{\"status\":\"%s\",\"value\":%d}",
         saved ? "ok" : "saved-ram-only", bright ? LIGHT_SENSOR_BRIGHT_VAL : LIGHT_SENSOR_DARK_VAL);
  rpcFinish(seq, prefix, used - prefix);
}

// POST /api/time. The phone is the only clock source now, so this is the one
// write that must not fail silently: an unvalidated value put the clock in 1970
// or 2106, which broke the date display and the night-mode window (issue #40).
static void methodTimeSet(uint16_t seq, uint32_t id, JsonVariantConst p) {
  if (!p["timestamp"].is<long long>()) {
    rpcErr(seq, id, "bad_timestamp");
    return;
  }
  const long long epoch = p["timestamp"].as<long long>();
  if (epoch <= 1577836800LL || epoch >= 4102444800LL) {
    logPrintf("RPC time.set rejected: %lld outside 2020..2100\n", epoch);
    rpcErr(seq, id, "timestamp_range");
    return;
  }
  struct timeval tv;
  tv.tv_sec = (time_t)epoch;
  tv.tv_usec = 0;
  settimeofday(&tv, NULL);
  logPrintf("RPC: RTC sync %lld\n", epoch);
  rpcSend(seq, id, "{\"timestamp\":1}");
}

// The weather frame (§4.5): the app fetched Open-Meteo with timeformat=unixtime
// and the firmware turns the two sun times into the local "HH:MM" the panel
// shows. That keeps the widget's clock rule in one place - the same
// TZ_OFFSET_HOURS + TZ_DST_RULE the dashboard clock itself uses - instead of
// trusting whatever offset the phone's network happened to apply.
static void epochToHHMM(long long epoch, char *out, size_t cap) {
  const time_t t = (time_t)epoch;
  struct tm tmUtc = {0};
  if (!gmtime_r(&t, &tmUtc)) {
    snprintf(out, cap, "--:--");
    return;
  }
  int dst = 0;
  if (TZ_DST_ENABLED && TZ_DST_RULE != TZ_DST_RULE_NONE)
    dst = getDstOffset(tmUtc.tm_year + 1900, tmUtc.tm_mon + 1, tmUtc.tm_mday, tmUtc.tm_hour,
                       TZ_OFFSET_HOURS);
  const time_t local = t + (time_t)(TZ_OFFSET_HOURS + dst) * 3600;
  struct tm tmLocal = {0};
  if (!gmtime_r(&local, &tmLocal)) {
    snprintf(out, cap, "--:--");
    return;
  }
  snprintf(out, cap, "%02d:%02d", tmLocal.tm_hour, tmLocal.tm_min);
}

// weather.set. Writing g_weatherData under g_stateMutex matches the fetch task
// that used to own it: the display reads these fields unlocked, and the
// fixed-size arrays are only safe because every writer leaves a NUL inside the
// bounds (issue #8).
static void methodWeatherSet(uint16_t seq, uint32_t id, JsonVariantConst p) {
  if (!p["temperature"].is<float>() && !p["temperature"].is<int64_t>()) {
    rpcErr(seq, id, "bad_param");
    return;
  }
  char sunrise[8], sunset[8], city[48];
  // A missing sun time becomes "--:--" rather than an empty string: the panel
  // prints whatever is in the field, and an empty one draws nothing at all.
  if (p["sunrise"].is<long long>()) epochToHHMM(p["sunrise"].as<long long>(), sunrise, sizeof(sunrise));
  else snprintf(sunrise, sizeof(sunrise), "--:--");
  if (p["sunset"].is<long long>()) epochToHHMM(p["sunset"].as<long long>(), sunset, sizeof(sunset));
  else snprintf(sunset, sizeof(sunset), "--:--");
  copyFixed(city, sizeof(city), p["city"] | "");

  if (xSemaphoreTake(g_stateMutex, pdMS_TO_TICKS(100)) != pdTRUE) {
    rpcErr(seq, id, "busy");
    return;
  }
  g_weatherData.temperature = p["temperature"].as<float>();
  g_weatherData.humidity = (int)(p["humidity"] | 0);
  g_weatherData.windSpeed = p["windSpeed"] | 0.0f;
  g_weatherData.windDirection = p["windDirection"] | 0.0f;
  g_weatherData.weatherCode = (int)(p["weatherCode"] | 0);
  g_weatherData.cloudCover = (int)(p["cloudCover"] | 0);
  memcpy(g_weatherData.sunriseTime, sunrise, sizeof(g_weatherData.sunriseTime));
  memcpy(g_weatherData.sunsetTime, sunset, sizeof(g_weatherData.sunsetTime));
  // An empty label leaves the previous one in place - the app is the one that
  // knows the city now, and WEATHER_CITY stays a setting the user edits.
  if (city[0]) memcpy(g_weatherData.cityName, city, sizeof(g_weatherData.cityName));
  g_weatherData.valid = (bool)(p["valid"] | true);
  g_weatherData.lastUpdated = millis();
  xSemaphoreGive(g_stateMutex);

  logPrintf("RPC: weather %.1fC %d%%, sun %s-%s\n", (double)g_weatherData.temperature,
            g_weatherData.humidity, sunrise, sunset);
  const size_t prefix = rpcBegin(id);
  size_t used = prefix;
  btCatf(s_out, sizeof(s_out) - 1, &used, "{\"sunrise\":\"%s\",\"sunset\":\"%s\",\"valid\":%d}",
         g_weatherData.sunriseTime, g_weatherData.sunsetTime, g_weatherData.valid ? 1 : 0);
  rpcFinish(seq, prefix, used - prefix);
}

// ---------------------------------------------------------------------------
// The live log
// ---------------------------------------------------------------------------
// /api/serial kept a window of everything logged since the last read and
// advanced the tail when it served it, so a polling client never re-received a
// line. Same contract here. The copy is capped at BT_LOG_TAIL rather than the
// whole ring: logMux is a critical section, and a 4 KB copy inside it is what
// the old endpoint did on the web task.
static void methodLogGet(uint16_t seq, uint32_t id) {
  static char tail[BT_LOG_TAIL + 1];  // one response at a time (rule 14)
  int len = 0;
  int total = 0;
  bool droppedOlder = false;
  portENTER_CRITICAL(&logMux);
  {
    const int head = logHead;
    const int first = logTail;
    int avail = (head >= first) ? (head - first) : (LOG_BUF_SIZE - first + head);
    total = avail;
    if (avail > BT_LOG_TAIL) {
      droppedOlder = true;
      avail = BT_LOG_TAIL;
    }
    // The window a polling client wants is the NEWEST avail bytes, which end at
    // head - the old endpoint sent from logTail forward and let a slow client
    // fall behind by a whole ring.
    int start = head - avail;
    if (start < 0) start += LOG_BUF_SIZE;
    const int n1 = LOG_BUF_SIZE - start;
    if (avail > 0) {
      if (n1 >= avail) {
        memcpy(tail, &logBuf[start], (size_t)avail);
      } else {
        memcpy(tail, &logBuf[start], (size_t)n1);
        memcpy(tail + n1, logBuf, (size_t)(avail - n1));
      }
      len = avail;
    }
    logTail = head;  // acknowledged, including anything older than the window
  }
  portEXIT_CRITICAL(&logMux);
  tail[len] = 0;
  if (droppedOlder)
    logPrintf("Log tail: %d of %d B older than the %d B window not sent\n",
              total - len, total, BT_LOG_TAIL);

  // JSON quoting is done while filling the response, not in a second buffer:
  // the escape can grow the text sixfold (every control byte becomes \u00xx),
  // so the response says how far it got instead of pretending the tail was
  // complete.
  const size_t prefix = rpcBegin(id);
  size_t used = prefix;
  btCatf(s_out, sizeof(s_out) - 1, &used, "{\"text\":\"");
  bool truncated = false;
  size_t i = 0;
  for (; i < (size_t)len; i++) {
    const unsigned char c = (unsigned char)tail[i];
    if (c == '"' || c == '\\') {
      if (used + 3 > sizeof(s_out) - 1) { truncated = true; break; }
      s_out[used++] = '\\';
      s_out[used++] = (char)c;
    } else if (c == '\n') {
      if (used + 3 > sizeof(s_out) - 1) { truncated = true; break; }
      s_out[used++] = '\\';
      s_out[used++] = 'n';
    } else if (c == '\r') {
      if (used + 3 > sizeof(s_out) - 1) { truncated = true; break; }
      s_out[used++] = '\\';
      s_out[used++] = 'r';
    } else if (c == '\t') {
      if (used + 3 > sizeof(s_out) - 1) { truncated = true; break; }
      s_out[used++] = '\\';
      s_out[used++] = 't';
    } else if (c < 0x20) {
      if (used + 7 > sizeof(s_out) - 1) { truncated = true; break; }
      used += (size_t)snprintf(s_out + used, 7, "\\u%04x", c);
    } else {
      if (used + 2 > sizeof(s_out) - 1) { truncated = true; break; }
      s_out[used++] = (char)c;
    }
  }
  bool ok = btCatf(s_out, sizeof(s_out) - 1, &used,
                   "\",\"bytes\":%u,\"total\":%d,\"dropped\":%d,\"truncated\":%s}",
                   (unsigned)i, total, total - len, truncated ? "true" : "false");
  if (!ok) {
    // The tail itself filled the buffer: send what is there and close the
    // object by hand rather than losing the lines that did fit.
    s_out[used++] = '"';
    s_out[used++] = '}';
    s_out[used] = 0;
    truncated = true;
  }
  rpcFinish(seq, prefix, used - prefix);
}

// ---------------------------------------------------------------------------
// Dispatcher
// ---------------------------------------------------------------------------

void btRpcHandle(const char *msg, size_t len, uint16_t seq) {
  JsonDocument doc;
  DeserializationError err = deserializeJson(doc, msg, len);
  if (err) {
    logPrintf("RPC: parse error: %s\n", err.c_str());
    rpcErr(seq, 0, "bad_json");
    return;
  }
  const char *m = doc["m"] | "";
  // The envelope carries the app's own request id; the frame seq is what the
  // reassembler correlates on, and they are the same number in practice. When
  // a client omits "id" the answer falls back to the seq, so correlation never
  // depends on the optional field.
  const uint32_t id = (uint32_t)(doc["id"].is<long>() ? doc["id"].as<long>() : (long)seq);
  JsonVariantConst p = doc["p"];

  // While an install is open the unit has one job. Everything that is not about
  // that install is refused with a word the app already maps to a sentence, and
  // the dangerous ones matter most: a config.set writes NVS, and an NVS write
  // suspends flash-resident code on both cores while the OTA slot is being
  // programmed. `ping` stays answered so a client can still ask "are you
  // alive?" while 1.5 MB go past, and ota.* stays answered because that is the
  // install's own control plane.
  if (btOtaActive() && strcmp(m, "ping") && strncmp(m, "ota.", 4) != 0) {
    logPrintf("RPC: '%s' refused, a firmware install is open\n", m);
    rpcErr(seq, id, "ota_in_progress");
    return;
  }

  if (!strcmp(m, "ping")) {
    const size_t prefix = rpcBegin(id);
    size_t used = prefix;
    btCatf(s_out, sizeof(s_out) - 1, &used, "{\"up\":%lu,\"heap\":%lu}",
           (unsigned long)(millis() / 1000), (unsigned long)ESP.getFreeHeap());
    rpcFinish(seq, prefix, used - prefix);
    return;
  }
  if (!strcmp(m, "snapshot")) return methodSnapshot(seq, id);
  if (!strcmp(m, "live")) return methodLive(seq, id);
  if (!strcmp(m, "health")) return methodHealth(seq, id);
  if (!strcmp(m, "perf")) return methodPerf(seq, id);
  if (!strcmp(m, "boot")) return methodBoot(seq, id);
  if (!strcmp(m, "sensors")) return methodSensors(seq, id);
  if (!strcmp(m, "fuel")) return methodFuel(seq, id);
  if (!strcmp(m, "ambient")) return methodAmbient(seq, id);
  if (!strcmp(m, "odo.get")) return methodOdoGet(seq, id);
  if (!strcmp(m, "noise")) return methodNoise(seq, id, p);
  if (!strcmp(m, "log.get")) return methodLogGet(seq, id);
  if (!strcmp(m, "config.get")) return methodConfigGet(seq, id);

  if (!strcmp(m, "config.set")) {
    if (!p.is<JsonObjectConst>()) {
      rpcErr(seq, id, "bad_param");
      return;
    }
    return methodConfigSet(seq, id, p.as<JsonObjectConst>());
  }
  if (!strcmp(m, "config.reset")) return methodConfigReset(seq, id, p);
  if (!strcmp(m, "odo.set")) return methodOdoSet(seq, id, p);
  if (!strcmp(m, "cal.ambient")) return methodCalAmbient(seq, id, p);
  if (!strcmp(m, "time.set")) return methodTimeSet(seq, id, p);
  if (!strcmp(m, "weather.set")) return methodWeatherSet(seq, id, p);

#ifdef BT_CFG_VERIFY
  // The phase 2a bench driver, run on demand rather than on a timer: a central
  // that has just paired and subscribed can then watch it work over the live
  // encrypted link and count the same bytes from its own side, which is the
  // comparison that means something. An automatic start would fire on the
  // pairing connection, before anyone is listening.
  if (!strcmp(m, "bench.run")) {
    btRunSelfTest();
    return rpcSend(seq, id, "{\"ran\":true}");
  }
#endif

  if (!strcmp(m, "trip.reset")) {
    pendingTripReset = true;
    return rpcSend(seq, id, "{\"status\":\"ok\"}");
  }
  if (!strcmp(m, "reboot")) {
    // Answered first - the app has no way to tell a completed reboot from a
    // dead link otherwise, and the unit reappears on the advertising list
    // within a few seconds.
    rpcSend(seq, id, "{\"status\":\"ok\"}");
    logPrintf("RPC: reboot requested\n");
    pendingReboot = true;
    return;
  }
  if (!strcmp(m, "sleep")) {
    rpcSend(seq, id, "{\"status\":\"ok\"}");
    pendingSleep = true;
    return;
  }
  if (!strcmp(m, "factory.reset")) {
    rpcSend(seq, id, "{\"status\":\"ok\"}");
    factoryResetConfig();
    // Bonds live in their own NVS namespace, so wiping `cfg` does not reach
    // them - and leaving them would lock the next owner out, because every
    // characteristic here demands an encrypted link.
    btClearBonds();
    logPrintf("RPC: factory reset, rebooting\n");
    pendingReboot = true;
    return;
  }
  // ---------------------------------------------------------------------------
  // The install (src/bt_ota.cpp owns the state; these four are its control plane)
  //
  // The image bytes never travel through here - they go to the OTA
  // characteristic as offset(4) + payload. These calls open the slot, finish it,
  // throw it away, and ask where a half-finished one stands.
  // ---------------------------------------------------------------------------
  if (!strcmp(m, "ota.begin")) {
    // A crc32 travels as a plain decimal (see ble/OtaCore.kt OtaJson), which
    // needs the 64-bit read: 4294967295 does not fit the signed long ArduinoJson
    // hands back by default, and reading it wrong would look like a corrupted
    // transfer at the very end of the run.
    const uint32_t size = (uint32_t)p["size"].as<uint64_t>();
    const uint32_t crc32 = (uint32_t)p["crc32"].as<uint64_t>();
    uint32_t off = 0, chunk = 0;
    const char *why = nullptr;
    if (!btOtaBegin(size, crc32, &off, &chunk, &why)) {
      logPrintf("RPC: ota.begin refused (%lu B, crc %08lx): %s\n", (unsigned long)size,
                (unsigned long)crc32, why ? why : "?");
      return rpcErr(seq, id, why ? why : "update_begin_failed");
    }
    const size_t prefix = rpcBegin(id);
    size_t used = prefix;
    btCatf(s_out, sizeof(s_out) - 1, &used, "{\"off\":%lu,\"chunk\":%lu}", (unsigned long)off,
           (unsigned long)chunk);
    rpcFinish(seq, prefix, used - prefix);
    return;
  }
  if (!strcmp(m, "ota.end")) {
    const char *why = nullptr;
    uint32_t off = 0;
    if (!btOtaEnd(&why, &off)) return rpcErr(seq, id, why ? why : "end_failed");
    const size_t prefix = rpcBegin(id);
    size_t used = prefix;
    btCatf(s_out, sizeof(s_out) - 1, &used, "{\"verified\":true,\"off\":%lu}", (unsigned long)off);
    rpcFinish(seq, prefix, used - prefix);
    return;
  }
  if (!strcmp(m, "ota.abort")) {
    const char *why = nullptr;
    if (!btOtaAbort(&why)) return rpcErr(seq, id, why ? why : "no_session");
    return rpcSend(seq, id, "{\"aborted\":true}");
  }
  if (!strcmp(m, "ota.status")) {
    // The question a phone asks when it is not sure what the unit has - the ACK
    // that carried the answer may well have been in the air when the link went
    // away. Everything in the answer is something the app can use to pick up
    // mid-file: where the unit is, how big the file was said to be, and how long
    // it has been since it last heard anything.
    char extra[96];
    uint32_t off = 0, size = 0;
    bool active = false;
    const char *stage = "off";
    extra[0] = 0;
    btOtaStatus(extra, sizeof(extra), &off, &size, &active, &stage);
    const size_t prefix = rpcBegin(id);
    size_t used = prefix;
    btCatf(s_out, sizeof(s_out) - 1, &used,
           "{\"active\":%s,\"off\":%lu,\"size\":%lu,\"stage\":\"%s\"%s%s}",
           active ? "true" : "false", (unsigned long)off, (unsigned long)size, stage,
           extra[0] ? "," : "", extra);
    rpcFinish(seq, prefix, used - prefix);
    return;
  }

  logPrintf("RPC: unknown method '%s'\n", m);
  rpcErr(seq, id, "unknown_method");
}

// ---------------------------------------------------------------------------
// Bench driver - compiled only with -DBT_CFG_VERIFY
// ---------------------------------------------------------------------------
// Phase 2a's config stream has to be proven before there is an app that reads
// it, and the plan's verification line is "config.get returns every config key
// and SET mirrors back changed values". So this runs the real request path on
// the unit: every read-only method through the dispatcher, then the full
// config.get, with btVerifyFrame() watching each frame as it leaves and merging
// the chunks the way the app's ConfigRepository will. The merged mirror is then
// checked key by key against the config table, one setting is changed through
// config.set, and the stream runs again to see the new value come back.
//
// It waits for a real central before starting, so the frames travel over the
// air and the 1 Hz telemetry keeps running while it goes: the heap numbers it
// logs are the ones a working unit has. The log is the measurement, and the
// harness reads it over USB - there is no serial-monitor step to remember.
#ifdef BT_CFG_VERIFY

static JsonDocument *s_vAcc = nullptr;
static int s_vChunks = 0, s_vChunkKeys = 0, s_vRespOk = 0, s_vRespBad = 0;
static int s_vTelem = 0, s_vTelemBad = 0;
static size_t s_vMin = 0, s_vMax = 0;
static uint32_t s_vBytes = 0;

// Called from bt.cpp for every frame the BLE task puts on the wire, before the
// notification is attempted - so a bench run with no central attached still
// measures the bytes. Parsing here is what catches hand-built JSON that is not
// valid JSON, which is the failure mode printf-built responses have.
void btVerifyFrame(uint16_t type, uint16_t seq, const char *msg, size_t len) {
  (void)seq;
  if (type == BT_F_TELEM) {
    JsonDocument d;
    s_vTelem++;
    if (deserializeJson(d, msg, len)) s_vTelemBad++;
    return;
  }
  if (type == BT_F_RESP) {
    JsonDocument d;
    if (deserializeJson(d, msg, len)) {
      s_vRespBad++;
      logPrintf("CFGVERIFY: response (%u B) is not valid JSON\n", (unsigned)len);
      return;
    }
    if (d["ok"].as<bool>()) s_vRespOk++;
    else {
      s_vRespBad++;
      logPrintf("CFGVERIFY: method rejected: %s\n", d["e"] | "?");
    }
    return;
  }
  if (type != BT_F_CHUNK) return;

  JsonDocument d;
  if (deserializeJson(d, msg, len)) {
    s_vRespBad++;
    logPrintf("CFGVERIFY: chunk %d is not valid JSON (%u B)\n", s_vChunks, (unsigned)len);
    return;
  }
  int keys = 0;
  for (JsonPairConst kv : d["d"].as<JsonObjectConst>()) {
    keys++;
    if (s_vAcc) (*s_vAcc)[kv.key().c_str()] = kv.value();
  }
  if (s_vChunks == 0 || len < s_vMin) s_vMin = len;
  if (len > s_vMax) s_vMax = len;
  s_vChunks++;
  s_vChunkKeys += keys;
  s_vBytes += (uint32_t)len;
  logPrintf("CFGVERIFY: chunk %d i=%d last=%s keys=%d len=%u heap=%lu\n", s_vChunks - 1,
            (int)(d["i"] | -1), d["last"].as<bool>() ? "yes" : "no", keys, (unsigned)len,
            (unsigned long)ESP.getFreeHeap());
}

// Streams the config and merges it into acc the way the app does.
static void verifyConfigMirror(JsonDocument &acc, const char *tag) {
  s_vChunks = 0;
  s_vChunkKeys = 0;
  s_vMin = 0;
  s_vMax = 0;
  s_vBytes = 0;
  s_vAcc = &acc;
  methodConfigGet(0xBEEF, 0xBEEF);
  s_vAcc = nullptr;
  logPrintf("CFGVERIFY: %s: %d chunk(s), %d key(s), %lu B (min %u, max %u)\n", tag, s_vChunks,
            s_vChunkKeys, (unsigned long)s_vBytes, (unsigned)s_vMin, (unsigned)s_vMax);
}

void btRunSelfTest() {
  const uint32_t heapStart = ESP.getFreeHeap();
  const int rows = cfgTableRowCount();
  logPrintf("CFGVERIFY: link up - heap %lu B, config table %d row(s)\n",
            (unsigned long)heapStart, rows);

  // 1. Every read-only method, through the dispatcher, with real values.
  static const char *kMethods[] = {"snapshot", "live",   "health", "perf",  "boot",    "sensors",
                                   "fuel",     "ambient", "odo.get", "noise", "log.get"};
  uint16_t seq = 0x100;
  for (const char *m : kMethods) {
    char req[48];
    snprintf(req, sizeof(req), "{\"m\":\"%s\",\"id\":%u}", m, (unsigned)seq);
    const int before = s_vRespBad;
    btRpcHandle(req, strlen(req), seq++);
    if (s_vRespBad > before) logPrintf("CFGVERIFY: %s did not answer cleanly\n", m);
    vTaskDelay(pdMS_TO_TICKS(20));
  }
  logPrintf("CFGVERIFY: %d method call(s): %d ok, %d bad\n", (int)(sizeof(kMethods) / sizeof(kMethods[0])),
            s_vRespOk, s_vRespBad);

  // 2. The full config stream, merged and checked against the table.
  JsonDocument acc;
  verifyConfigMirror(acc, "config.get");
  const JsonObjectConst mirrored = acc.as<JsonObjectConst>();
  int missing = 0;
  for (int i = 0; i < rows; i++) {
    // isNull() on a const view is the non-deprecated way to ask "is this key
    // here at all" - a config value is never JSON null, so a null answer means
    // the mirror never saw the row.
    if (mirrored[cfgRowKey(i)].isNull()) {
      if (missing < 6) logPrintf("CFGVERIFY: mirror has no %s\n", cfgRowKey(i));
      missing++;
    }
  }
  logPrintf("CFGVERIFY: %d of %d table key(s) mirrored, %d missing\n", rows - missing, rows,
            missing);

  // 3. SET has to land where GET can see it. TZ_OFFSET_HOURS is the inert
  // choice: a plain integer that survives the JSON round trip without escaping,
  // and the value is written back the moment the check is done.
  const int tzWas = acc["TZ_OFFSET_HOURS"] | 1;
  acc.clear();
  {
    char req[96];
    snprintf(req, sizeof(req), "{\"m\":\"config.set\",\"id\":9001,\"p\":{\"TZ_OFFSET_HOURS\":7}}");
    btRpcHandle(req, strlen(req), 9001);
  }
  verifyConfigMirror(acc, "after set");
  const int tzNow = acc["TZ_OFFSET_HOURS"] | -999;
  logPrintf("CFGVERIFY: config.set %s - TZ_OFFSET_HOURS %d -> %d\n",
            (tzNow == 7) ? "mirrored" : "FAILED", tzWas, tzNow);
  acc.clear();
  {
    char req[96];
    snprintf(req, sizeof(req), "{\"m\":\"config.set\",\"id\":9002,\"p\":{\"TZ_OFFSET_HOURS\":%d}}", tzWas);
    btRpcHandle(req, strlen(req), 9002);
  }
  verifyConfigMirror(acc, "restore");
  logPrintf("CFGVERIFY: TZ_OFFSET_HOURS restored to %d\n", acc["TZ_OFFSET_HOURS"] | -999);

  logPrintf("CFGVERIFY: done. heap %lu B now (was %lu at start), low-water since boot %lu; "
            "telemetry %d frame(s) %d bad; frames %d ok %d bad\n",
            (unsigned long)ESP.getFreeHeap(), (unsigned long)heapStart,
            (unsigned long)ESP.getMinFreeHeap(), s_vTelem, s_vTelemBad, s_vRespOk, s_vRespBad);
}
#endif

