package com.alefinot.dashboardpp.ble

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

object OtaController {

    const val MIN_BYTES = 64 * 1024

    const val MAX_BYTES = 4 * 1024 * 1024

    /** Silence to wait for the unit's next ACK before asking it where it stands. */
    const val STALL_SILENCE_MS = 12_000L

    const val RESUME_WINDOW_MS = 120_000L

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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var job: Job? = null

    /** The picked image, held in RAM on purpose: a resume has to send the rest of this file. */
    private var bytes: ByteArray? = null
    private var image: OtaImage? = null

    private val fatalFileCodes = setOf("too_big", "too_small", "file_empty", "file_unreadable", "not_implemented")

    /** True when there is a file to send and the stage allows starting or resuming. */
    fun canStart(): Boolean {
        if (bytes == null) return false
        return when (_view.value.stage) {
            OtaStage.Ready, OtaStage.Paused -> true
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

        val events = Channel<OtaEvent>(Channel.UNLIMITED)
        val collector = launch {
            link.otaEvents.collect { text -> parseOtaEvent(text)?.let { events.send(it) } }
        }
        try {
            _view.update { it.copy(stage = OtaStage.Handshaking, error = null, note = null) }
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
            var chunk = answered.coerceIn(1, OtaFrames.maxChunk(mtu))
            var nextOff = (BleJson.int(ready.fields, "off") ?: 0).coerceIn(0, img.size)
            tracker.apply(OtaEvent.BeginOk(chunk = chunk, off = nextOff))
            publish(tracker, img)

            link.setConnectionPriority(true)

            var lastAckMs = now()
            var lastPublishMs = 0L
            var writeFailures = 0

            while (true) {
                val seg = nextSegment(img.size, chunk, nextOff) ?: break

                if (nextOff - tracker.acked >= OUTSTANDING_BYTES) {
                    val paceUntil = now() + STALL_SILENCE_MS
                    while (nextOff - tracker.acked >= OUTSTANDING_BYTES && now() < paceUntil) {
                        val ev = withTimeoutOrNull(400L) { events.receive() } ?: continue
                        if (tracker.apply(ev)) {
                            publish(tracker, img)
                            return
                        }
                        if (ev is OtaEvent.Ack) lastAckMs = now()
                    }
                }

                if (!link.otaWrite(OtaFrames.data(seg.offset, data, seg.offset, seg.length))) {
                    writeFailures++
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
                        return
                    }
                    nextOff = unitOff
                    tracker.apply(OtaEvent.Ack(unitOff))
                    lastAckMs = now()
                    publish(tracker, img)
                    delay(WRITE_RETRY_DELAY_MS)
                    continue
                }
                writeFailures = 0
                tracker.wrote(seg.offset + seg.length)
                nextOff = seg.offset + seg.length

                while (true) {
                    val event = events.tryReceive().getOrNull() ?: break
                    if (event is OtaEvent.BeginOk) {
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
                    val off = unitOff
                    if (off != nextOff) {
                        nextOff = off
                    }
                    tracker.apply(OtaEvent.Ack(off))
                    lastAckMs = now()
                    publish(tracker, img)
                }
            }

            _view.update { it.copy(stage = OtaStage.Verifying, note = null) }
            api.otaEnd()
            val deadline = now() + VERIFY_WINDOW_MS
            while (now() < deadline) {
                val event = withTimeoutOrNull(2_000) { events.receive() }
                if (event == null) {
                    if (link.state.value !is BleLinkState.Ready) {
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
