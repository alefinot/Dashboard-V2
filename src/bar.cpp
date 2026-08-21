// Phone-companion notification / media bar (top strip of the dashboard).
// See src/bar.h for the state model.
#include "bar.h"
#include <Arduino.h>
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
#include <freertos/semphr.h>
#include <string.h>

#define BAR_FIFO_DEPTH 8

static SemaphoreHandle_t barMutex = NULL;
static NtfDto alerts[BAR_FIFO_DEPTH];
static unsigned char alertCount = 0;
static MediaDto song;
static bool songActive = false;
// When the front alert started holding the bar (7s timer while a song plays).
static unsigned long alertHoldMs = 0;
static volatile bool barDirty = true;  // draw once (boot), then on change

static portMUX_TYPE barInitMux = portMUX_INITIALIZER_UNLOCKED;
static void barLock() {
  if (barMutex == NULL) {
    portENTER_CRITICAL(&barInitMux);
    if (barMutex == NULL) barMutex = xSemaphoreCreateMutex();
    portEXIT_CRITICAL(&barInitMux);
  }
  xSemaphoreTake(barMutex, portMAX_DELAY);
}
static void barUnlock() { xSemaphoreGive(barMutex); }

void barPush(const NtfDto &dto) {
  barLock();
  if (alertCount < BAR_FIFO_DEPTH) {
    alerts[alertCount++] = dto;
  } else {
    // FIFO full: drop the oldest, shift the rest up.
    memmove(&alerts[0], &alerts[1], sizeof(NtfDto) * (BAR_FIFO_DEPTH - 1));
    alerts[BAR_FIFO_DEPTH - 1] = dto;
  }
  // A new alert starts its own hold window (the 7s timer).
  alertHoldMs = millis();
  barDirty = true;
  barUnlock();
}

void barSetSong(const MediaDto &dto) {
  barLock();
  song = dto;
  songActive = dto.active;
  barDirty = true;
  barUnlock();
}

void barTick(unsigned long nowMs) {
  barLock();
  // An alert only falls back to the song while a song is playing: the
  // conditional 7s timeout. With no song, the alert stays (no timer).
  if (alertCount > 0 && songActive &&
      (nowMs - alertHoldMs) >= (unsigned long)BAR_TIMEOUT_MS) {
    // Expire the front alert: shift the FIFO up, drop the newest.
    memmove(&alerts[0], &alerts[1], sizeof(NtfDto) * (alertCount - 1));
    alertCount--;
    if (alertCount > 0)
      alertHoldMs = nowMs;  // next alert starts a fresh 7s window
    barDirty = true;  // the bar content changed
  }
  barUnlock();
}

unsigned int barQueueDepth() {
  barLock();
  unsigned int depth = alertCount;
  barUnlock();
  return depth;
}

static void copyStr(char *dst, const char *src, int dstSize) {
  int n = (int)strlen(src);
  if (n > dstSize - 1) n = dstSize - 1;
  if (n < 0) n = 0;
  memcpy(dst, src, n);
  dst[n] = 0;
}

void drawNotificationBar(LGFX_ST7789_4 &d) {
  // Snapshot the state + consume the dirty flag under the lock.
  barLock();
  bool dirty = barDirty;
  barDirty = false;
  bool hasAlert = alertCount > 0;
  NtfDto a;
  if (hasAlert) a = alerts[alertCount - 1];  // front = newest
  MediaDto s = song;
  bool songOn = songActive;
  barUnlock();

  if (!dirty) return;  // event-driven: nothing new, skip

  const int x = 0, y = 0, w = 480, h = 32;
  uint16_t bg = d.color565(15, 15, 15);
  uint16_t border = d.color565(45, 45, 45);
  uint16_t accent = d.color565(0, 220, 220);
  uint16_t white = d.color565(240, 240, 240);
  uint16_t dim = d.color565(150, 150, 150);

  // Flat dark strip across the top; 1px underline marks it as a bar.
  d.fillRect(x, y, w, h, bg);
  d.fillRect(x, y + h - 1, w, 1, border);

  bool any = hasAlert || songOn;
  if (!any) return;  // empty strip only

  d.loadVLWFont("/Fonts/Conthrax_SemiBold_16px.vlw");
  const int textY = y + 20;  // baseline for the 16px font
  const int iconCx = x + 16;
  const int iconCy = y + 15;

  char line1[128];
  char line2[128];
  int iconChar = -1;  // -1 = music glyph, else monogram letter
  if (hasAlert) {
    const char *icon = a.icon;
    iconChar = (icon[0] != 0)
                   ? (int)(unsigned char)icon[0]
                   : (int)(unsigned char)a.app[0];
    if (iconChar < 33 || iconChar > 126) iconChar = '?';
    copyStr(line1, a.app, sizeof(line1));
    copyStr(line2, a.title, sizeof(line2));
  } else {
    copyStr(line1, s.artist, sizeof(line1));
    copyStr(line2, s.song, sizeof(line2));
  }

  // Icon
  if (iconChar >= 0) {
    d.fillCircle(iconCx, iconCy, 9, d.color565(30, 60, 90));
    char monogram[8];
    monogram[0] = (char)iconChar;
    monogram[1] = 0;
    d.setTextColor(accent, bg);
    d.setCursor(iconCx - 4, iconCy + 3);
    d.print(monogram);
  } else {
    // Music note glyph (head + stem + flag).
    d.fillCircle(iconCx - 2, iconCy + 4, 4, white);
    d.drawLine(iconCx + 2, iconCy + 4, iconCx + 2, iconCy - 8, white);
    d.drawLine(iconCx + 2, iconCy - 8, iconCx + 6, iconCy - 10, white);
  }

  // Text: line1 (app / artist) white, " — " separator, line2 (title / song)
  // dim, clipped to the right edge.
  int curX = x + 34;
  d.setTextColor(white, bg);
  d.setCursor(curX, textY);
  d.print(line1);
  int16_t bx, by;
  uint16_t tw, th;
  d.getTextBounds(line1, 0, 0, &bx, &by, &tw, &th);
  curX += tw;
  d.setTextColor(dim, bg);
  d.setCursor(curX, textY);
  d.print("  —  ");
  curX += d.textWidth("  —  ");

  const int maxTextX = x + w - 10;
  const char *t2 = line2;
  int i = 0;
  while (t2[i] && curX < maxTextX) {
    char c[2] = {t2[i], 0};
    int cw = d.textWidth(c);
    if (curX + cw > maxTextX) break;
    d.setTextColor(dim, bg);
    d.setCursor(curX, textY);
    d.print(c);
    curX += cw;
    i++;
  }
}
