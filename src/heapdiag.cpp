// heapdiag.cpp — see heapdiag.h for what each line means and why the units are
// what they are. Added by the heap-recovery plan (Implementation plans/
// HEAP-RECOVERY-2026-11-02.md), Phase 0: measure before trimming.
#include "heapdiag.h"

#include "dashboard.h"  // logPrintf

#include <stdio.h>

#include <esp_heap_caps.h>
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>

// A fixed table of the tasks we care about. Eight slots covers loopTask, the
// three long-lived app tasks and the two CPU probes with room to spare, and it
// costs 8 * (4 + 4) bytes of pointers plus the literals (which live in flash).
static const int kMaxTasks = 8;
static const char *s_taskName[kMaxTasks];
static void *s_taskHandle[kMaxTasks];
static int s_taskCount = 0;

// When the stack report goes out: twice early (so a boot-time overflow shows up
// before anyone starts a settings save), then every minute as a soak baseline.
static const unsigned long kStackMarksEarlyMs[2] = {10000UL, 60000UL};
static const unsigned long kStackMarkPeriodMs = 60000UL;
static unsigned long s_nextStackMarkMs = kStackMarksEarlyMs[0];
static int s_earlyMarkIdx = 0;
static size_t s_rxHigh = 0;

uint32_t heapInternalFree() {
  return (uint32_t)heap_caps_get_free_size(MALLOC_CAP_INTERNAL);
}

uint32_t heapMaxAllocTotal() {
  return (uint32_t)heap_caps_get_largest_free_block(MALLOC_CAP_DEFAULT);
}

uint32_t heapMaxAllocInternal() {
  return (uint32_t)heap_caps_get_largest_free_block(MALLOC_CAP_INTERNAL);
}

void heapStage(const char *tag) {
  logPrintf("HEAPSTAGE %-18s free=%lu maxalloc=%lu internal=%lu intmax=%lu\n", tag,
            (unsigned long)ESP.getFreeHeap(), (unsigned long)ESP.getMaxAllocHeap(),
            (unsigned long)heapInternalFree(), (unsigned long)heapMaxAllocInternal());
}

void heapDiagAddTask(const char *name, void *handle) {
  if (handle == NULL || name == NULL) return;
  for (int i = 0; i < s_taskCount; i++) {
    if (s_taskHandle[i] == handle) return;  // registered twice - keep one row
  }
  if (s_taskCount >= kMaxTasks) {
    logPrintf("HEAPDIAG: task table full (%d), '%s' not tracked\n", s_taskCount, name);
    return;
  }
  s_taskName[s_taskCount] = name;
  s_taskHandle[s_taskCount] = handle;
  s_taskCount++;
}

void heapDiagTick(unsigned long nowMs) {
  if (nowMs < s_nextStackMarkMs) return;
  if (s_earlyMarkIdx < 2) {
    s_earlyMarkIdx++;
    s_nextStackMarkMs = (s_earlyMarkIdx < 2) ? kStackMarksEarlyMs[s_earlyMarkIdx]
                                             : kStackMarksEarlyMs[1] + kStackMarkPeriodMs;
  } else {
    s_nextStackMarkMs = nowMs + kStackMarkPeriodMs;
  }

  // One line per task, each labelled on its own: the app's log tail keeps the
  // last BT_LOG_TAIL bytes, and a wrapped multi-task line would cut mid-row.
  for (int i = 0; i < s_taskCount; i++) {
    UBaseType_t hwm = uxTaskGetStackHighWaterMark((TaskHandle_t)s_taskHandle[i]);
    logPrintf("STACKHWM t=%lus %s free=%u B\n", nowMs / 1000UL, s_taskName[i], (unsigned)hwm);
  }
  logPrintf("STACKHWM t=%lus heap free=%lu maxalloc=%lu internal=%lu intmax=%lu rx_high=%u\n",
            nowMs / 1000UL, (unsigned long)ESP.getFreeHeap(), (unsigned long)ESP.getMaxAllocHeap(),
            (unsigned long)heapInternalFree(), (unsigned long)heapMaxAllocInternal(),
            (unsigned)btRxHighWater());
}

void btNoteRxUse(size_t used) {
  // Written from the Bluedroid callback task, read from BtTask/loopTask. A
  // 32-bit aligned store is not torn on this core, and the only thing that could
  // be lost is one high-water update - acceptable for a diagnostic counter.
  if (used > s_rxHigh) s_rxHigh = used;
}

size_t btRxHighWater() { return s_rxHigh; }

size_t heapDiagStackJson(char *buf, size_t cap) {
  size_t n = 0;
  for (int i = 0; i < s_taskCount && n + 1 < cap; i++) {
    int w = snprintf(buf + n, cap - n, "%s\"%s\":%u", i ? "," : "", s_taskName[i],
                     (unsigned)uxTaskGetStackHighWaterMark((TaskHandle_t)s_taskHandle[i]));
    // A negative or truncated write means the buffer is full: report the rows
    // that fit rather than closing a JSON string half-way through a name.
    if (w < 0 || (size_t)w >= cap - n) break;
    n += (size_t)w;
  }
  return n;
}

size_t heapDiagHeapJson(char *buf, size_t cap) {
  int w = snprintf(buf, cap, "\"internal_free\":%lu,\"internal_max_alloc\":%lu,\"rx_high\":%u",
                   (unsigned long)heapInternalFree(), (unsigned long)heapMaxAllocInternal(),
                   (unsigned)btRxHighWater());
  if (w < 0 || (size_t)w >= cap) return 0;
  return (size_t)w;
}
