// Optional Google-Maps navigation widget (step 2 of the BLE plan).
//
// The phone parses Google Maps nav notifications (turn / ETA / arrival) and
// pushes a NAV frame over BLE. The ESP renders a compact card in the free
// left band. Gated on remaining heap: the widget only exists while the
// heap budget stays green (see the plan). See
// Implementation plans/bluetooth-weather-and-phone-notifications-plan.md.
#ifndef NAV_H
#define NAV_H

#include "dashboard.h"

struct NavDto {
  bool on;      // show / hide the widget
  char act[16];  // nav token (e.g. "nav")
  double d;     // remaining distance
  char u[8];    // unit ("m" / "km")
  char road[24];  // road name (truncated by the sender)
  char eta[16];  // ETA "HH:MM" ("" = none)
  bool arrival;  // arrival-soon flag
};

void navPush(const NavDto &dto);
// Draw the nav widget in the free left band (region 8,124,112,52).
// No-op until a NAV frame arrives / BLE is off.
void drawNavWidget(LGFX_ST7789_4 &d);

#endif // NAV_H
