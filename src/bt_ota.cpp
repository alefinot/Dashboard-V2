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
//                            thing: copy the bytes into a ring and return. No
//                            flash work, no JSON, no notify - a long onWrite
//                            here stalls the whole GATT stack, including the
//                            writes still arriving.
//   BtTask (core 0, 8 KB)    the RPC handlers (ota.begin / end / abort / status)
//                            AND btOtaPoll(): drain the ring, write flash, fold
//                            the CRC, send the ACKs, watch the clocks. Every
//                            state change happens on this one task, so there is
//                            no lock between the control plane and the data path.
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
// Memory rules (AGENTS.md 14): one static ring (8 x 516 B), one static detail
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
// How much accepted data between ACKs. The ACK is the phone's resume token, so
// it also bounds how much has to be re-sent after a drop; 16 KB is ~30 writes
// and roughly one per second on a healthy link.
static const uint32_t OTA_ACK_BYTES = 16384;
// An app slot in partitions.csv (0x1D0000). Larger than this cannot fit, and
// the build gate (1.80 MB) exists so a normal build never asks.
static const uint32_t OTA_MAX_IMAGE = 0x1D0000u;
// Smaller than this is not firmware, it is a truncated download or a text file.
static const uint32_t OTA_MIN_IMAGE = 4096u;

// The handoff ring from the Bluedroid task to BtTask. Depth 4 is generous: the
// phone awaits each write's callback before sending the next (that wait IS the
// flow control), so at most a frame or two is ever in here.
static const int OTA_RING_SLOTS = 4;
static const int OTA_RING_MASK = OTA_RING_SLOTS - 1;
static const int OTA_SLOT_BYTES = 4 + 512;  // offset prefix + max payload
static const int OTA_MAX_FRAME = OTA_SLOT_BYTES;

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

static char s_reason[24] = "";
static char s_detail[96] = "";

// The Bluedroid task drops a frame here; BtTask consumes it. Single producer,
// single consumer, indices wrap by mask - no mutex needed, but both indices are
// volatile because they are written by different tasks.
static uint8_t s_ring[OTA_RING_SLOTS][OTA_SLOT_BYTES];
static volatile uint8_t s_ringHead = 0;  // producer (Bluedroid)
static volatile uint8_t s_ringTail = 0;  // consumer (BtTask)
static volatile uint16_t s_ringLen[OTA_RING_SLOTS] = {0};
// Set by the producer when a frame is too long for the ring, or when bytes
// arrived with no install open. Handled (and reported) on BtTask, where the
// error path is allowed to talk.
static volatile bool s_ringsOver = false;
static volatile bool s_strayLogged = false;
static uint32_t s_strayBytes = 0;

static uint16_t s_eventSeq = 0x4000;  // frame seq for our unsolicited events

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
  esp_err_t e = esp_ota_begin(s_slot, size, &handle);
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
  // Drop anything a previous install left in the ring.
  s_ringHead = 0;
  s_ringTail = 0;
  s_ringsOver = false;

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
  *off = s_accepted;
  if (!s_active) {
    // Already finished. Answering true here is the truth the caller wants - an
    // `end` that arrives twice (a reply it never saw, a phone that retried) gets
    // "yes, it is accepted" rather than an error code it would render as a
    // failure. A genuinely absent session stays an error.
    if (s_acceptedImage) return true;
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
// thinks happens on BtTask.
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
    s_ringsOver = true;  // a frame this shape is a bug on one side of the link
    return;
  }
  const uint8_t next = (uint8_t)((s_ringHead + 1) & OTA_RING_MASK);
  if (next == s_ringTail) {
    s_ringsOver = true;
    return;
  }
  memcpy(s_ring[s_ringHead], data, len);
  s_ringLen[s_ringHead] = (uint16_t)len;
  s_ringHead = next;
}

// Drains the ring into flash. Runs on BtTask, which is also the task answering
// RPC - so an ACK, an error event and a reply can never interleave with a write.
static void btOtaDrain() {
  int served = 0;
  while (s_ringTail != s_ringHead && served < OTA_RING_SLOTS) {
    const uint8_t slot = s_ringTail;
    const uint16_t len = s_ringLen[slot];
    s_ringTail = (uint8_t)((s_ringTail + 1) & OTA_RING_MASK);
    served++;

    if (!s_active || !s_sessionOpen) {
      // The install died between the write arriving and this drain (an abort, or
      // a silence window). The bytes belong to a session that no longer exists.
      s_active = false;
      continue;
    }

    const uint32_t off = otaReadLE32(s_ring[slot]);
    if (off != s_accepted) {
      // No rewind, and no skipping. Both mean the phone and the unit disagree
      // about what has been written, and the CRC the unit is folding is only
      // worth anything if the bytes it covers are the bytes it says.
      char detail[64];
      snprintf(detail, sizeof(detail), "write at %lu, unit expects %lu", (unsigned long)off,
               (unsigned long)s_accepted);
      otaFail("offset_mismatch", detail);
      return;
    }

    const uint8_t *payload = s_ring[slot] + 4;
    const uint32_t n = (uint32_t)(len - 4);
    esp_err_t e = esp_ota_write(s_handle, payload, n);
    if (e != ESP_OK) {
      char detail[80];
      snprintf(detail, sizeof(detail), "write at %lu: %s", (unsigned long)s_accepted,
               esp_err_to_name(e));
      otaFail("write_failed", detail);
      return;
    }

    s_crcStored = otaCrcChain(s_crcStored, payload, n);
    s_accepted += n;
    otaQuietLastByteMs = millis();

    if (s_accepted - s_ackAt >= OTA_ACK_BYTES || s_accepted == s_size) {
      s_ackAt = s_accepted;
      otaSendAck();
      updateOTAProgress((int)s_accepted, (int)s_size);
      if ((s_accepted & 0x1FFFF) < OTA_ACK_BYTES)
        logPrintf("OTA: %lu/%lu B, heap=%lu\n", (unsigned long)s_accepted,
                  (unsigned long)s_size, (unsigned long)ESP.getFreeHeap());
    }
  }

  if (s_ringsOver) {
    s_ringsOver = false;
    otaFail("bad_frame", "a write did not fit this unit's frame");
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
  if (!s_active && s_ringTail == s_ringHead && !s_ringsOver) {
    if (s_strayLogged && s_strayBytes) {
      logPrintf("OTA: %lu stray byte(s) dropped this window\n", (unsigned long)s_strayBytes);
      s_strayBytes = 0;
      s_strayLogged = false;
    }
    return;
  }
  btOtaDrain();
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
