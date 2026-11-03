// heapdiag.h — heap and stack instrumentation (heap-recovery plan, Phase 0).
//
// Everything here only READS memory state and prints it. It exists because the
// 2.0.x heap work has to be decided from measurements, not guesses, and because
// the numbers that matter on this core are not the ones a stock ESP32 sketch
// prints:
//
//   * Static DRAM is charged against the heap before the heap exists (the map
//     shows dram0_0_seg ending where the heap begins), so a static byte and a
//     heap byte are the same byte. `internal=` is what that leaves.
//   * uxTaskGetStackHighWaterMark() on this core returns BYTES, not words
//     (framework-arduinoespressif32-libs/esp32/include/freertos/FreeRTOS-Kernel/
//     include/freertos/task.h: "The return value is in bytes rather than the
//     standard FreeRTOS value of words"). Dividing it by 1024, or treating it as
//     a word count, understates the spare by ~4x or ~1000x.
//
// Static fixed-size storage only (AGENTS 14); English only (AGENTS 16).
#pragma once
#include <Arduino.h>

#include <cstddef>

// Free heap in the internal (DRAM) capability - the pool our statics come out
// of, and the only one a 31 KB sprite can use on a no-PSRAM module.
uint32_t heapInternalFree();

// The largest single free block, overall and inside internal DRAM. A 31 KB
// sprite needs one block, not 31 KB of spread-out total.
uint32_t heapMaxAllocTotal();
uint32_t heapMaxAllocInternal();

// One line at a boot or work stage:
//   HEAPSTAGE <tag> free= maxalloc= internal= intmax=
void heapStage(const char *tag);

// Register a task for the stack high-water report. `name` must live as long as
// the registration (string literals at every call site), and `handle` is the
// handle from xTaskCreate. Call once per task, right after creating it.
void heapDiagAddTask(const char *name, void *handle);

// Stack high-water marks (bytes still free at the deepest point since that task
// started) at 10 s, 60 s, then every 300 s. Called from loop(); the loop task
// itself is registered with xTaskGetCurrentTaskHandle() from setup().
void heapDiagTick(unsigned long nowMs);

// Bytes of the BLE reassembly buffer in use at the deepest point since boot.
// bt.cpp reports its own usage; the perf reply reads it back.
void btNoteRxUse(size_t used);
size_t btRxHighWater();

// JSON object members for the perf reply, appended into the caller's buffer.
// Both are additive: a client that does not know the key ignores it (AGENTS 17
// is about changing or removing a field, not adding a diagnostic one).
//   "loopTask":3041,"SensorTaskCore1":2104,...   (bytes free at the HWM)
size_t heapDiagStackJson(char *buf, size_t cap);
//   "internal_free":N,"internal_max_alloc":N,"rx_high":N
size_t heapDiagHeapJson(char *buf, size_t cap);
