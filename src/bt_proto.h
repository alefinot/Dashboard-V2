// Wire format shared by the BLE link and the app.
//
// Every message is a header plus a payload, and a message larger than one MTU
// is split into fragments carrying the same seq, with FIRST on the first piece
// and LAST on the final one. The receiver reassembles by seq and refuses a
// second open message, so a lost fragment can never grow an unbounded buffer.
//
//   0  version   1
//   1  type      see BtFrameType
//   2  flags     bit0 FIRST, bit1 LAST
//   3  reserved  must be 0
//   4  seq       message id, little endian
//   6  len       payload bytes in this fragment, little endian
//
// Payload per fragment = MTU - 3 - BT_FRAME_HDR. At the negotiated MTU of 517
// that is 506 bytes.
#pragma once

#include <stddef.h>
#include <stdint.h>

#define BT_PROTO_VERSION 1u

#define BT_FRAME_HDR 8u
// Largest message the firmware will reassemble. A full config export is the
// biggest thing either side sends; an OTA chunk never travels this way.
#define BT_MAX_MSG 8192u

#define BT_FLAG_FIRST 0x01u
#define BT_FLAG_LAST 0x02u

enum BtFrameType : uint8_t {
  BT_F_REQ = 1,    // phone -> unit, JSON request
  BT_F_RESP = 2,   // unit -> phone, JSON response
  BT_F_TELEM = 3,  // unit -> phone, 1 Hz snapshot
  BT_F_WEATHER = 4,
  BT_F_TIME = 5,
  BT_F_PING = 6,
  BT_F_PONG = 7,
  // Install events, unit -> phone, JSON: {"ev":"begin_ok"|"ack"|"done"|"error"}.
  // They travel on the RPC characteristic, the only one of the three that can
  // notify - the OTA data characteristic is write-only, so it has nothing to
  // answer on. The image bytes never travel this way: they go to the OTA
  // characteristic as offset(4, little endian) + payload, payload at most
  // MTU - 3 - 4 (496 B at the 517 B we ask for).
  BT_F_OTA = 8,
  BT_F_LOG = 9,
  // One piece of a streamed config.get. It gets its own type instead of reusing
  // BT_F_RESP so the app never has to guess, from the shape of a JSON object,
  // whether it is looking at a complete answer or one more slice of a bigger
  // one. Each chunk payload is {"id":req,"i":n,"last":bool,"d":{...}} and the
  // stream closes with an ordinary BT_F_RESP carrying {"chunks":n,"keys":n}.
  BT_F_CHUNK = 10,
};

// Splits `data` into fragments of type `type`, tags them with `seq`, and sends
// them through the matching characteristic. Paced so the controller's TX
// buffers never overflow; call it from the BLE task only.
bool btSendMessage(uint8_t type, uint16_t seq, const char *data, size_t len);

// True while a phone holds an encrypted, connected link.
bool btLinkUp();

// Handles one complete request message. Implemented in bt_rpc.cpp; runs on the
// BLE task, so it may block for a few hundred milliseconds on a config save.
void btRpcHandle(const char *msg, size_t len, uint16_t seq);

// The 1 Hz telemetry body, allocation-free. Also answers the `snapshot` method
// on demand. Returns bytes written (0 if cap is too small).
size_t btBuildSnapshotJson(char *buf, size_t cap);

// Bytes left on the BLE task stack at its deepest point since boot; 0 until the
// task exists. This is the stack the RPC handlers run on.
uint16_t btStackFree();

// The same task's handle, for the heapdiag stack table. Opaque pointer on
// purpose - FreeRTOS types stay out of this header.
void *btTaskHandleForDiag();

// The ATT MTU actually negotiated on this link (what onMtu said), NOT what we
// asked for - BLEDevice::getMTU() reports the request, so a central that
// negotiated lower would be handed a chunk size its writes cannot carry.
uint16_t btLinkMtu();

// The install itself (src/bt_ota.cpp). The control-plane calls run on the BLE
// task, from the RPC handlers below. btOtaFeed runs on the Bluedroid callback
// task and only copies - flash work there would stall the GATT stack - and
// btOtaPoll() (called from the BLE task's loop) drains that ring into flash,
// folds the CRC-32, sends the ACK events and enforces the resume/silence
// windows. Every state change therefore happens on one task, and there are no
// locks between the control plane and the data path.
bool btOtaBegin(uint32_t size, uint32_t crc32, uint32_t *off, uint32_t *chunk,
                const char **err);
bool btOtaEnd(const char **err, uint32_t *off);
bool btOtaAbort(const char **err);
void btOtaStatus(char *buf, size_t cap, uint32_t *off, uint32_t *size, bool *active,
                 const char **stage);
void btOtaFeed(const uint8_t *data, size_t len);
void btOtaPoll();

// Ring behaviour of the most recent install (reset by btOtaBegin): how many of
// the OTA_RING_SLOTS-1 usable slots were occupied at the busiest moment, and the
// longest gap between two BtTask drain passes. The perf reply reports both, so a
// slot-count change is judged on numbers (heap-recovery plan, Phase 1).
uint8_t btOtaRingHighWater();
unsigned long btOtaDrainGapMaxMs();
bool btOtaActive();         // an install is open, resume window included
bool btOtaImageAccepted();  // verified and activated; the reboot is pending

// Forgets every stored pairing. Called by the factory reset: the GATT
// permissions require an encrypted link, so a bond left behind by the previous
// owner would lock the new one out of the unit for good.
void btClearBonds();

// True while the panel is showing a pairing passkey.
bool btPairingActive();
uint32_t btPairingPasskey();

// Bounded printf appender shared by the telemetry serializer and the RPC
// handlers: never writes more than cap-1 bytes in total, and returns false when
// the text did not fit (the buffer still holds what did).
bool btCatf(char *buf, size_t cap, size_t *used, const char *fmt, ...);

// Bench driver for the config stream, compiled into a build only when
// -DBT_CFG_VERIFY is set (see platformio.ini and the footer of bt_rpc.cpp).
// btRunSelfTest() drives the request path on the BLE task; btVerifyFrame() is
// the observer btSendMessage calls for every message it sends.
#ifdef BT_CFG_VERIFY
void btRunSelfTest();
void btVerifyFrame(uint16_t type, uint16_t seq, const char *msg, size_t len);
#endif
