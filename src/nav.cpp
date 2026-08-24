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

  d.loadVLWFont("/Fonts/Conthrax_SemiBold_16px.vlw");
  uint16_t white = d.color565(240, 240, 240);
  uint16_t dim = d.color565(150, 150, 150);

  // Arrow glyph: a small upward triangle at the top-left.
  int ax = x + 14, ay = y + 14;
  d.fillTriangle(ax, ay - 6, ax - 5, ay + 4, ax + 5, ay + 4, accent);

  // Distance (row 1).
  char dist[24];
  snprintf(dist, sizeof(dist), "%.1f%s", n.d, n.u);
  d.setTextColor(white, bg);
  d.setCursor(x + 26, y + 18);
  d.print(dist);

  // Road (row 2), clipped to the width.
  const int roadX = x + 10;
  const int roadMaxX = x + w - 8;
  const char *road = n.road;
  d.setTextColor(dim, bg);
  int rx = roadX;
  for (unsigned i = 0; road[i] && rx < roadMaxX; i++) {
    char c[2] = {road[i], 0};
    int cw = d.textWidth(c);
    if (rx + cw > roadMaxX) break;
    d.setTextColor(dim, bg);
    d.setCursor(rx, y + 34);
    d.print(c);
    rx += cw;
  }

  // ETA / arrival (row 3).
  d.setTextColor(accent, bg);
  d.setCursor(x + 10, y + 50);
  if (n.arrival)
    d.print("ARRIVING");
  else if (n.eta[0] != 0)
    d.print(n.eta);
  else
    d.print("—");
}
