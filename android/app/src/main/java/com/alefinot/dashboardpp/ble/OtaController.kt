package com.alefinot.dashboardpp.ble

import android.bluetooth.BluetoothGatt
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A picked file that cannot be sent, with the code the card shows. */
private class OtaFileException(val code: String) : Exception(code)

/**
 * The one firmware install this process is running, wherever the screen is.
 *
 * A 1.5 MB transfer outlives the settings tab that started it: the person scrolls away, rotates
 * the tablet, or the settings store re-composes underneath. `BleRuntime` exists for the same
 * reason — one link belongs to the process rather than to a screen — and the argument applies
 * harder here. Losing the object that holds the file bytes and the last ACK is what turns a
 * dropped connection into "start the whole thing again", except the unit has a clock running on
 * it and only holds the install open for a minute.
 *
 * So the picked image and the progress live here, and the firmware card only draws [view]. One
 * install at a time, because the unit itself allows exactly one.
 */
object OtaController {

    /**
     * Smallest file accepted as firmware. The image built from this tree is ~1.5 MB; anything
     * near this floor is a text file or a truncated download, and sending it wastes a minute of
     * a driver's attention to end in `end_failed`.
     */
    const val MIN_BYTES = 64 * 1024

    /**
     * Largest file read into memory. This board's flash is 4 MB and the spare OTA slot is well
     * under 1.9 MB, so anything past this is the wrong file — and reading a several-hundred-megabyte
     * `firmware.zip`-looking thing out of a Downloads folder is how an app dies mid-transfer.
     */
    const val MAX_BYTES = 4 * 1024 * 1024

    /** Silence to wait for the unit's next ACK before asking it where it stands. */
    const val STALL_SILENCE_MS = 12_000L

    /**
     * How long the unit is expected to hold an install open across a link drop.
     *
     * This is the unit's window, not the phone's: `OTA_SILENCE_MS` in `src/bt_ota.cpp` is the same
     * number, and the card's wording is derived from it so a person reading "about 2 minutes" is
     * reading the truth. It has to be long enough for a human to notice a pause, look at the card
     * and press RESUME; 60 s measured too tight on the bench on 2026-11-02, where the unit closed a
     * resumable install while the phone was still showing it as paused.
     */
    const val RESUME_WINDOW_MS = 120_000L

    /**
     * How far the phone may run ahead of the bytes the unit has confirmed on flash.
     *
     * WRITE_TYPE_NO_RESPONSE means a completed write only says the phone's stack took the bytes, not
     * that the unit has them. Without a ceiling the phone buffers a couple of hundred kilobytes into
     * Android's outgoing queue in a few seconds, the card then reports a number far behind what is
     * really on flash, and the first hiccup wedges a queue nobody can flush. Pacing against the
     * unit's own ACK keeps the outstanding window small and the progress figure honest. It must stay
     * above the unit's ACK cadence (`OTA_ACK_BYTES` in `src/bt_ota.cpp` — 4 KiB, one flash sector),
     * or the two sides deadlock. It is not the unit's cushion either, and since A3 it is not the
     * phone's ceiling either: the cushion is the handoff ring in `bt_ota.cpp` (8 slots, 7 usable),
     * and the window the pump respects is `OtaPump.paceLimitBytes` — one frame narrower than that
     * ring, because the frame that fills it is the frame the unit refuses (`bad_frame ring full`).
     */
    const val OUTSTANDING_BYTES = 32 * 1024

    /** Write attempts in a row before the transfer gives up and offers RESUME. */
    const val MAX_WRITE_FAILURES = 3

    /** Backoff between those attempts, so a busy unit gets a moment rather than another write. */
    const val WRITE_RETRY_DELAY_MS = 300L

    /** How long to wait for the unit's verdict after the last byte went out. */
    const val VERIFY_WINDOW_MS = 60_000L

    /** A view update at most this often, so a 3000-write transfer does not recompose 3000 times. */
    const val PUBLISH_INTERVAL_MS = 250L

    private val _view = MutableStateFlow(OtaView())
    val view: StateFlow<OtaView> = _view.asStateFlow()

    /**
     * The controller's own scope, deliberately not the screen's.
     *
     * The settings tab is a composable that comes and goes, and leaving it must not cancel a
     * read or a transfer that is 900 KB into a 1.5 MB image. `SupervisorJob` so one failed
     * launch does not take the session down, `Main` because everything downstream here is
     * suspending rather than blocking — the GATT calls hand off to the binder thread and the
     * file read hops to [Dispatchers.IO].
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var job: Job? = null

    /** The picked image, held in RAM on purpose: a resume has to send the rest of this file. */
    private var bytes: ByteArray? = null
    private var image: OtaImage? = null

    /**
     * Codes that mean the file is wrong, not the transfer. Picking a different file is the only
     * way past them, so the START button stays quiet and CLEAR is the way out.
     */
    private val fatalFileCodes = setOf("too_big", "too_small", "file_empty", "file_unreadable", "not_implemented")

    /** True when there is a file to send and the stage allows starting or resuming. */
    fun canStart(): Boolean {
        if (bytes == null) return false
        return when (_view.value.stage) {
            OtaStage.Ready, OtaStage.Paused -> true
            // A failed transfer is worth another press when the file itself was not the problem.
            // Airtime corrupts: a CRC failure at 96 % is usually the radio, not the file, and
            // making the driver hunt through the file picker to send the same bytes again is
            // busywork. The unit has closed the failed install, so a fresh `ota.begin` is legal;
            // if it still holds one open (a link that dropped mid-transfer), it answers
            // `already_active` and the card tells the driver to RESUME instead.
            OtaStage.Failed -> _view.value.error !in fatalFileCodes
            else -> false
        }
    }

    /** True when a transfer is running right now. */
    fun busy(): Boolean = job?.isActive == true

    /** Reads and understands a picked file. Nothing reaches the unit from here. */
    fun pick(context: Context, uri: Uri, name: String) {
        if (job?.isActive == true) return
        bytes = null
        image = null
        _view.value = OtaView(stage = OtaStage.Reading, fileName = name)
        job = scope.launch {
            val outcome = try {
                withContext(Dispatchers.IO) { readImage(context, uri) }
            } catch (e: OtaFileException) {
                _view.update { it.copy(stage = OtaStage.Failed, error = e.code) }
                return@launch
            } catch (e: Exception) {
                _view.update { it.copy(stage = OtaStage.Failed, error = "file_unreadable") }
                return@launch
            }
            val probe = OtaImages.probe(outcome)
            bytes = outcome
            image = probe
            _view.update {
                it.copy(
                    stage = OtaStage.Ready,
                    image = probe,
                    total = probe.size,
                    sent = 0,
                    acked = 0,
                    error = null,
                    note = versionNote(probe),
                )
            }
        }
    }

    /** Starts (or resumes) the transfer. Ignored unless a file has been read. */
    fun start(context: Context) {
        if (job?.isActive == true) return
        val data = bytes ?: return
        val img = image ?: return
        job = scope.launch { transfer(context, data, img) }
    }

    /**
     * Throws away the picked file and the view.
     *
     * If an install was actually open on the unit, `ota.abort` goes out first — leaving it open
     * would keep the unit's quiet window running (no telemetry, dashboard parked) for the rest
     * of its timeout, which looks like a frozen dashboard rather than a cancelled update.
     */
    fun drop(context: Context) {
        val wasRunning = job?.isActive == true
        val stage = _view.value.stage
        val name = _view.value.fileName
        job?.cancel()
        job = null
        bytes = null
        image = null
        _view.value = OtaView(stage = OtaStage.Failed, fileName = name, error = "cancelled")
        if (wasRunning && stage in setOf(OtaStage.Handshaking, OtaStage.Sending, OtaStage.Verifying)) {
            scope.launch {
                // Hand the interval back before the abort: STOP cancels the transfer job, and a job
                // already past its last suspension point may never reach its `finally`. This launch
                // is the one thing that is certain to run when a driver hits STOP mid-transfer.
                runCatching { BleRuntime.link(context).setConnectionPriority(false) }
                runCatching { BleApi(BleRuntime.link(context)).otaAbort() }
            }
        }
    }

    /** Back to a clean card. Refused while a transfer is running. */
    fun reset() {
        if (job?.isActive == true) return
        bytes = null
        image = null
        _view.value = OtaView()
    }

    // ------------------------------------------------------------------
    // The transfer
    // ------------------------------------------------------------------

    private suspend fun readImage(context: Context, uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream(256 * 1024)
            val block = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val n = input.read(block)
                if (n < 0) break
                total += n
                // One byte past the cap is enough to know, without reading a giant file into RAM.
                if (total > MAX_BYTES) throw OtaFileException("too_big")
                out.write(block, 0, n)
            }
            when {
                total == 0 -> throw OtaFileException("file_empty")
                total < MIN_BYTES -> throw OtaFileException("too_small")
                else -> out.toByteArray()
            }
        } ?: throw OtaFileException("file_unreadable")

    private fun versionNote(probe: OtaImage): String? = when {
        probe.versionTag == null ->
            "This file carries no version marker, so nothing here can tell you which version it " +
                "is or warn you when it is the one already on the unit."
        else -> "This file says it is " + probe.versionTag + ". Whether it is the right build for " +
            "this board is still your call — a firmware file is a firmware file."
    }

    /** "about a minute", from the window the unit is expected to hold an install open. */
    private fun resumeWindowWords(): String {
        val seconds = RESUME_WINDOW_MS / 1000L
        return when {
            seconds % 60L == 0L && seconds >= 60L ->
                "about " + (if (seconds == 60L) "a minute" else "${seconds / 60} minutes")
            else -> "about $seconds seconds"
        }
    }

    private suspend fun CoroutineScope.transfer(context: Context, data: ByteArray, img: OtaImage) {
        val link = BleRuntime.link(context)
        val api = BleApi(link)
        val tracker = OtaTracker(img.size)
        val startedAtMs = System.nanoTime() / 1_000_000
        fun now(): Long = System.nanoTime() / 1_000_000

        // ACKs and the terminal verdict arrive while the write loop is busy, so they land in a
        // channel the loop drains between chunks instead of being awaited where they arrive.
        val events = Channel<OtaEvent>(Channel.UNLIMITED)
        val collector = launch {
            link.otaEvents.collect { text -> parseOtaEvent(text)?.let { events.send(it) } }
        }
        try {
            _view.update { it.copy(stage = OtaStage.Handshaking, error = null, note = null) }
            // The OTA characteristic has been in the GATT profile since the profile exists, but a
            // unit built before the BLE installer has nothing behind it. `ota.begin` would answer
            // `not_implemented` anyway; checking the discovery result says it before 1.5 MB has
            // been read into memory for nothing.
            if (!link.otaAvailable()) {
                tracker.fail("not_implemented")
                publish(tracker, img)
                return
            }
            val ready = api.otaBegin(img.size, img.crc32)
            if (!ready.ok) {
                tracker.fail(ready.error ?: "unknown", 0)
                publish(tracker, img)
                return
            }
            val mtu = (link.state.value as? BleLinkState.Ready)?.mtu ?: 23
            val answered = BleJson.int(ready.fields, "chunk") ?: 0
            // The unit picks the chunk size; the phone clamps it to what its own ATT writes can
            // carry, so a unit answering 4096 because its own MTU is larger does not get writes
            // the phone's stack quietly drops.
            var chunk = answered.coerceIn(1, OtaFrames.maxChunk(mtu))
            var nextOff = (BleJson.int(ready.fields, "off") ?: 0).coerceIn(0, img.size)
            tracker.apply(OtaEvent.BeginOk(chunk = chunk, off = nextOff))
            publish(tracker, img)

            // Ask for the fast connection interval here, not back at connect time: an install is the
            // only thing this app does where the interval is the whole story, and holding it past the
            // chunk phase is what the field reports of links dropping under HIGH are about
            // (`INSTALL-SPEED-PLAN-2026-11-03`, A2). The answer is deliberately ignored - Android
            // gives no way to read back the interval that was agreed - so the number that says
            // whether it worked is the writes/s the card now prints, not this return value.
            link.setConnectionPriority(true)

            var lastAckMs = now()
            var lastPublishMs = 0L
            var writeFailures = 0

            // A3 (`INSTALL-SPEED-PLAN-2026-11-03`): several frames travel at once instead of one.
            // The bench says the phone, not the radio, was the cost: one write completed every
            // ~23 ms while the unit's handoff ring held 2-3 of its 7 frames and its flash drain
            // waited up to 125 ms between passes. The window that keeps that depth safe is the
            // unit's ring - see `OtaPump`, and `bad_frame ring full` in `src/bt_ota.cpp` for what
            // happens if it is exceeded.
            val paceBytes = OtaPump.paceLimitBytes(chunk)

            /**
             * Wait for the stack to finish sending what it has, bounded by the pipe's own depth.
             *
             * Used wherever the phone is about to ask the unit where the image really stands: a
             * status answer that arrives while our own writes are still queued describes a link that
             * has not finished talking, and continuing from it re-sends bytes the queued frames are
             * about to deliver - which the unit answers with `offset_mismatch`.
             */
            suspend fun drainPipe() {
                var drained = 0
                while (link.otaWritePending() > 0 && drained < OtaPump.PIPE_FRAMES) {
                    if (link.otaWriteAwait() == null) break
                    drained++
                }
                link.otaPipeReset()
            }

            /**
             * A frame the stack would not take, or one whose callback never came.
             *
             * The unit's answer is the only thing that says where the image really is, so ask it and
             * continue from there - or stop and offer RESUME when the link itself looks gone.
             * Returns true when the caller has to leave the loop.
             */
            suspend fun writeAttemptFailed(): Boolean {
                writeFailures++
                drainPipe()
                val unitOff = settledOffset(read = {
                    val s = api.otaStatus()
                    if (s.ok) (BleJson.int(s.fields, "off") ?: 0).coerceIn(0, img.size) else null
                })
                if (writeFailures >= MAX_WRITE_FAILURES || unitOff == null ||
                    link.state.value !is BleLinkState.Ready
                ) {
                    val live = link.state.value is BleLinkState.Ready
                    _view.update {
                        it.copy(
                            stage = OtaStage.Paused,
                            note = "The unit has " + tracker.acked + " of " + img.size +
                                " bytes on flash" +
                                if (live) {
                                    ". The link is still up; press RESUME and it continues from " +
                                        "where the unit actually is."
                                } else {
                                    ". The link dropped. The unit holds the install open for " +
                                        resumeWindowWords() + ": reconnect on the link card, " +
                                        "then press RESUME and it continues from there."
                                },
                        )
                    }
                    return true
                }
                // The unit's number wins in both directions. It is the first byte the unit still
                // needs: writing below it re-sends bytes it already accepted (which is how A3's first
                // run ended, `offset_mismatch`), and writing above it skips bytes it never got.
                nextOff = unitOff
                tracker.apply(OtaEvent.Ack(unitOff))
                lastAckMs = now()
                publish(tracker, img)
                delay(WRITE_RETRY_DELAY_MS)
                return false
            }

            while (true) {
                // ---- hand the pipe everything it may carry ----
                var refused: OtaWriteStart? = null
                while (OtaPump.canIssue(link.otaWritePending(), nextOff - tracker.acked, chunk)) {
                    val seg = nextSegment(img.size, chunk, nextOff) ?: break
                    val start = link.otaWriteStart(OtaFrames.data(seg.offset, data, seg.offset, seg.length))
                    if (start != OtaWriteStart.Started) {
                        refused = start
                        break
                    }
                    writeFailures = 0
                    tracker.wrote(seg.offset + seg.length)
                    nextOff = seg.offset + seg.length
                }
                // A full pipe is not a failure - the loop waits for a completion below and tries
                // again. A write refused while nothing at all is in flight is: the stack is not
                // busy, it is gone.
                if (refused == OtaWriteStart.Unavailable ||
                    (refused == OtaWriteStart.Busy && link.otaWritePending() == 0)
                ) {
                    if (writeAttemptFailed()) return
                    continue
                }

                // Every byte handed over and the stack has sent all of it: the image is on the wire.
                // `ota.end` goes to a different characteristic, so its order against writes still in
                // the queue is not guaranteed - which is exactly why the pipe has to be empty here.
                if (nextSegment(img.size, chunk, nextOff) == null && link.otaWritePending() == 0) break

                if (nextSegment(img.size, chunk, nextOff) != null &&
                    nextOff - tracker.acked >= paceBytes && link.otaWritePending() == 0
                ) {
                    // Pace against the unit, not against the phone's own speed. The write callback
                    // only says Android took the bytes; the unit's ACK is the only thing that says
                    // they are on flash. Running further ahead than this buffers the image into the
                    // stack, makes the progress figure lag reality by a couple of hundred KB, and
                    // leaves a queue that cannot be flushed when the link hiccups.
                    val paceUntil = now() + STALL_SILENCE_MS
                    while (nextOff - tracker.acked >= paceBytes && now() < paceUntil) {
                        val ev = withTimeoutOrNull(400L) { events.receive() } ?: continue
                        if (tracker.apply(ev)) {
                            publish(tracker, img)
                            return
                        }
                        if (ev is OtaEvent.Ack) lastAckMs = now()
                    }
                    // Falls through whether or not an ACK arrived: the stall check below asks the
                    // unit where it is if the silence has run long enough.
                } else if (link.otaWritePending() > 0) {
                    // The pipe's tick: wait for the next frame to leave the phone. A completion with
                    // a non-SUCCESS status, or none at all, is a write that may not have landed -
                    // the same case the old sequential path treated as a failed write.
                    val status = link.otaWriteAwait()
                    if (status == null || status != BluetoothGatt.GATT_SUCCESS) {
                        if (writeAttemptFailed()) return
                        continue
                    }
                } else {
                    // Nothing in flight and nothing to issue (the last frame is short, or the unit's
                    // ACK is the only thing that can free the window): let its events wake us.
                    val ev = withTimeoutOrNull(400L) { events.receive() }
                    if (ev != null) {
                        if (tracker.apply(ev)) {
                            publish(tracker, img)
                            return
                        }
                        if (ev is OtaEvent.Ack) lastAckMs = now()
                    }
                }

                while (true) {
                    val event = events.tryReceive().getOrNull() ?: break
                    if (event is OtaEvent.BeginOk) {
                        // The unit answers `ota.begin` twice - in the RPC reply (already applied
                        // above) and as a `begin_ok` event on the OTA characteristic. Only the
                        // second answer before the first write is worth adopting; after that the
                        // event is an echo, and taking its offset back would put the phone behind
                        // bytes the unit already has on flash (see `adoptBeginOkOffset`).
                        val adopted = adoptBeginOkOffset(nextOff, event.off)
                        if (adopted != nextOff) {
                            chunk = event.chunk.coerceIn(1, OtaFrames.maxChunk(mtu))
                            nextOff = adopted.coerceIn(0, img.size)
                        }
                    }
                    if (tracker.apply(event)) {
                        publish(tracker, img)
                        return
                    }
                    if (event is OtaEvent.Ack) lastAckMs = now()
                }

                if (now() - lastPublishMs >= PUBLISH_INTERVAL_MS) {
                    lastPublishMs = now()
                    publish(tracker, img)
                }

                if (now() - lastAckMs >= STALL_SILENCE_MS) {
                    // No ACK for a while. Ask rather than guess: the answer either shows real
                    // progress (ACK frames simply lag the writes) or shows the unit is behind
                    // what the phone thinks it sent. The pipe goes quiet first, for the same reason
                    // a failed write does: with frames still in the phone, the unit's offset is
                    // behind by exactly those frames and re-sending them is a mismatch.
                    drainPipe()
                    val unitOff = settledOffset(read = {
                        val s = api.otaStatus()
                        if (s.ok) (BleJson.int(s.fields, "off") ?: 0).coerceIn(0, img.size) else null
                    })
                    if (unitOff == null) {
                        _view.update {
                            it.copy(
                                stage = OtaStage.Paused,
                                note = "The unit stopped answering at " + tracker.acked + " of " +
                                    img.size + " bytes. Reconnect on the link card and press " +
                                    "RESUME; if its window has closed, the install restarts.",
                            )
                        }
                        return
                    }
                    // Continue exactly where the unit says it is, forwards as well as backwards. The
                    // number is settled (its ring has had time to drain), so it is the real next byte
                    // the unit needs - and that is the only offset its own check accepts.
                    nextOff = unitOff
                    tracker.apply(OtaEvent.Ack(unitOff))
                    lastAckMs = now()
                    publish(tracker, img)
                }
            }

            // Every byte is on the wire. The unit now folds its own CRC-32 check, then the ESP32
            // image gate runs inside Update.end(), then otadata flips.
            _view.update { it.copy(stage = OtaStage.Verifying, note = null) }
            api.otaEnd()
            // Measured from the end of the transfer, not from its start. The window has to cover
            // the unit's own verify-and-flip work, which lands 1-2 s after the last byte - while
            // the transfer itself ran two minutes. Counting it from the start of the transfer made
            // every successful install answer "the unit heard nothing": measured on the bench on
            // 2026-11-02, on an install the unit had in fact accepted, booted and reported.
            val deadline = now() + VERIFY_WINDOW_MS
            while (now() < deadline) {
                val event = withTimeoutOrNull(2_000) { events.receive() }
                if (event == null) {
                    if (link.state.value !is BleLinkState.Ready) {
                        // The unit rebooted into the new image before it could get a word out.
                        // That is what "done" looks like when the reboot beats the last notify.
                        // Publish first: this is the last chance to leave the install's seconds,
                        // rate and write count on the card, and a field install has no bench log
                        // to read them out of afterwards.
                        publish(tracker, img)
                        _view.update {
                            it.copy(
                                stage = OtaStage.Installed,
                                note = "The link dropped while the unit was finishing. Reconnect " +
                                    "and the version on this card tells you what it is running.",
                            )
                        }
                        return
                    }
                    continue
                }
                if (tracker.apply(event)) {
                    publish(tracker, img)
                    return
                }
                if (event is OtaEvent.Ack) publish(tracker, img)
            }
            tracker.fail("stalled")
            publish(tracker, img)
        } finally {
            collector.cancel()
            // The ordinary interval comes back whatever ended the chunk phase - done, failed,
            // paused, aborted, or an exception - because that is precisely the set of paths a call
            // planted at each `return` forgets one of. A phone left on the fast interval burns
            // battery for nothing, and some stacks answer a held HIGH with a dropped link.
            link.setConnectionPriority(false)
        }
    }

    private fun publish(tracker: OtaTracker, img: OtaImage) {
        _view.update {
            it.copy(
                stage = tracker.stage,
                image = img,
                fileName = it.fileName,
                total = img.size,
                sent = tracker.sent,
                acked = tracker.acked,
                bytesPerSecond = tracker.bytesPerSecond(),
                elapsedSeconds = tracker.elapsedSeconds(),
                writesPerSecond = tracker.writesPerSecond(),
                writes = tracker.writes,
                error = tracker.error,
            )
        }
    }
}
