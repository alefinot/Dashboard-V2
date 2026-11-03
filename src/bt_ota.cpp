// BLE firmware install - the phase 2b state machine.
//
// A phone pushes a firmware image into the *other* OTA slot over the encrypted
// GATT link, the unit folds a CRC-32 as it goes, and only when the whole image
// matches does it flip otadata and reboot. Nothing here authenticates the file
// (AGENTS.md 3: no signing, anywhere): the CRC-32 says "the bytes that arrived
// are the bytes that left", and esp_ota_end()'s own magic/checksum/SHA gate says
// "what arrived is an image this bootloader can run". A failed or interrupted
// install leaves the running slot untouched, which is the one promise the whole
// design exists to keep.
//
// Three tasks are involved, and the split is deliberate:
//
//   Bluedroid callback task  OTA characteristic writes land here. It does ONE
//                            thing: check the frame and append its bytes to a FIFO,
//                            then return. No flash work, no JSON, no notify - a
//                            long onWrite here stalls the whole GATT stack,
//                            including the writes still arriving.
//   BtTask (core 0, 8 KB)    the RPC handlers (ota.begin / end / abort / status)
//                            AND btOtaPoll(): take the FIFO to flash in one write
//                            per pass, fold the CRC, send the ACKs, watch the
//                            clocks. Every state change happens on this one task,
//                            so there is no lock between the control plane and the
//                            data path.
//   Display task (core 1)    owns the panel. This file only sets the flags the
//                            loop already understands (otaUpdateInProgress,
//                            otaScreenShowUpdate, updateOTAProgress,
//                            otaProgressTarget = 258 for the reboot frame), and
//                            the loop draws.
//
// The wire shape (settled in Implementation plans/BLE-OTA-PHASE-2b-2026-11-02.md
// §6.1, and already implemented on the phone in ble/OtaCore.kt):
//
//   phone -> unit  RPC  ota.begin {"size":N,"crc32":N}   reply {"off":N,"chunk":N}
//   phone -> unit  OTA characteristic, raw:              offset(4, LE) + payload
//   unit  -> phone BT_F_OTA on the RPC characteristic:  {"ev":"begin_ok",...},
//                                                        {"ev":"ack","off":N},
//                                                        {"ev":"done"} |
//                                                        {"ev":"error","reason":"..","off":N}
//   phone -> unit  RPC  ota.end / ota.status / ota.abort
//
// Memory rules (AGENTS.md 14): one static byte FIFO (4 KB), one static detail
// string, nothing allocated per chunk, no per-chunk JSON beyond a ~40 byte body
// built on the stack.

#include "dashboard.h"
#include "bt_proto.h"

#include <esp_ota_ops.h>
#include <esp_partition.h>

#include <string.h>

// ---------------------------------------------------------------------------
// Limits
// ---------------------------------------------------------------------------
// The install is thrown away this long after the last accepted byte. This is not
// a technical limit - it is the time it takes a driver to notice a pause, unlock a
// tablet, open the app and reach the firmware card. Two minutes measured out on
// the bench on 2026-11-02: at 60 s the unit closed an install (`stalled` at
// 162192/1590912 B) while the phone was still showing it as resumable, and nobody
// had a chance to press RESUME. The phone's own `RESUME_WINDOW_MS` carries the
// same number so the card's wording stays true. Longer than this and a unit that
// has been walked away from sits parked behind an update screen for too long.
static const unsigned long OTA_SILENCE_MS = 120000UL;
// A ceiling on the whole install, for a client that opens a session and then
// forgets to send ota.end: the unit must not stay parked forever.
static const unsigned long OTA_MAX_MS = 15UL * 60UL * 1000UL;
// How much accepted data between ACKs. The ACK is the phone's resume token, so it
// also bounds how much has to be re-sent after a drop. 4096 B is the same order of
// magnitude as the batch the drain writes flash in (one esp_ota_write per ~2 KB of
// the handoff FIFO, see OTA_WRITE_TARGET), and the cap computed in btOtaBegin() is
// half the handoff buffer - so the offset a phone resumes from is a boundary the
// unit has really written to, never a point that only ever existed in RAM.
//
// What this number is NOT: the phone's in-flight allowance. The unit must not read
// it that way, because ble/OtaController.kt lets the phone run OUTSTANDING_BYTES
// (32 KB) past the last ACK it was given - eight times this unit's whole handoff
// FIFO. What holds the air rate near the drain rate is the link's own flow
// control (write-without-response, one write completing before the next is issued);
// the FIFO's depth is the cushion for the stretches where that still leaves the
// unit behind, see under OTA_FIFO_BYTES.
//
// The old 16 KB cadence was two problems at once: four times the ring of its day,
// and an ACK of up to 14 KB that had only ever been seen in RAM - after a drop the
// phone resumed from an offset the unit had never put on flash.
static const uint32_t OTA_ACK_BYTES = 4096;
// An app slot in partitions.csv (0x1D0000). Larger than this cannot fit, and
// the build gate (1.80 MB) exists so a normal build never asks.
static const uint32_t OTA_MAX_IMAGE = 0x1D0000u;
// Smaller than this is not firmware, it is a truncated download or a text file.
static const uint32_t OTA_MIN_IMAGE = 4096u;

// ---------------------------------------------------------------------------
// The handoff buffer: a byte FIFO, not a ring of frames
// ---------------------------------------------------------------------------
// Until 2026-11-04 this was a ring of 8 frames (8 x 516 B) and the drain wrote
// flash one frame at a time. The bench counted what that shape costs (install-speed
// plan, B1): 3 214 esp_ota_write calls for a 1.52 MB image, mean 3 108 us each,
// 9 989 ms of the install spent inside them. Only part of that is programming the
// flash - the rest is the per-call cost of taking the flash lock and switching the
// cache off on the other core, and on an ESP32 that window also silences the BT
// controller sharing this core with BtTask, which is why the same install showed a
// 105 ms drain gap and a 135 ms arrival gap. (An earlier note here blamed a per-
// sector erase inside the writes; measured with the erase accounted for separately,
// that is not what the numbers say.)
//
// A byte FIFO lets the drain take everything that has arrived since its last pass
// in ONE write: the same bytes in the same order, with roughly a quarter of the
// flash windows and of the radio holes. Frames stop being the unit of storage - the
// 4-byte offset prefix is checked as the frame arrives, when the producer knows
// exactly what the next byte of the image must be, and is then dropped.
//
// RAM (rule 14): 4 096 B for the FIFO replaces 4 128 B of frame slots plus 16 B of
// per-slot lengths, so this is slightly LESS memory than what it replaces. Depth is
// marginally better too: 4 096 B holds 8.3 frames at the negotiated 492 B payload,
// against the 7 usable slots it replaces.
//
// Depth still matters: nothing about the ACK cadence protects this buffer (the
// phone may run OUTSTANDING_BYTES - 32 KB, ble/OtaController.kt - past the last ACK
// it was given), and the batching policy deliberately lets bytes sit here while a
// write's worth accumulates. A FIFO that does fill says so out loud - `bad_frame`
// with "fifo full", the depth reached and the drain statistics - rather than being
// reported as a malformed frame and chased as a phone bug.
static const int OTA_FIFO_BYTES = 4096;  // power of two: the indices mask, no divide
static const int OTA_FIFO_MASK = OTA_FIFO_BYTES - 1;

// The batching policy, in two numbers. The drain writes flash when this many bytes
// are waiting; if they have not arrived yet it waits for the next pass, unless the
// FIFO has stopped growing (the phone is quiet), a batch has been waiting
// OTA_WRITE_WAIT_MS, or the buffer is nearly full. At the measured air rate - one
// 492 B payload every ~24 ms - reaching 2 KB takes ~100 ms of arrivals and 40 ms of
// waiting is ~820 B, both comfortably inside the 4 KB buffer, and the phone's
// progress bar is fed from an ACK every ~2 KB, which is finer than the old 1,736 B.
static const uint32_t OTA_WRITE_TARGET = 2048;
static const unsigned long OTA_WRITE_WAIT_MS = 60;
// Pressure: if this much of the FIFO is full, write now whatever is in it.
static const uint16_t OTA_FIFO_PRESSURE = OTA_FIFO_BYTES - OTA_FIFO_BYTES / 4;

// The largest OTA frame this link can carry: a 4-byte offset prefix plus the
// largest payload one ATT write holds at the maximum ATT MTU (517 -> 512). Anything
// longer is refused at arrival and reported (s_faultShape).
static const int OTA_MAX_FRAME = 4 + 512;

// ---------------------------------------------------------------------------
// CRC-32, written out rather than borrowed
// ---------------------------------------------------------------------------
// This must match java.util.zip.CRC32 (the standard reflected CRC-32, the one
// `crc32` on the command line prints) byte for byte, or every install dies at
// the last metre with a corrupted-air complaint. The ROM's esp_crc32_le() uses
// the same polynomial but its seed/chaining convention is not spelled out in
// this core's headers, and guessing wrong costs an evening of bench time - so
// the loop is here, unrolled-free and readable, in the same domain zlib and
// Java use: the stored value is the *final-xored* one, so entering and leaving
// the raw loop complements it.
static uint32_t otaCrcRaw(uint32_t crc, const uint8_t *p, size_t n) {
  while (n--) {
    crc ^= *p++;
    for (int i = 0; i < 8; i++)
      crc = (crc >> 1) ^ (0xEDB88320u & (uint32_t)-(int32_t)(crc & 1u));
  }
  return crc;
}

// Chains `stored` (a final-xored CRC) with `len` more bytes and returns the new
// stored value.
static uint32_t otaCrcChain(uint32_t stored, const uint8_t *p, size_t len) {
  return ~otaCrcRaw(~stored, p, len);
}

// The standard check value for "123456789" is 0xCBF43926. Cheap insurance that a
// future edit to the loop above (or to the complements around it) cannot turn
// every install into a phantom CRC failure: this runs once per install, over nine
// bytes, and only speaks when it disagrees.
static bool otaCrcSelfCheck() {
  return otaCrcChain(0u, (const uint8_t *)"123456789", 9) == 0xCBF43926u;
}

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------
// An install is open: the slot is (or was) claimed, the CRC is folding, resume
// is possible. Cleared when the image is accepted, when it is refused, or when
// the silence window expires.
static bool s_active = false;
// esp_ota_begin() has a live handle right now, so bytes are going into flash.
// This is what otaFlashWriteActive() reports, and what makes the hold-to-restart
// gesture refuse to fire (a reset between two writes is a half-written slot).
static bool s_sessionOpen = false;
// The image was accepted and otadata points at the new slot. The unit is waiting
// for the display task to finish its bar and reboot; no clock may expire here,
// and a second ota.end must not re-verify anything.
static bool s_acceptedImage = false;

static esp_ota_handle_t s_handle = 0;
static const esp_partition_t *s_slot = nullptr;

static uint32_t s_size = 0;          // declared image size
static uint32_t s_crcDeclared = 0;   // what the phone says the image is
static uint32_t s_crcStored = 0;     // what the unit has seen so far (final-xored)
static uint32_t s_accepted = 0;      // bytes accepted == the next expected offset
static uint32_t s_ackAt = 0;         // accepted when the last ACK went out
static uint32_t s_chunk = 0;         // what this link can carry per write

// The silence clock lives in main.cpp as otaQuietLastByteMs (it is the same
// quantity the quiet window was built around); this file stamps it on every
// accepted byte and reads it to decide when to give up.
static unsigned long s_openedMs = 0;

// The offset the next arriving frame must carry. Owned by the producer (only
// btOtaFeed writes it) so the arrival-time offset check cannot race with the drain:
// it advances by exactly the payload bytes appended. It agrees with the unit's
// written count whenever the FIFO is empty, which is where a phone that dropped the
// link and came back resumes from (ota.status answers `off` = s_accepted, and the
// FIFO empties in one 10 ms BtTask pass while a reconnect takes seconds). Nothing
// here reads BtTask's bookkeeping.
static uint32_t s_feedExpect = 0;

static char s_reason[24] = "";
static char s_detail[96] = "";

// The Bluedroid task appends bytes here; BtTask takes them to flash. Single
// producer, single consumer, and the two indices are byte counts that only ever
// grow: because OTA_FIFO_BYTES divides 2^16, (uint16_t)(head - tail) is the exact
// number of bytes waiting however many times they have wrapped. Both are volatile
// because each task reads the other's, and each task writes only its own.
static uint8_t s_fifo[OTA_FIFO_BYTES];
static volatile uint16_t s_fifoHead = 0;  // producer (Bluedroid): bytes handed over
static volatile uint16_t s_fifoTail = 0;  // consumer (BtTask): bytes written to flash

static inline uint16_t fifoPending() { return (uint16_t)(s_fifoHead - s_fifoTail); }

// Bytes that arrived on the OTA characteristic with no install open: dropped, but
// counted and reported once, because they mean something on the link is writing
// when it was never invited to.
static volatile bool s_strayLogged = false;
static uint32_t s_strayBytes = 0;

// Set by the producer when a frame cannot be taken: the wrong shape, no room left
// in the FIFO, or an offset that is not the next byte of the image. Recorded with
// plain stores on the callback task, reported (and the install failed) in
// btOtaDrain, where the error path is allowed to talk.
static volatile bool s_faultPending = false;
static volatile bool s_faultShape = false;   // len < 5 or len > OTA_MAX_FRAME
static volatile bool s_faultFifo = false;    // arrived with no room for it
static volatile bool s_faultOffset = false;  // not the offset the image is at
static volatile uint16_t s_overLen = 0;      // length of the refused frame
static volatile uint32_t s_offGot = 0;       // the offset it carried
static volatile uint32_t s_offWant = 0;      // and what the unit expected
static volatile uint16_t s_fifoHigh = 0;     // high-water bytes waiting, this install
static unsigned long s_lastDrainMs = 0;      // BtTask: last pass that served the FIFO
static unsigned long s_drainGapMaxMs = 0;    // longest pause between those passes

// BtTask's own view of the batch it is building - read and written only by the
// drain, so it needs no sharing with the producer. `s_batchPend` is how many bytes
// were waiting at the previous pass: if that number did not change, the phone has
// not sent anything since, which is what "this batch is as full as it is going to
// get" means in practice (one pass of quiet is not enough: at ~24 ms per frame and
// a 10 ms loop, a pass with nothing new is normal mid-stream).
static uint16_t s_batchPend = 0;
static uint8_t s_batchIdle = 0;
static unsigned long s_batchMs = 0;          // when this batch started waiting

// What the two ends of an install actually cost, measured rather than assumed:
// how much of the wall clock goes to flash (the esp_ota_write calls, and the
// erase that esp_ota_begin does up front), and how the bytes actually arrive from
// the radio (the gap between frames landing in the FIFO). The install-speed work
// turns on one question - is the wall the link or the flash? - and these numbers
// answer it. Counted on BtTask around the write, and by the producer on arrival;
// printed at the end of every install and inside the fifo-full failure line.
static uint32_t s_wrCalls = 0;               // esp_ota_write calls this install
static uint32_t s_wrUsTotal = 0;             // time spent inside them
static uint32_t s_wrUsMax = 0;               // the single worst call
static unsigned long s_beginMs = 0;          // esp_ota_begin cost (= the erase)
static unsigned long s_lastArriveMs = 0;     // last frame the radio delivered
static unsigned long s_arriveGapMaxMs = 0;   // longest pause between arrivals

// Read by the perf reply, so a soak run leaves its handoff behaviour visible without
// waiting for an install to fail. Both are reset by ota.begin (see btOtaBegin).
uint16_t btOtaFifoHighBytes() { return (uint16_t)s_fifoHigh; }
unsigned long btOtaDrainGapMaxMs() { return s_drainGapMaxMs; }

static uint16_t s_eventSeq = 0x4000;  // frame seq for our unsolicited events

// ACK cadence for the install in progress: OTA_ACK_BYTES, capped at half of this
// unit's handoff FIFO so the phone is always held back before the buffer that
// absorbs flash stalls is half full. Computed in btOtaBegin() from the chunk size
// the link actually negotiated. See OTA_FIFO_BYTES.
static uint32_t s_ackBytes = OTA_ACK_BYTES;

// Defined below, next to btOtaPoll(). The `end` path needs it before its
// definition: a length question cannot be answered until the FIFO's last bytes are
// in flash, so that path asks for a flush (every waiting byte, batch rules aside).
static void btOtaDrain(bool flushAll);

static uint32_t otaReadLE32(const uint8_t *p) {
  return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

// One chunk per write, sized by the link we actually negotiated: an ATT write
// carries MTU-3 bytes and four of them belong to the offset, so 517 B buys 496
// payload bytes. The phone clamps the same way (OtaFrames.maxChunk) - both sides
// doing the same arithmetic is what keeps a long write from being rejected by
// one stack or the other.
static uint32_t otaChunkForLink() {
  uint16_t mtu = btLinkMtu();
  if (mtu < 23) mtu = 23;
  int32_t usable = (int32_t)mtu - 3 - 4;
  if (usable < 1) usable = 1;
  if (usable > 512) usable = 512;
  int32_t aligned = (usable / 16) * 16;
  if (aligned < 1) aligned = usable < 16 ? usable : 16;
  return (uint32_t)aligned;
}

// Unsolicited events go out on the RPC characteristic (the OTA characteristic is
// write-only, so it has nothing to answer on) as BT_F_OTA frames.
static void otaEvent(const char *body) {
  if (!btLinkUp()) return;
  btSendMessage(BT_F_OTA, s_eventSeq++, body, strlen(body));
}

static void otaSendAck() {
  char body[48];
  snprintf(body, sizeof(body), "{\"ev\":\"ack\",\"off\":%lu}", (unsigned long)s_accepted);
  otaEvent(body);
}

// Everything the phone's card needs to show: where the unit stands, and the
// words the app already maps to a sentence (ble/OtaCore.kt).
static void otaFail(const char *code, const char *detail) {
  snprintf(s_reason, sizeof(s_reason), "%s", code);
  snprintf(s_detail, sizeof(s_detail), "%s", detail && detail[0] ? detail : "");
  logPrintf("OTA: install stopped (%s) %lu/%lu B heap=%lu %s\n", code,
            (unsigned long)s_accepted, (unsigned long)s_size,
            (unsigned long)ESP.getFreeHeap(), s_detail);

  if (s_sessionOpen) {
    esp_err_t e = esp_ota_abort(s_handle);
    logPrintf("OTA: slot released (%s)\n", esp_err_to_name(e));
    s_sessionOpen = false;
    s_handle = 0;
  }
  s_active = false;

  char body[96];
  snprintf(body, sizeof(body), "{\"ev\":\"error\",\"reason\":\"%s\",\"off\":%lu}", code,
           (unsigned long)s_accepted);
  otaEvent(body);

  // The panel: a failure frame naming the reason, then back to the dashboard on
  // its own after its dwell (main.cpp). The quiet window closes now - the unit
  // is still running what it was, so it has no business parking the dashboard.
  otaQuietExit(s_reason);
  otaScreenShowError();
  otaUpdateInProgress = false;

  // Give the dashboard its big UI buffers back. The install asked for them to be
  // freed (otaClaimPanel above), and ui.cpp will not re-allocate them while the
  // request stands - so a unit that refused an image used to run the rest of the
  // session with the speed readout drawn by neither path: no sprite (it was
  // freed), and no direct-panel fallback either, because src/ui.cpp:799 gates
  // that on this same flag. Bench 2026-11-03, after a `bad_length` refusal: the
  // heartbeat kept reporting sp=0 otaReq=1. Order matters - clear
  // otaUpdateInProgress first, or ensureSpeedSprite() sees an install still
  // running and stays asleep. The accepted path never comes here (it reboots
  // into the new image), and otaQuietExit already set forceFullRedraw, so the
  // next frame after the failure frame is a whole panel drawn from the rebuilt
  // sprite.
  otaMemReleaseRequested = false;
  otaMemReleased = false;
}

// ---------------------------------------------------------------------------
// Control plane (called from bt_rpc.cpp, i.e. on BtTask)
// ---------------------------------------------------------------------------

// Claims the panel and opens the quiet window, in the order plan 5.2 sets out:
// free the panel's flash-resident buffers first (on the display task's own
// initiative), then let the display task draw the update frame, then park
// everything else. Bounded waits, because a unit with a stuck display must
// refuse an install rather than hang the BLE task.
static bool otaClaimPanel() {
  otaUpdateInProgress = true;

  otaMemReleaseRequested = true;
  for (int i = 0; i < 150 && !otaMemReleased; i++) vTaskDelay(pdMS_TO_TICKS(20));
  if (!otaMemReleased)
    logPrintf("OTA: sprite/font release did not finish in 3s, continuing anyway\n");

  otaScreenShowUpdate();
  return true;
}

bool btOtaBegin(uint32_t size, uint32_t crc32, uint32_t *off, uint32_t *chunk,
                const char **err) {
  *err = nullptr;
  *off = s_accepted;
  *chunk = otaChunkForLink();

  if (s_active) {
    // `ota.begin` doubles as the resume handshake: the phone re-states the same
    // image and the unit answers with the offset to continue from. Refusing a
    // re-state would make the resume path in the app (which always calls begin)
    // unusable. A *different* image against an open slot is a real conflict.
    if (size == s_size && crc32 == s_crcDeclared) {
      *chunk = s_chunk;  // keep the chunk the transfer started with
      // A resume costs the phone time it did not have when the window started
      // (unlock the tablet, find the card, press RESUME), so the restatement
      // itself counts as life on the link: without this the window could expire
      // between "yes, continue at 786432" and the first write.
      otaQuietLastByteMs = millis();
      logPrintf("OTA: begin restated, resuming at %lu/%lu B\n", (unsigned long)s_accepted,
                (unsigned long)s_size);
      char body[64];
      snprintf(body, sizeof(body), "{\"ev\":\"begin_ok\",\"chunk\":%lu,\"off\":%lu}",
               (unsigned long)s_chunk, (unsigned long)s_accepted);
      otaEvent(body);
      return true;
    }
    *err = "already_active";
    return false;
  }

  if (size < OTA_MIN_IMAGE) {
    *err = "too_small";
    return false;
  }
  if (size > OTA_MAX_IMAGE) {
    *err = "no_space";
    return false;
  }
  if (!otaCrcSelfCheck()) {
    // Should be impossible; said out loud rather than letting every install fail
    // with a mystery CRC.
    logPrintf("OTA: CRC-32 self-check FAILED - refusing the install\n");
    *err = "write_failed";
    return false;
  }

  s_slot = esp_ota_get_next_update_partition(nullptr);
  if (!s_slot) {
    logPrintf("OTA: no spare app slot in the partition table\n");
    *err = "update_begin_failed";
    return false;
  }
  if (size > s_slot->size) {
    logPrintf("OTA: %lu B does not fit the %s slot (%lu B)\n", (unsigned long)size,
              s_slot->label, (unsigned long)s_slot->size);
    *err = "no_space";
    return false;
  }

  esp_ota_handle_t handle = 0;
  // esp_ota_begin() given the real image size erases the whole staging area here:
  // IDF 5.5 erases ALIGN_UP(size, sector) up front (and then never erases again
  // during the writes). It is one dead block on the critical path before the first
  // byte moves, so it is measured - it is the largest single number in a fast
  // install (see the end-of-install flash line).
  const unsigned long beginT0 = millis();
  esp_err_t e = esp_ota_begin(s_slot, size, &handle);
  s_beginMs = millis() - beginT0;
  logPrintf("OTA: open+erase %lums (%lu B, %lu sectors)\n", s_beginMs,
            (unsigned long)((size + 4095u) & ~4095u), (unsigned long)((size + 4095u) / 4096u));
  if (e != ESP_OK) {
    logPrintf("OTA: esp_ota_begin(%s, %lu B) failed: %s\n", s_slot->label, (unsigned long)size,
              esp_err_to_name(e));
    snprintf(s_detail, sizeof(s_detail), "esp_ota_begin: %s", esp_err_to_name(e));
    *err = "update_begin_failed";
    return false;
  }

  s_handle = handle;
  s_sessionOpen = true;
  s_size = size;
  s_crcDeclared = crc32;
  s_crcStored = 0;
  s_accepted = 0;
  s_ackAt = 0;
  s_chunk = otaChunkForLink();
  otaQuietLastByteMs = millis();
  s_openedMs = otaQuietLastByteMs;
  s_reason[0] = 0;
  s_detail[0] = 0;
  s_active = true;
  s_strayBytes = 0;
  // Drop anything a previous install left in the FIFO, and restart the batch view
  // of this one.
  s_fifoHead = 0;
  s_fifoTail = 0;
  s_feedExpect = 0;
  s_faultShape = false;
  s_faultFifo = false;
  s_faultOffset = false;
  s_overLen = 0;
  s_fifoHigh = 0;
  s_batchPend = 0;
  s_batchIdle = 0;
  s_batchMs = 0;
  s_lastDrainMs = 0;
  s_drainGapMaxMs = 0;
  s_wrCalls = 0;
  s_wrUsTotal = 0;
  s_wrUsMax = 0;
  s_lastArriveMs = 0;
  s_arriveGapMaxMs = 0;
  s_faultPending = false;

  // The ACK window for this session, capped at half the handoff FIFO for the chunk
  // size this link negotiated. Not flow control - the phone's allowance is 32 KB,
  // far past this buffer (see OTA_ACK_BYTES) - but honesty about flash: an ACK is a
  // claim about what the unit has written, and the unit should not make a claim
  // further ahead than what it has actually drained, or the resume token after a
  // drop points at bytes that only ever lived in RAM. Said out loud when it bites,
  // because it changes the progress granularity the phone sees.
  s_ackBytes = OTA_ACK_BYTES;
  {
    uint32_t halfFifo = (uint32_t)OTA_FIFO_BYTES / 2u;
    if (halfFifo < 512u) halfFifo = 512u;  // never ACK more often than every 512 B
    if (halfFifo < OTA_ACK_BYTES) {
      s_ackBytes = halfFifo;
      logPrintf("OTA: ack window trimmed to %lu B (handoff FIFO is %d B)\n",
                (unsigned long)s_ackBytes, OTA_FIFO_BYTES);
    }
  }

  *off = 0;
  *chunk = s_chunk;

  // The panel and the quiet window, before the first byte is accepted: the flash
  // session must open behind a finished picture, not halfway through a draw.
  otaClaimPanel();
  otaQuietEnter("ble");

  logPrintf("OTA: begin %lu B into %s (crc32=%08lx, chunk=%lu B, mtu %u, heap=%lu)\n",
            (unsigned long)size, s_slot->label, (unsigned long)crc32, (unsigned long)s_chunk,
            (unsigned)btLinkMtu(), (unsigned long)ESP.getFreeHeap());

  char body[64];
  snprintf(body, sizeof(body), "{\"ev\":\"begin_ok\",\"chunk\":%lu,\"off\":%lu}",
           (unsigned long)s_chunk, (unsigned long)s_accepted);
  otaEvent(body);
  return true;
}

// The image is complete as far as the phone is concerned. The unit runs its own
// gate on it: CRC first (cheap, and it names a corrupted transfer), then
// esp_ota_end's magic/checksum/SHA verify (names an image built for another
// board), then and only then otadata.
bool btOtaEnd(const char **err, uint32_t *off) {
  *err = nullptr;

  // Take the ring's last frames into flash before answering anything. The phone
  // writes the image on one characteristic and this `end` on another, and while
  // the link keeps their air order (the app holds one write mutex for both),
  // btTask's loop picks up the RPC before its next btOtaPoll() pass - so the
  // frames still sitting in the handoff ring would be invisible to the length
  // check below. Seen on the bench 2026-11-03: a complete 1,590,912 B file ended
  // as `bad_length` with 1,590,176 B accepted, short by exactly the two frames
  // that were in the buffer when `end` arrived.
  // Take the FIFO's last bytes into flash before answering anything. The phone
  // writes the image on one characteristic and this `end` on another, and while
  // the link keeps their air order (the app holds one write mutex for both),
  // btTask's loop picks up the RPC before its next btOtaPoll() pass - so the bytes
  // still sitting in the handoff FIFO would be invisible to the length check below.
  // Seen on the bench 2026-11-03: a complete 1,590,912 B file ended as `bad_length`
  // with 1,590,176 B accepted, short by exactly the two frames that were in the
  // buffer when `end` arrived. One write stops at the array end, so this can need
  // more than one pass; the loop is bounded, and a session that a drain ends (a
  // fault on the final frames, an abort) drops out of it on its own.
  for (int pass = 0; pass < 4 && s_active && fifoPending(); pass++) btOtaDrain(true);

  *off = s_accepted;
  if (!s_active) {
    // Already finished. Answering true here is the truth the caller wants - an
    // `end` that arrives twice (a reply it never saw, a phone that retried) gets
    // "yes, it is accepted" rather than an error code it would render as a
    // failure. A genuinely absent session stays an error. And if the drain above
    // is what ended the session - a flash write that failed on the final frames -
    // name that fault instead of calling it "no_session".
    if (s_acceptedImage) return true;
    if (s_reason[0]) {
      *err = s_reason;
      return false;
    }
    *err = "no_session";
    return false;
  }

  otaQuietLastByteMs = millis();

  if (!s_sessionOpen) {
    // The slot was already released by an error path; nothing left to finish.
    s_active = false;
    *err = "no_session";
    return false;
  }

  if (s_accepted != s_size) {
    char detail[64];
    snprintf(detail, sizeof(detail), "expected %lu B, accepted %lu B", (unsigned long)s_size,
             (unsigned long)s_accepted);
    otaFail("bad_length", detail);
    *err = "bad_length";
    *off = s_accepted;
    return false;
  }
  if (s_crcStored != s_crcDeclared) {
    char detail[80];
    snprintf(detail, sizeof(detail), "unit saw %08lx, phone declared %08lx",
             (unsigned long)s_crcStored, (unsigned long)s_crcDeclared);
    otaFail("crc", detail);
    *err = "crc";
    *off = s_accepted;
    return false;
  }

  const unsigned long verifyStart = millis();
  esp_err_t e = esp_ota_end(s_handle);
  s_sessionOpen = false;
  s_handle = 0;
  if (e != ESP_OK) {
    char detail[80];
    snprintf(detail, sizeof(detail), "image check: %s", esp_err_to_name(e));
    otaFail("end_failed", detail);
    *err = "end_failed";
    return false;
  }
  logPrintf("OTA: fifo high-water %u/%d B, longest %lums between drains\n",
            (unsigned)s_fifoHigh, OTA_FIFO_BYTES, (unsigned long)s_drainGapMaxMs);
  logPrintf("OTA: flash %lu writes mean %lu us max %lu us total %lums; open+erase %lums; "
            "longest %lums between arrivals\n",
            (unsigned long)s_wrCalls,
            (unsigned long)(s_wrCalls ? s_wrUsTotal / s_wrCalls : 0), (unsigned long)s_wrUsMax,
            (unsigned long)(s_wrUsTotal / 1000u), s_beginMs, s_arriveGapMaxMs);
  logPrintf("OTA: image verified in %lums (heap=%lu, stack free %u B)\n",
            (unsigned long)(millis() - verifyStart), (unsigned long)ESP.getFreeHeap(),
            (unsigned)btStackFree());

  const esp_partition_t *running = esp_ota_get_running_partition();
  e = esp_ota_set_boot_partition(s_slot);
  if (e != ESP_OK) {
    // Nothing has been activated: otadata still names the running slot, so the
    // next boot is the firmware it booted from this time.
    char detail[96];
    snprintf(detail, sizeof(detail), "otadata: %s", esp_err_to_name(e));
    otaFail("write_failed", detail);
    *err = "write_failed";
    return false;
  }

  s_active = false;
  s_acceptedImage = true;
  logPrintf("OTA: image accepted (%lu B, slot %s at 0x%lx; was running 0x%lx)\n",
            (unsigned long)s_size, s_slot->label, (unsigned long)s_slot->address,
            (unsigned long)(running ? running->address : 0));

  char body[48];
  snprintf(body, sizeof(body), "{\"ev\":\"done\",\"off\":%lu}", (unsigned long)s_accepted);
  otaEvent(body);

  // The reboot belongs to the display task: the bar completes, the done frame is
  // drawn and held, bootinfo is tagged, and only then does the unit restart into
  // the new slot (main.cpp). The quiet window stays open deliberately - the unit
  // is not coming back, and parking the producers until then is correct.
  otaUpdateSuccess = true;
  otaProgressTarget = 258;
  *off = s_accepted;
  return true;
}

bool btOtaAbort(const char **err) {
  *err = nullptr;
  if (!s_active) {
    *err = "no_session";
    return false;
  }
  otaFail("aborted", "cancelled from the phone");
  return true;
}

// What the phone asks when it is not sure where a transfer stands (it usually
// is not sure: the ACK that would have told it was in flight when the link
// dropped). `stage` is a word for the app's log, not a state machine.
void btOtaStatus(char *buf, size_t cap, uint32_t *off, uint32_t *size, bool *active,
                 const char **stage) {
  if (s_acceptedImage) *stage = "rebooting";
  else if (!s_active) *stage = s_reason[0] ? "failed" : "off";
  else if (!s_sessionOpen) *stage = "opening";
  else *stage = "receiving";
  *active = s_active;
  *off = s_accepted;
  *size = s_size;
  if (buf && cap)
    snprintf(buf, cap, "\"silent_ms\":%lu,\"mtu\":%u,\"chunk\":%lu",
             (unsigned long)(s_active ? millis() - otaQuietLastByteMs : 0),
             (unsigned)btLinkMtu(), (unsigned long)s_chunk);
}

// ---------------------------------------------------------------------------
// Data plane
// ---------------------------------------------------------------------------

// Called on the Bluedroid callback task. Copy and return - everything that
// thinks happens on BtTask. What it checks: the frame's shape, that the FIFO has
// room for the whole payload, and that the frame carries the offset the image is
// actually at. The offset check lives here now because a byte FIFO has no frame
// boundaries to check at the far end: this is the one moment the 4-byte prefix
// exists. It is judged against `s_feedExpect`, which only this task writes (the
// offset the next arriving frame must carry), so the check cannot race with the
// drain's bookkeeping; the FIFO is empty, the resync below says the unit's written
// count is the truth (that is where a phone that reconnects resumes from).
void btOtaFeed(const uint8_t *data, size_t len) {
  if (!s_active) {
    s_strayBytes += (uint32_t)len;
    if (!s_strayLogged) {
      s_strayLogged = true;
      logPrintf("OTA: %u B written with no install open - dropped\n", (unsigned)len);
    }
    return;
  }
  if (len < 5 || len > OTA_MAX_FRAME) {
    s_faultShape = true;
    s_overLen = (uint16_t)len;
    s_faultPending = true;  // a frame this shape is a bug on one side of the link
    return;
  }
  const uint16_t n = (uint16_t)(len - 4);
  const uint16_t pend = fifoPending();
  if ((uint32_t)pend + (uint32_t)n > (uint32_t)OTA_FIFO_BYTES) {
    s_faultFifo = true;
    s_overLen = (uint16_t)len;
    s_faultPending = true;
    return;
  }
  const uint32_t got = otaReadLE32(data);
  if (got != s_feedExpect) {
    // No rewind, and no skipping: if the phone and the unit disagree about what has
    // been written, the CRC being folded is not worth anything.
    s_faultOffset = true;
    s_offGot = got;
    s_offWant = s_feedExpect;
    s_faultPending = true;
    return;
  }
  // Append the payload, wrapped across the array end if it has to be. Room for the
  // whole payload was checked above; a stale read of s_fifoTail only ever makes
  // `pend` look bigger than it is, which makes both checks stricter, never looser.
  const uint16_t idx = (uint16_t)(s_fifoHead & OTA_FIFO_MASK);
  const uint16_t first = (uint16_t)(OTA_FIFO_BYTES - idx);
  if (first >= n) {
    memcpy(s_fifo + idx, data + 4, n);
  } else {
    memcpy(s_fifo + idx, data + 4, first);
    memcpy(s_fifo, data + 4 + first, (uint16_t)(n - first));
  }
  s_fifoHead = (uint16_t)(s_fifoHead + n);
  s_feedExpect += n;

  const unsigned long nowArr = millis();
  if (s_lastArriveMs && nowArr - s_lastArriveMs > s_arriveGapMaxMs)
    s_arriveGapMaxMs = nowArr - s_lastArriveMs;
  s_lastArriveMs = nowArr;
  const uint16_t waiting = (uint16_t)(s_fifoHead - s_fifoTail);
  if (waiting > s_fifoHigh) s_fifoHigh = waiting;
}

// Takes the FIFO to flash. Runs on BtTask, which is also the task answering RPC -
// so an ACK, an error event and a reply can never interleave with a write. One
// esp_ota_write covers everything that arrived since the last pass: that is the
// whole reason the handoff buffer is a byte FIFO and not a ring of frames (see
// OTA_FIFO_BYTES). `flushAll` asks for the batching rules to be ignored - the
// `end` path cannot answer a length question while bytes still sit in RAM.
static void btOtaDrain(bool flushAll) {
  if (s_active) {
    // How long BtTask can go without serving the FIFO. This is the quantity that
    // decides whether a deep-enough pipeline overflows it or not, so it is measured
    // on every install and printed when one dies.
    const unsigned long nowMs = millis();
    if (s_lastDrainMs && nowMs - s_lastDrainMs > s_drainGapMaxMs)
      s_drainGapMaxMs = nowMs - s_lastDrainMs;
    s_lastDrainMs = nowMs;
  }

  // Report a frame the producer refused - here, because the callback task may not
  // talk - and end the install. The refused frame never entered the FIFO, and the
  // bytes already in it belong to a session that is over, so they are dropped
  // rather than flashed.
  if (s_faultPending) {
    s_faultPending = false;
    const bool isOffset = s_faultOffset;
    char detail[96];
    if (s_faultShape)
      snprintf(detail, sizeof(detail), "frame %u B; this unit takes 5..%d B (chunk is %lu B)",
               (unsigned)s_overLen, OTA_MAX_FRAME, (unsigned long)s_chunk);
    else if (s_faultFifo)
      snprintf(detail, sizeof(detail), "fifo full (%d B, frame %u B, high-water %u B, longest "
               "%lums between drains, flash mean %lu us max %lu us)", OTA_FIFO_BYTES,
               (unsigned)s_overLen, (unsigned)s_fifoHigh, (unsigned long)s_drainGapMaxMs,
               (unsigned long)(s_wrCalls ? s_wrUsTotal / s_wrCalls : 0), (unsigned long)s_wrUsMax);
    else
      snprintf(detail, sizeof(detail), "write at %lu, unit expects %lu", (unsigned long)s_offGot,
               (unsigned long)s_offWant);
    s_faultShape = false;
    s_faultFifo = false;
    s_faultOffset = false;
    s_overLen = 0;
    s_fifoTail = s_fifoHead;
    otaFail(isOffset ? "offset_mismatch" : "bad_frame", detail);
    return;
  }

  if (!s_active || !s_sessionOpen) {
    // The install died between the bytes arriving and this pass (an abort, or a
    // silence window). The bytes belong to a session that no longer exists.
    s_active = false;
    s_fifoTail = s_fifoHead;
    return;
  }

  const uint16_t pend = fifoPending();
  if (pend == 0) {
    s_batchPend = 0;
    s_batchIdle = 0;
    return;
  }
  // Batch bookkeeping, all of it BtTask's own: the batch clock starts when bytes
  // first appear, and "the phone has gone quiet" means passes in a row that brought
  // nothing new. One quiet pass is not enough - at ~24 ms per frame and a 10 ms
  // loop, a pass with nothing new is ordinary mid-stream.
  if (pend == s_batchPend) {
    s_batchIdle++;
  } else {
    if (s_batchPend == 0) s_batchMs = millis();
    s_batchIdle = 0;
  }
  s_batchPend = pend;
  if (!flushAll && pend < OTA_WRITE_TARGET && pend < OTA_FIFO_PRESSURE && s_batchIdle < 3 &&
      (millis() - s_batchMs) < OTA_WRITE_WAIT_MS)
    return;

  // Everything waiting, up to the array end (the tail index wraps). The run stays a
  // multiple of 4 except when it carries the image's final short frame, which is
  // what the per-frame writes already handed esp_ota_write, and what its own tail
  // handling copes with.
  const uint16_t idx = (uint16_t)(s_fifoTail & OTA_FIFO_MASK);
  uint16_t run = pend;
  if (run > (uint16_t)(OTA_FIFO_BYTES - idx)) run = (uint16_t)(OTA_FIFO_BYTES - idx);

  const uint32_t wrT0 = micros();
  esp_err_t e = esp_ota_write(s_handle, s_fifo + idx, run);
  const uint32_t wrUs = (uint32_t)(micros() - wrT0);
  s_wrCalls++;
  s_wrUsTotal += wrUs;
  if (wrUs > s_wrUsMax) s_wrUsMax = wrUs;
  if (e != ESP_OK) {
    char detail[80];
    snprintf(detail, sizeof(detail), "write at %lu: %s", (unsigned long)s_accepted,
             esp_err_to_name(e));
    s_fifoTail = s_fifoHead;  // the session is over; whatever is waiting is moot
    otaFail("write_failed", detail);
    return;
  }

  s_crcStored = otaCrcChain(s_crcStored, s_fifo + idx, run);
  s_accepted += run;
  s_fifoTail = (uint16_t)(s_fifoTail + run);  // published last: the producer's room
                                              // check reads it
  otaQuietLastByteMs = millis();

  if (s_accepted - s_ackAt >= s_ackBytes || s_accepted == s_size) {
    s_ackAt = s_accepted;
    otaSendAck();
    updateOTAProgress((int)s_accepted, (int)s_size);
    // One progress line per 128 KB transferred, whatever the ACK cadence is.
    if (s_accepted / 131072u != (s_accepted - run) / 131072u)
      logPrintf("OTA: %lu/%lu B, heap=%lu\n", (unsigned long)s_accepted,
                (unsigned long)s_size, (unsigned long)ESP.getFreeHeap());
  }
}

static void btOtaClocks() {
  if (!s_active) return;
  const unsigned long now = millis();
  if (now - otaQuietLastByteMs > OTA_SILENCE_MS) {
    char detail[64];
    snprintf(detail, sizeof(detail), "nothing accepted for %lus",
             (unsigned long)((now - otaQuietLastByteMs) / 1000UL));
    otaFail("stalled", detail);
    return;
  }
  if (now - s_openedMs > OTA_MAX_MS) {
    otaFail("stalled", "an install was open for 15 minutes");
    return;
  }
}

// Called from btTask's loop, every pass.
void btOtaPoll() {
  if (!s_active && fifoPending() == 0 && !s_faultPending) {
    if (s_strayLogged && s_strayBytes) {
      logPrintf("OTA: %lu stray byte(s) dropped this window\n", (unsigned long)s_strayBytes);
      s_strayBytes = 0;
      s_strayLogged = false;
    }
    return;
  }
  btOtaDrain(false);
  btOtaClocks();
}

// ---------------------------------------------------------------------------
// The questions the rest of the firmware asks about an install
// ---------------------------------------------------------------------------
// These four used to be stubs in bt.cpp with the web task gone. They are the
// real answers now: main.cpp reads them to draw the failure frame, and
// sensors.cpp refuses the hold-to-restart gesture while the answer is true,
// because a reset between two flash writes is a half-written slot.

bool otaFlashWriteActive() { return s_sessionOpen; }

const char *otaInstallReason() { return s_reason[0] ? s_reason : "none"; }

const char *otaInstallDetail() { return s_detail; }

void otaInstallBytesSnapshot(long *bytesDone, long *bytesTotal) {
  if (bytesDone) *bytesDone = (long)s_accepted;
  if (bytesTotal) *bytesTotal = (long)s_size;
}

// True while any install is open, including the gap where a link dropped and the
// resume window is still running. The RPC dispatcher uses this to answer
// `ota_in_progress` for everything that is not about the install itself, which is
// how a settings write cannot land halfway into a flashing slot.
bool btOtaActive() { return s_active; }

bool btOtaImageAccepted() { return s_acceptedImage; }
