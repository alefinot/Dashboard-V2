package com.alefinot.dashboardpp.ui.screens.settings

import com.alefinot.dashboardpp.ble.BleJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The store's promises, tested against a fake unit.
 *
 * The old settings page had the unit's own form, so these rules came free: the form
 * could not offer a value outside its own bands, and it re-read itself after a write.
 * Once the rows are drawn by this app, the rules have to be written down and tested,
 * because the failure mode of getting one wrong is a number on the screen that was
 * never accepted anywhere.
 */
class ConfigStoreTest {

    private val gateway = FakeGateway()
    private var ticks = 0
    private val clock: () -> Long = { (ticks++).toLong() }

    private fun scope() = CoroutineScope(Dispatchers.Unconfined)

    /** One group, the named rows, and a unit that already holds sensible values. */
    private fun store(
        vararg items: SchemaItem,
        hidden: List<String> = emptyList(),
        rebootKeys: List<String> = emptyList(),
        debounceMs: Long = 40,
        syncTtlMs: Long = 60_000,
    ): ConfigStore {
        gateway.stored = LinkedHashMap()
        for (item in items) {
            gateway.stored[item.key] = item.defaultValue
            item.keyY?.let { gateway.stored[it] = item.defaultY }
        }
        val schema = SettingsFixtures.schema(
            SchemaCard("Test card", items.toList()),
            hidden = hidden,
            rebootKeys = rebootKeys,
        )
        gateway.knownResetKeys = items.flatMap { listOf(it.key) + listOfNotNull(it.keyY) }.toSet()
        return ConfigStore(gateway, schema, scope(), debounceMs, syncTtlMs, clock)
    }

    private fun int(
        key: String,
        min: Double? = null,
        max: Double? = null,
        def: Int = 10,
        advanced: Boolean = false,
        reboot: Boolean = false,
        section: String? = null,
    ) = SchemaItem(
        key = key,
        kind = ValueKind.INT,
        control = RowControl.NUMBER,
        label = ConfigStore.humanize(key),
        min = min,
        max = max,
        advanced = advanced,
        reboot = reboot,
        section = section,
        defaultValue = def,
    )

    private fun bool(key: String, def: Boolean = false) = SchemaItem(
        key = key,
        kind = ValueKind.BOOL,
        control = RowControl.TOGGLE,
        label = ConfigStore.humanize(key),
        defaultValue = def,
    )

    @Test
    fun nothingIsEditableBeforeTheReadJoinsTheSchema() {
        val store = store(int("SPEED_MAX", def = 60))
        assertEquals("rows exist before anything was read", 0, store.state.value.rows.size)
        assertTrue(store.state.value.sync is SyncPhase.Idle)
        store.sync()
        assertTrue(store.state.value.sync is SyncPhase.Ready)
        assertEquals(1, store.state.value.rows.size)
    }

    @Test
    fun threeRapidEditsBecomeOneWrite() = runBlocking {
        val item = int("SPEED_MAX", def = 60)
        val store = store(item, debounceMs = 60)
        store.sync()
        gateway.writes.clear()
        gateway.writtenKeys.clear()
        store.edit(item, 61)
        store.edit(item, 62)
        store.edit(item, 63)
        assertEquals("a typed row writes at once", 0, gateway.writes.size)
        delay(300)
        assertEquals("three keystrokes made ${gateway.writes.size} writes", 1, gateway.writes.size)
        assertEquals(63L, (gateway.stored["SPEED_MAX"] as Number).toLong())
    }

    /**
     * The SAVE button's job. A debounce window is a place a value can die — the link drops,
     * the phone sleeps, someone walks off to the bike — and a driver who has just finished
     * editing wants those rows on the unit rather than in the phone's RAM with a timer on
     * them. It reports the rows it sent, and the pending count is what disables the button
     * when there is nothing left to send.
     */
    @Test
    fun saveWritesWhatIsStillWaitingWithoutWaitingForTheDebounce() = runBlocking {
        val a = int("SPEED_MAX", def = 60)
        val b = int("CRUISE_SPEED", def = 90)
        val store = store(a, b, debounceMs = 5_000)
        store.sync()
        assertEquals("a fresh read leaves nothing pending", 0, store.state.value.pending)
        store.edit(a, 61)
        store.edit(b, 91)
        assertEquals("a typed row is pending until the unit has it", 2, store.state.value.pending)
        gateway.writes.clear()
        val flush = store.flush()
        assertEquals(2, flush.rows)
        assertEquals(0, flush.notWritten)
        assertEquals("a flush does not sit out the debounce", 2, gateway.writes.size)
        assertEquals(61L, (gateway.stored["SPEED_MAX"] as Number).toLong())
        assertEquals(91L, (gateway.stored["CRUISE_SPEED"] as Number).toLong())
        assertEquals(0, store.state.value.pending)
        val pieces = gateway.writes.size
        assertEquals("nothing left to write", 0, store.flush().rows)
        assertEquals("an empty flush does not spend a round trip", pieces, gateway.writes.size)
    }

    /**
     * A row the unit refuses did not reach the unit, and the SAVE count has to say so: a button
     * that reports "wrote 2" across a refusal told the driver something that was never true.
     * It is not counted as *pending* though — the row is back on the value the unit confirmed,
     * so there is nothing left in the phone to send, and the row itself is where the reason is.
     */
    @Test
    fun saveCountsARowTheUnitRefusedAsNotReachingTheUnit() = runBlocking {
        val a = int("SPEED_MAX", def = 60)
        val b = int("CRUISE_SPEED", def = 90)
        val store = store(a, b, debounceMs = 5_000)
        store.sync()
        store.edit(a, 61)
        store.edit(b, 91)
        gateway.writeQueue.add(WriteResult(ok = false, error = "the unit said no"))
        gateway.writeQueue.add(WriteResult(ok = true))
        val flush = store.flush()
        assertEquals(2, flush.rows)
        assertEquals(1, flush.notWritten)
        assertEquals(60L, (gateway.stored["SPEED_MAX"] as Number).toLong())
        assertEquals(91L, (gateway.stored["CRUISE_SPEED"] as Number).toLong())
        assertEquals(RowStatus.FAILED, store.state.value.rows["SPEED_MAX"]?.status)
        assertEquals("the refused row is back on the unit's value", 0, store.state.value.pending)
    }

    /**
     * An `xy` row holds two keys and answers one write. Counting keys would send that row twice
     * (and charge the unit for a flash cycle it did not need), so a flush works through rows.
     */
    @Test
    fun anXyRowIsOneRowToSave() = runBlocking {
        val xy = SchemaItem(
            key = "POS_X",
            keyY = "POS_Y",
            kind = ValueKind.INT,
            control = RowControl.PAIR,
            label = "Position",
            defaultValue = 10,
            defaultY = 20,
        )
        val store = store(xy, debounceMs = 5_000)
        store.sync()
        store.edit(xy, 40, 50)
        gateway.writes.clear()
        assertEquals("two pending keys, one row", 1, store.flush().rows)
        assertEquals(0, store.flush().notWritten)
        assertEquals(1, gateway.writes.size)
        assertEquals(0, store.state.value.pending)
    }

    /**
     * A control you click writes the moment you click it; only a control you type into waits out
     * the debounce. That rule used to be proved through the header's advanced switch, which wrote
     * on its own path; the switch is gone (2026-11-08) and the rule is the store's own, so it is
     * proved through the ordinary row path now.
     */
    @Test
    fun aSwitchGoesOutWithoutWaitingForTyping() = runBlocking {
        val item = bool("ADV_MODE", def = false)
        val store = store(item, debounceMs = 5_000)
        store.sync()
        store.edit(item, true)
        assertEquals("a click waited for typing", 1, gateway.writes.size)
        assertEquals(true, gateway.stored["ADV_MODE"])
    }

    @Test
    fun aRefusedWritePutsTheRowBackToWhatTheUnitConfirmed() = runBlocking {
        val item = int("SPEED_MAX", def = 60)
        val store = store(item, debounceMs = 10)
        store.sync()
        gateway.writeQueue.addLast(WriteResult(ok = false, error = "read_only"))
        store.edit(item, 90)
        delay(200)
        val row = store.state.value.rows.getValue("SPEED_MAX")
        assertEquals(RowStatus.FAILED, row.status)
        assertEquals("the row kept a value the unit never accepted", 60L, (row.value as Number).toLong())
        assertEquals("read_only", row.message)
        assertEquals(60L, (gateway.stored["SPEED_MAX"] as Number).toLong())
    }

    @Test
    fun anOutOfRangeNumberIsClampedBeforeItLeavesThePhone() = runBlocking {
        val item = int("SPEED_MAX", min = 0.0, max = 100.0, def = 60)
        val store = store(item, debounceMs = 10)
        store.sync()
        store.edit(item, 4000)
        delay(200)
        assertEquals(
            "the unit was sent a number outside its band",
            100L,
            (gateway.stored["SPEED_MAX"] as Number).toLong(),
        )
        val note = store.state.value.rows.getValue("SPEED_MAX").message.orEmpty()
        assertTrue("the row says nothing about the clamp: $note", note.contains("maximum"))
    }

    @Test
    fun anIntRowNeverSendsAFraction() = runBlocking {
        val item = int("SPEED_MAX", min = 0.0, max = 100.0, def = 60)
        val store = store(item, debounceMs = 10)
        store.sync()
        store.edit(item, 61.7)
        delay(200)
        val sent = gateway.writtenKeys.size
        assertEquals(1, sent)
        val value = gateway.stored["SPEED_MAX"]
        assertTrue("an int row sent ${value?.javaClass}", value is Long)
        assertEquals(61L, (value as Number).toLong())
    }

    @Test
    fun aWriteThatDidNotReachFlashMarksTheRowDirty() = runBlocking {
        val item = int("SPEED_MAX", def = 60)
        val store = store(item, debounceMs = 10)
        store.sync()
        gateway.writeQueue.addLast(WriteResult(ok = true, nvsErrors = 1, failedKeys = "SPEED_MAX"))
        store.edit(item, 75)
        delay(200)
        val row = store.state.value.rows.getValue("SPEED_MAX")
        assertEquals(RowStatus.RAM_ONLY, row.status)
        assertTrue(row.message.orEmpty().contains("not written to flash"))
        assertEquals(statusNote(row), "in RAM, not in flash — in RAM only, not written to flash: SPEED_MAX")
    }

    @Test
    fun aBootOnlyRowRaisesTheRebootBadge() = runBlocking {
        val item = int("DISPLAY_ROTATION", min = 0.0, max = 3.0, def = 0, reboot = true)
        val store = store(item, debounceMs = 10, rebootKeys = listOf("DISPLAY_ROTATION"))
        store.sync()
        assertEquals(emptySet<String>(), store.state.value.rebootPending)
        store.edit(item, 1)
        delay(200)
        assertEquals(setOf("DISPLAY_ROTATION"), store.state.value.rebootPending)
    }

    @Test
    fun aResetAsksTheUnitRatherThanTypingADefault() = runBlocking {
        val item = int("SPEED_MAX", def = 60)
        val store = store(item, debounceMs = 10)
        store.sync()
        store.edit(item, 12)
        delay(200)
        store.reset(item)
        assertEquals(listOf("SPEED_MAX"), gateway.resets)
        val row = store.state.value.rows.getValue("SPEED_MAX")
        assertEquals(RowStatus.SYNCED, row.status)
        assertEquals(60L, (row.value as Number).toLong())
    }

    @Test
    fun aKeyTheUnitDoesNotKnowIsReportedNotSwallowed() = runBlocking {
        val item = int("SPEED_MAX", def = 60)
        val store = store(item)
        store.sync()
        gateway.knownResetKeys = emptySet()
        store.reset(item)
        assertEquals(RowStatus.FAILED, store.state.value.rows.getValue("SPEED_MAX").status)
        assertEquals("no such key in this firmware", store.state.value.rows.getValue("SPEED_MAX").message)
    }

    @Test
    fun aSecretRowLeftBlankWritesNothing() = runBlocking {
        val item = SchemaItem(
            key = "WIFI_PASS",
            kind = ValueKind.SECRET,
            control = RowControl.SECRET,
            label = "Password",
            defaultValue = "",
        )
        gateway.stored["WIFI_PASS"] = ""
        val schema = SettingsFixtures.schema(SchemaCard("Test card", listOf(item)))
        val store = ConfigStore(gateway, schema, scope(), 10, 60_000, clock)
        store.sync()
        val row = store.state.value.rows.getValue("WIFI_PASS")
        assertEquals("a blank secret must not read as missing", emptyList<String>(), store.state.value.missingKeys)
        assertEquals(RowStatus.SYNCED, row.status)
        gateway.writes.clear()
        store.edit(item, "")
        delay(200)
        assertEquals("a blank box cleared a stored secret", 0, gateway.writes.size)
        store.edit(item, "hunter2")
        delay(200)
        assertEquals(1, gateway.writes.size)
        assertEquals("hunter2", gateway.stored["WIFI_PASS"])
    }

    @Test
    fun aKeyTheSchemaKnowsButTheUnitDidNotSendIsMarkedMissing() = runBlocking {
        val known = int("SPEED_MAX", def = 60)
        val gone = int("NEW_IN_FIRMWARE", def = 3)
        val store = store(known, gone)
        gateway.stored.remove("NEW_IN_FIRMWARE")
        store.sync()
        assertEquals(listOf("NEW_IN_FIRMWARE"), store.state.value.missingKeys)
        val row = store.state.value.rows.getValue("NEW_IN_FIRMWARE")
        assertEquals(RowStatus.MISSING, row.status)
        assertEquals(3L, (row.value as Number).toLong())
    }

    @Test
    fun aFailedReadLeavesAnEditingPageAlone() = runBlocking {
        val item = int("SPEED_MAX", def = 60)
        val store = store(item, debounceMs = 10)
        store.sync()
        store.edit(item, 71)
        delay(200)
        gateway.fetchThrows = true
        store.sync()
        assertTrue(store.state.value.sync is SyncPhase.Failed)
        assertEquals(
            "a failed read emptied the rows",
            1,
            store.state.value.rows.size,
        )
        assertEquals(71L, (store.state.value.rows.getValue("SPEED_MAX").value as Number).toLong())
    }

    @Test
    fun aFreshReadIsNotReRunOnEveryVisit() = runBlocking {
        val item = int("SPEED_MAX", def = 60)
        val store = store(item, syncTtlMs = 10_000)
        store.syncIfNeeded()
        store.syncIfNeeded()
        store.syncIfNeeded()
        assertEquals("the tab re-read the config on every entry", 1, gateway.fetchCount)
        // Past the TTL it does re-read: the unit may have been changed by another
        // client, and the bar says when the values were last looked at.
        ticks += 20_000
        store.syncIfNeeded()
        assertEquals(2, gateway.fetchCount)
    }

    @Test
    fun anEditStillInTheDebounceIsNotDroppedByATtlSkip() = runBlocking {
        val item = int("SPEED_MAX", def = 60)
        val store = store(item, debounceMs = 200, syncTtlMs = 10_000)
        store.syncIfNeeded()
        store.edit(item, 77)
        store.syncIfNeeded()          // TTL says skip; a row is mid-debounce
        delay(400)
        assertEquals(77L, (gateway.stored["SPEED_MAX"] as Number).toLong())
    }

    @Test
    fun actionsGoThroughTheGatewayAndKeepTheirFields() = runBlocking {
        val store = store(int("SPEED_MAX", def = 60))
        store.sync()
        gateway.callQueue.addLast(
            CallResult(ok = true, fields = BleJson.obj("""{"km":123.5}""") ?: emptyMap()),
        )
        val result = store.action("odo.set", """{"km":123.5}""")
        assertEquals(listOf("odo.set" to """{"km":123.5}"""), gateway.calls)
        assertEquals(123.5, (result.fields["km"] as Number).toDouble(), 0.001)
        assertNull(result.error)
    }


    // ---------------------------------------------------------------- reads vs typing

    /**
     * A read can land while the user is still typing — the TTL expired, a RETRY after a
     * link hiccup, another part of the app asking for the config. A number that vanishes
     * out of the box a moment after it was typed reads as the unit ignoring the driver,
     * so the typed value wins the race and is still the value that gets written.
     */
    @Test
    fun aTypedValueSurvivesASecondRead() = runBlocking {
        val item = int("SPEED_MAX", def = 60)
        val store = store(item, debounceMs = 200)
        store.sync()
        store.edit(item, 77)
        store.sync()
        val row = store.state.value.rows["SPEED_MAX"]!!
        assertEquals("the re-read ate what was typed", 77L, (row.value as Number).toLong())
        assertEquals("the re-read marked it as confirmed", RowStatus.PENDING, row.status)
        delay(400)
        assertEquals("the value that reached the unit", 77L, (gateway.stored["SPEED_MAX"] as Number).toLong())
    }

    /**
     * A boot-only change stays pending until the unit is back, and no read can tell that
     * it is: `config.get` answers the same either way. So the badge outlives a re-read,
     * and only the app's own restart request clears it.
     */
    @Test
    fun theRebootBadgeOutlivesASecondRead() = runBlocking {
        val item = int("GPS_RATE", def = 10, reboot = true)
        val store = store(item, debounceMs = 10)
        store.sync()
        store.edit(item, 9600)
        delay(200)
        assertEquals(setOf("GPS_RATE"), store.state.value.rebootPending)
        store.sync()
        assertEquals(
            "a read that cannot know undid the warning",
            setOf("GPS_RATE"),
            store.state.value.rebootPending,
        )
        store.clearRebootPending()
        assertEquals(emptySet<String>(), store.state.value.rebootPending)
    }

    /**
     * A boot-only row that ends up back where the last read found it needs no restart.
     * On the bench the row went 1 → 2 → 1 and the badge went on saying "1 boot-only
     * change: DISPLAY_ROTATION" for the rest of the session; a warning that is wrong once
     * gets ignored the next time it is right.
     */
    @Test
    fun puttingABootOnlyRowBackTheWayItWasDropsTheBadge() = runBlocking {
        val item = int("DISPLAY_ROTATION", min = 0.0, max = 3.0, def = 1, reboot = true)
        val store = store(item, debounceMs = 10)
        store.sync()
        store.edit(item, 2.0)
        delay(150)
        assertEquals(setOf("DISPLAY_ROTATION"), store.state.value.rebootPending)
        store.edit(item, 1.0)
        delay(150)
        assertEquals(
            "a value put back the way the unit already had it still asked for a restart",
            emptySet<String>(),
            store.state.value.rebootPending,
        )
        // …and asking for it again puts the warning back.
        store.edit(item, 3.0)
        delay(150)
        assertEquals(setOf("DISPLAY_ROTATION"), store.state.value.rebootPending)
    }

    // ------------------------------------------------- the key the app no longer shows

    /**
     * `ADV_MODE` is a firmware row that no card carries, and since 2026-11-08 no control either:
     * the header's Advanced Mode switch was removed and the settings tab draws every row the
     * unit reports. The app never writes the key now — a config backup, which is written from
     * `CFG_TABLE`, is the route that still puts it back.
     *
     * It does still arrive with the rest of the read, and two things have to stay true when it
     * does. It must not surface among the keys the schema does not know: an unlabelled orphan
     * row would invite a tap the unit refuses. And a read that carries a key no card carries has
     * to finish, not stall.
     */
    @Test
    fun aFirmwareKeyWithNoCardDoesNotBecomeAnOrphanRow() = runBlocking {
        val schema = SettingsFixtures.schema(
            SchemaCard("Ordinary", listOf(int("PLAIN", def = 1))),
            SchemaCard("Harder", listOf(int("HARD", def = 2, advanced = true)), advanced = true),
        )
        gateway.stored = linkedMapOf("PLAIN" to 1, "HARD" to 2, "ADV_MODE" to false)
        gateway.knownResetKeys = setOf("PLAIN", "HARD")
        val store = ConfigStore(gateway, schema, scope(), 40, 60_000, clock)
        store.sync()
        assertEquals(
            "the advanced key showed up as an unlabelled key",
            emptySet<String>(),
            store.state.value.unknown.toSet(),
        )
        assertTrue(
            "a read carrying a key no card carries did not finish: ${store.state.value.sync}",
            store.state.value.sync is SyncPhase.Ready,
        )
        assertNull(
            "the schema still has no row for it, so the tab must not draw one",
            schema.item("ADV_MODE"),
        )
    }

    // ---------------------------------------------------------------- restore

    /**
     * A backup carries keys this app does not show — Wi-Fi and NTP settings the schema
     * keeps out of the cards even though the unit stores them. They are not written back,
     * and they are not failures: the honest report says they were left as the unit has
     * them, and counts them apart from the ones the unit refused.
     */
    @Test
    fun aRestoreLeavesKeysThisAppDoesNotShowAlone() = runBlocking {
        val store = store(int("A_ONE", def = 1), hidden = listOf("WIFI_SSID", "WIFI_PASS"))
        val report = store.restore("""{"A_ONE":9,"WIFI_SSID":"old network","WIFI_PASS":"hunter2"}""")
        assertEquals(1, report.applied)
        assertEquals(2, report.leftOut)
        assertEquals(
            "keys left out on purpose were also counted as refused by the unit",
            0,
            report.refused,
        )
        assertTrue(
            "the skipped keys were not named, or were described as useless to this unit: " +
                report.problems,
            report.problems.any { it.contains("does not show") } &&
                report.problems.none { it.contains("no use for") },
        )
        report.problems.firstOrNull { it.contains("does not show") }!!
            .let { sentence ->
                assertTrue(
                    "the sentence does not name the keys it counts: $sentence",
                    sentence.contains("WIFI_SSID") && sentence.contains("WIFI_PASS"),
                )
            }
        assertFalse("a credential was written to the unit", gateway.stored.containsKey("WIFI_SSID"))
        assertEquals(9, (gateway.stored["A_ONE"] as Number).toInt())
    }
}
