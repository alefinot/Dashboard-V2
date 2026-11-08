package com.alefinot.dashboardpp.ble

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A GATT callback says what happened to *a* client. It never says which one, and the app used to
 * answer that question by assumption: one `BluetoothGattCallback` instance is handed to every
 * `connectGatt()`, and every callback completed whatever operation happened to be armed at the
 * moment it fired.
 *
 * That is not a theoretical hole. On the bench (SM-T590, 2026-11-07) the *previous* client's
 * `STATE_DISCONNECTED` arrived 18 ms into a fresh attempt, completed the *new* attempt's
 * `connectDone` with `false`, and the teardown that followed dropped a link that had been alive for
 * 7 milliseconds:
 *
 * ```
 * 13:06:59.812 teardown cause=gatt status=133
 * 13:06:59.813 dial patient address=94:B5:55:F8:B8:5A autoConnect=true
 * 13:06:59.831 link failed reason=disconnected while an attempt runs; that attempt owns it
 * 13:07:00.553 gatt connected status=0
 * 13:07:00.556 mtu negotiated=23              <- requestMtu refused, answered in 1 ms
 * 13:07:00.559 discover refused by the stack attempt=1
 * 13:07:00.560 teardown cause=connect failed disconnected linkAgeMs=7
 * ```
 *
 * These are structural guards, and they are honest about being structural: `BluetoothGatt` cannot be
 * constructed in a JVM test, so what is pinned here is the shape that makes the race impossible -
 * every callback checking whose callback it is before it touches anything. The behavioural proof is
 * the bench run in `Implementation plans/RECONNECT-ROBUSTNESS-2026-11-07.md` §5 rows 1-3, and the
 * `ignored event=` log line this class emits is what makes a regression visible in a capture.
 */
class CallbacksBelongToTheirClientTest {

    /** Every callback must answer for its own client before it completes, notifies or tears down. */
    @Test
    fun everyCallbackChecksItStillOwnsTheClientItWasGiven() {
        val cb = codeOnly(mainSource("BleLink.kt"))
            .substringAfter("private val gattCallback = object : BluetoothGattCallback() {")
        val segments = cb.split("override fun").drop(1)
        assertTrue(
            "expected the seven GATT callbacks, found ${segments.size}",
            segments.size >= 7,
        )
        for (seg in segments) {
            val name = seg.substringBefore("(").trim()
            val guard = seg.indexOf("ownsClient(")
            assertTrue("$name must start by asking whether it still owns the client", guard >= 0)
            val firstTouch = Regex("\\w+Done\\?\\.complete\\(").find(seg)?.range?.first ?: Int.MAX_VALUE
            val firstNotify = seg.indexOf("onNotify(").let { if (it < 0) Int.MAX_VALUE else it }
            assertTrue(
                "$name touches link state before checking ownership: ${seg.take(160)}",
                guard < minOf(firstTouch, firstNotify),
            )
        }
    }

    /**
     * `BluetoothGatt` does not override `equals()`, so reference equality is the real question.
     * A `.equals()` here would compile, read the same, and answer something else.
     */
    @Test
    fun ownershipIsDecidedByIdentityAndIsLoudWhenItFails() {
        val src = mainSource("BleLink.kt")
        val helper = codeOnly(src).section("private fun ownsClient(")
        assertTrue("ownership must be reference identity", helper.contains("from === live"))
        assertTrue("a refused callback must be logged, not silently dropped", helper.contains("Log.d("))
        assertTrue("the log must say which callback was dropped", helper.contains("event="))
        assertTrue(
            "the log must distinguish the two clients, which is the whole point of the line",
            helper.contains("identityHashCode"),
        )
    }

    /**
     * The guard drops a dead client's callbacks, so the teardown itself has to answer everything
     * that client was waiting on. An operation left armed here would sit until its own timeout -
     * `bond`'s poll out of a 180 s budget, `connectDone` out of 20 s - which is how a clean
     * disconnect becomes three minutes of a card reading PAIRING.
     */
    @Test
    fun aTeardownAnswersEveryOperationThatClientWasWaitingOn() {
        val close = codeOnly(mainSource("BleLink.kt")).section("private fun closeGatt(")
        for (op in listOf(
            "connectDone", "bondDone", "serviceDone", "mtuDone", "descriptorDone", "writeDone",
            "otaWriteDone",
        )) {
            assertTrue("$op must be completed by closeGatt", Regex("$op\\?\\.complete\\(").containsMatchIn(close))
            assertTrue("$op must be cleared by closeGatt", Regex("$op = null").containsMatchIn(close))
        }
        // Order is the fix, not tidiness: null the live client first, so a callback already running
        // on the GATT thread is refused by the guard rather than completing something mid-teardown.
        val nulls = close.indexOf("gatt = null")
        val completes = Regex("\\w+Done\\?\\.complete\\(").find(close)!!.range.first
        assertTrue("closeGatt must drop the client before answering its operations", nulls in 0 until completes)
        // And the settle floor needs to know what was closed and when.
        assertTrue(close.contains("lastCloseAtMs = System.currentTimeMillis()"))
        assertTrue(close.contains("lastClosedAddress = client.device.address"))
    }

    /**
     * A cancelled hunt is not a radio event. It used to be reported as one, because
     * `CancellationException` is an `Exception` and the method ended in a catch-all that returned
     * `false` - so the ladder spent a rung and printed a reason string out of the coroutine
     * machinery: `link failed reason=StandaloneCoroutine was cancelled`.
     */
    @Test
    fun cancellationClosesTheClientAndIsNotReportedAsARadioFailure() {
        val src = codeOnly(mainSource("BleLink.kt"))
        val catch = src.indexOf("catch (e: CancellationException)")
        assertTrue("connect() must answer cancellation separately from failure", catch >= 0)
        val body = src.substring(catch, catch + 1_200)
        assertTrue("a cancelled attempt must not leave a client registered", body.contains("closeGatt("))
        assertTrue("the cancellation must keep travelling", body.contains("throw e"))
        // Idle, not Failed: the reconnect guards count Connecting and Pairing as a link being
        // built, so a state left behind here parks every rearm behind an attempt that is gone.
        assertTrue("a cancelled attempt must leave the link Idle", body.contains("BleLinkState.Idle"))
        assertTrue(
            "the cancellation catch must come before the catch-all that would swallow it",
            catch < src.indexOf("if (_state.value is BleLinkState.Failed) {"),
        )
    }

    /**
     * Redialling the same address a few milliseconds after closing it is what the stack punishes:
     * the bench run's `requestMtu` refusal, its `discover refused`, and its `services=0` all sit on
     * top of a redial that started too soon. So the pause is part of the contract.
     */
    @Test
    fun rediallingTheSameAddressWaitsForTheStackToSettle() {
        val src = codeOnly(mainSource("BleLink.kt"))
        assertTrue(src.contains("const val CLOSE_SETTLE_MS"))
        val floor = Regex("lastClosedAddress != address[\\s\\S]{0,600}delay\\(").containsMatchIn(src)
        assertTrue("the settle floor must actually delay, not merely notice", floor)
        assertTrue(
            "only the same address owes the pause; another unit is another client",
            src.contains("lastClosedAddress != address"),
        )
        assertTrue(
            "the settle must be logged, and must say which dial it is holding back - an " +
                "unexplained wait reads as a hang",
            src.contains("redial settle waited") && src.contains("reason=\$why"),
        )

        // Every dial owes it, not just the first one. The bench's `linkAgeMs=7` cascade (MTU
        // refused, discover refused) sits on the direct -> patient handoff: that second
        // `connectGatt()` follows a `close()` a few milliseconds earlier, in the same breath.
        val dials = Regex("device\\.connectGatt\\(").findAll(src).toList()
        assertTrue("expected the direct and the patient dial, found ${dials.size}", dials.size == 2)
        val patient = src.substringAfter("patient retry after a direct timeout").substringBefore("device.connectGatt(")
        assertTrue(
            "the patient dial must wait out the same floor: it is the one that follows a close()",
            patient.contains("settleFor("),
        )
        val entry = src.substringBefore("dial direct address=")
        assertTrue("and so must the entry dial", entry.contains("settleFor("))
    }

    /**
     * The seven handshake operations are assigned on the connect coroutine's thread and completed
     * on a binder callback thread. Without a visibility guarantee the callback may read a stale
     * `null` and drop the answer it was carrying, and that reads exactly like a stack refusing the
     * call - so it is part of the same bug, and part of the same fix.
     */
    @Test
    fun everyHandshakeFieldIsVisibleAcrossTheThreadsThatShareIt() {
        val src = mainSource("BleLink.kt")
        for (name in listOf(
            "connectDone", "bondDone", "serviceDone", "mtuDone", "descriptorDone", "writeDone",
            "otaWriteDone",
        )) {
            assertTrue(
                "$name crosses threads and must be @Volatile",
                Regex("@Volatile\\s+private var $name:").containsMatchIn(src),
            )
        }
    }

    /**
     * Asking "does this phone still hold the bond for that address?" is part of reconnecting: it
     * decides what FORGET THIS UNIT can honestly claim, and whether a card is dialable at all. On
     * Android 12+ `getRemoteDevice` and `bondState` are gated on `BLUETOOTH_CONNECT`, so the answer
     * can arrive as a `SecurityException` - and the one caller that asks it unguarded runs
     * uncaught in the view model's scope, which is a crash while pressing a button.
     *
     * Caught by the whole-tree call-site audit of 2026-11-07. Pinned here because the API 31+ path
     * has no device on this bench to find it any other way.
     */
    @Test
    fun askingAboutABondNeverTakesTheProcessWithIt() {
        val src = codeOnly(mainSource("BleLink.kt"))
        val body = src.section("fun isBonded(")
        assertTrue(
            "isBonded must survive a permission refusal, not just a malformed address",
            body.contains("catch (e: SecurityException)"),
        )
        assertTrue("and must say so in the log the capture reads", body.contains("Log.w("))
    }

    // ------------------------------------------------------------------ helpers
    /** Comments stripped: this guard is about the code path, and the reasoning lives in prose. */
    private fun codeOnly(src: String): String = src
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("//.*"), " ")

    /** From [openingSignature] to the end of that member, by its own dedented closing brace. */
    private fun String.section(openingSignature: String): String {
        val start = indexOf(openingSignature)
        assertTrue("no $openingSignature in the source", start >= 0)
        // `}` alone is not enough: a `} catch (` line is a `    }` too, and cutting there would
        // silently hide the arms this guard exists to check.
        val end = indexOf("\n    }\n", start)
        assertTrue("unterminated $openingSignature", end > start)
        return substring(start, end)
    }

    private fun mainSource(name: String): String {
        val f = File(File(moduleDir(), "src/main/java/com/alefinot/dashboardpp/ble"), name)
        assertTrue("cannot find $f", f.isFile)
        // Normalised once, here: the sources on disk are CRLF, and every brace-matching helper below
        // looks for a line-anchored `}`. Matching `\n` against `\r\n` silently finds nothing, which
        // is the worst way for a guard to fail - it reports the file as unterminated, not itself.
        return f.readText().replace("\r\n", "\n")
    }

    /**
     * Gradle runs a module's tests with that module as the working directory; the CLI and the IDE
     * disagree about it, so each level upward is tried. Same reasoning as
     * `ConnectActionsOnOnePageTest.moduleDir` - a guard that cannot find its own input is worse than
     * no guard, because it teaches you to ignore a red run.
     */
    private fun moduleDir(): File {
        val marker = "src/main/java/com/alefinot/dashboardpp/ble"
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            for (cand in listOf(File(dir, "android/app"), File(dir, "app"), dir)) {
                if (File(cand, marker).isDirectory) return cand
            }
            dir = dir.parentFile
        }
        throw IllegalStateException(
            "cannot reach the app module walking up from " + System.getProperty("user.dir"),
        )
    }
}
