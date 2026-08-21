// BLE GATT transport for the phone companion (weather / notifications /
// media / nav). See Implementation plans/bluetooth-weather-and-phone-
// notifications-plan.md.
//
// The ESP is a GATT *server*: it advertises a 16-bit service and two
// characteristics. The phone (BLE central) writes framed payloads to the RX
// characteristic; the ESP reassembles the frame, dispatches it (weather /
// notification / media / nav), and pushes STATUS back over the TX
// characteristic (notify + read).
#ifndef BLE_H
#define BLE_H

#include "dashboard.h"

// Frame type bytes (the leading byte of each framed payload).
#define BLE_TYPE_WEATHER    0x01
#define BLE_TYPE_NOTIFICATION 0x02
#define BLE_TYPE_PING       0x03
#define BLE_TYPE_ACK        0x04
#define BLE_TYPE_NAV        0x05
#define BLE_TYPE_MEDIA      0x06

// GATT UUIDs (16-bit) advertised by the server.
#define BLE_SVC_UUID16  ((uint16_t)0xD11A)
#define BLE_RX_UUID16   ((uint16_t)0xD101)  // phone -> ESP (write)
#define BLE_TX_UUID16   ((uint16_t)0xD102)  // ESP -> phone (notify + read)

// Create the bleTask (pinned to core 0). Called from setup() when
// BLE_ENABLED is set; a no-op otherwise.
void bleTask(void *pv);
// True while a phone is connected.
bool bleIsLinked();
// Send a STATUS JSON (heap/fps/wifi/bt/nq/ver) to the connected phone.
void bleSendStatus();

#endif // BLE_H
