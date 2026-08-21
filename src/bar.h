// Phone-companion notification / media bar (top strip of the dashboard).
//
// The ESP is a passive display for whatever the phone pushes over BLE:
//   - NOTIFICATION frames (alert / SMS / call) -> bounded FIFO (drop-oldest)
//   - MEDIA frames (current song)              -> persistent "song" state
// The bar always shows the newest alert; while a song plays, an alert only
// holds the bar for BAR_TIMEOUT_MS then falls back to the song. With no song
// an alert stays until a newer alert replaces it. See
// Implementation plans/bluetooth-weather-and-phone-notifications-plan.md.
#ifndef BAR_H
#define BAR_H

#include "dashboard.h"

struct NtfDto {
  char app[64];     // app / package name (display token)
  char title[128];  // headline
  char body[128];   // secondary line (sms / call detail)
  unsigned long epoch;  // ms since epoch (dedupe key)
  char icon[8];    // short monogram / emoji token (1-3 chars)
};

struct MediaDto {
  char artist[128];
  char song[128];
  char icon[8];   // monogram token for the artwork monogram
  bool active;    // true = playing
};

// Push a notification (newest-wins; drops the oldest when the FIFO is full).
void barPush(const NtfDto &dto);
// Set / clear the persistent song state (active=false clears it).
void barSetSong(const MediaDto &dto);
// Expire alerts that have held the bar past BAR_TIMEOUT_MS while a song
// plays. Called once per display frame from the loop task.
void barTick(unsigned long nowMs);
// Draw the top bar (region y 0..32). No-op when nothing is active.
void drawNotificationBar(LGFX_ST7789_4 &d);
// Current alert FIFO depth (0 = empty) — for the STATUS payload.
unsigned int barQueueDepth();

#endif // BAR_H
