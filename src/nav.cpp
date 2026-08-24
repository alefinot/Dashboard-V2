// Optional Google-Maps navigation widget (step 2 of the BLE plan).
// See src/nav.h.
#include "nav.h"
#include <Arduino.h>
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
#include <freertos/semphr.h>
#include <string.h>

static SemaphoreHandle_t navMutex = NULL;
static NavDto nav;
static bool navValid = false;   // have we received a NAV frame?
static volatile bool navChanged = true;  // dirty flag (draw once)

static portMUX_TYPE navInitMux = portMUX_INITIALIZER_UNLOCKED;
static void navLock() {
  if (navMutex == NULL) {
    portENTER_CRITICAL(&navInitMux);
    if (navMutex == NULL) navMutex = xSemaphoreCreateMutex();
    portEXIT_CRITICAL(&navInitMux);
  }
  xSemaphoreTake(navMutex, portMAX_DELAY);
}
static void navUnlock() { xSemaphoreGive(navMutex); }

void navPush(const NavDto &dto) {
  navLock();
  nav = dto;
  navValid = true;
  navChanged = true;  // request a redraw
  navUnlock();
}

void drawNavWidget(LGFX_ST7789_4 &d) {
  // Heap gate (nav.h / plan §9): the nav widget only exists while the steady-
  // state free heap stays green (>= ~20 KB). If it is not, skip the draw
  // (the widget is hidden, not drawn); a later show draws fresh.
  if (ESP.getFreeHeap() < 20000) {
    return;
  }
  navLock();
  bool on = navValid && nav.on;
  bool changed = navChanged;
  navChanged = false;  // consume the dirty flag
  NavDto n = nav;
  navUnlock();

  if (!changed) return;  // nothing new: skip (no per-frame redraw)

  const int x = 8, y = 124, w = 112, h = 52;
  uint16_t bg = d.color565(15, 15, 15);
  uint16_t border = d.color565(45, 45, 45);
  uint16_t accent = d.color565(0, 220, 220);

  if (!on) {
    // Hide: clear the region so a later show draws fresh.
    d.fillRect(x, y, w, h, d.color565(0, 0, 0));
    return;
  }

  fillAARoundRect(d, x, y, w, h, 6, bg);
  drawAARoundRect(d, x, y, w, h, 6, border);

  uint16_t white = d.color565(240, 240, 240);
  uint16_t dim = d.color565(150, 150, 150);

  // Arrow glyph: a small upward triangle at the top-left, beside the act row.
  int ax = x + 14, ay = y + 10;
  d.fillTriangle(ax, ay - 6, ax - 5, ay + 4, ax + 5, ay + 4, accent);

  // Act (row 1): the maneuver instruction (e.g. "Turn right") - the
  // headline, next to the arrow, clipped to the width.
  d.loadVLWFont("/Fonts/Conthrax_SemiBold_16px.vlw");
  d.setTextColor(white, bg);
  int tx = x + 26;
  const int actMaxX = x + w - 8;
  for (unsigned i = 0; n.act[i] && tx < actMaxX; i++) {
    char c[2] = {n.act[i], 0};
    int cw = d.textWidth(c);
    if (tx + cw > actMaxX) break;
    d.setTextColor(white, bg);
    d.setCursor(tx, y + 10);
    d.print(c);
    tx += cw;
  }

  d.loadVLWFont("/Fonts/Conthrax_SemiBold_10px.vlw");

  // Distance (row 2).
  char dist[24];
  snprintf(dist, sizeof(dist), "%.1f%s", n.d, n.u);
  d.setTextColor(white, bg);
  d.setCursor(x + 10, y + 24);
  d.print(dist);

  // Road (row 3), clipped to the width.
  const int roadMaxX = x + w - 8;
  int rx = x + 10;
  for (unsigned i = 0; n.road[i] && rx < roadMaxX; i++) {
    char c[2] = {n.road[i], 0};
    int cw = d.textWidth(c);
    if (rx + cw > roadMaxX) break;
    d.setTextColor(dim, bg);
    d.setCursor(rx, y + 36);
    d.print(c);
    rx += cw;
  }

  // ETA / arrival (row 4).
  d.setTextColor(accent, bg);
  d.setCursor(x + 10, y + 49);
  if (n.arrival)
    d.print("ARRIVING");
  else if (n.eta[0] != 0)
    d.print(n.eta);
  else
    d.print("—");
}
