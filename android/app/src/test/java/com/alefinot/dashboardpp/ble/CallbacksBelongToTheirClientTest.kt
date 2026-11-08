package com.alefinot.dashboardpp.ble

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

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
        val nulls = close.indexOf("gatt = null")
        val completes = Regex("\\w+Done\\?\\.complete\\(").find(close)!!.range.first
        assertTrue("closeGatt must drop the client before answering its operations", nulls in 0 until completes)
        // And the settle floor needs to know what was closed and when.
        assertTrue(close.contains("lastCloseAtMs = System.currentTimeMillis()"))
        assertTrue(close.contains("lastClosedAddress = client.device.address"))
    }

    @Test
    fun cancellationClosesTheClientAndIsNotReportedAsARadioFailure() {
        val src = codeOnly(mainSource("BleLink.kt"))
        val catch = src.indexOf("catch (e: CancellationException)")
        assertTrue("connect() must answer cancellation separately from failure", catch >= 0)
        val body = src.substring(catch, catch + 1_200)
        assertTrue("a cancelled attempt must not leave a client registered", body.contains("closeGatt("))
        assertTrue("the cancellation must keep travelling", body.contains("throw e"))
        assertTrue("a cancelled attempt must leave the link Idle", body.contains("BleLinkState.Idle"))
        assertTrue(
            "the cancellation catch must come before the catch-all that would swallow it",
            catch < src.indexOf("if (_state.value is BleLinkState.Failed) {"),
        )
    }

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

    private fun codeOnly(src: String): String = src
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("//.*"), " ")

    /** From [openingSignature] to the end of that member, by its own dedented closing brace. */
    private fun String.section(openingSignature: String): String {
        val start = indexOf(openingSignature)
        assertTrue("no $openingSignature in the source", start >= 0)
        val end = indexOf("\n    }\n", start)
        assertTrue("unterminated $openingSignature", end > start)
        return substring(start, end)
    }

    private fun mainSource(name: String): String {
        val f = File(File(moduleDir(), "src/main/java/com/alefinot/dashboardpp/ble"), name)
        assertTrue("cannot find $f", f.isFile)
        return f.readText().replace("\r\n", "\n")
    }

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
