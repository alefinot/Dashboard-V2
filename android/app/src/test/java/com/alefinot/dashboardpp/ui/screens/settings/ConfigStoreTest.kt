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

    @Test
    fun aSwitchGoesOutWithoutWaitingForTyping() = runBlocking {
        val item = bool("ADV_MODE", def = false)
        val store = store(item, debounceMs = 5_000)
        store.sync()
        store.setAdvanced(true)
        assertEquals(1, gateway.writes.size)
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

    // ---------------------------------------------------------------- advanced switch

    /**
     * ADV_MODE is a firmware row, but this page owns it in the header instead of in a
     * card, so the schema has no row for it. The switch still has to reach the unit — and
     * when the unit says no, the switch has to fall back rather than show an advanced
     * mode that is not on.
     */
    @Test
    fun theAdvancedSwitchIsWrittenEvenThoughNoCardCarriesIt() = runBlocking {
        val schema = SettingsFixtures.schema(
            SchemaCard("Ordinary", listOf(int("PLAIN", def = 1))),
            SchemaCard("Harder", listOf(int("HARD", def = 2, advanced = true)), advanced = true),
        )
        gateway.stored = linkedMapOf("PLAIN" to 1, "HARD" to 2, "ADV_MODE" to false)
        gateway.knownResetKeys = setOf("PLAIN", "HARD")
        val store = ConfigStore(gateway, schema, scope(), 40, 60_000, clock)
        store.sync()
        assertEquals(
            "the master switch showed up as an unlabelled key",
            emptySet<String>(),
            store.state.value.unknown.toSet(),
        )
        assertFalse(store.state.value.advanced)

        // Refused: the switch goes back to what the unit says rather than showing an
        // advanced mode that is not on, and the refusal gets named.
        gateway.writeQueue.addLast(WriteResult(ok = false, error = "nvs"))
        store.setAdvanced(true)
        assertFalse("the switch stayed on after the unit refused", store.state.value.advanced)
        assertTrue(
            "no explanation of the refusal: ${store.state.value.error}",
            (store.state.value.error ?: "").contains("nvs"),
        )

        store.setAdvanced(true)
        assertTrue(store.state.value.advanced)
        assertEquals(
            "the key the unit was asked to change",
            listOf(mapOf<String, Any?>("ADV_MODE" to true), mapOf<String, Any?>("ADV_MODE" to true)),
            gateway.writes,
        )
        assertTrue("ADV_MODE never reached the unit", gateway.stored["ADV_MODE"] == true)
    }

    // ---------------------------------------------------------------- restore

    /**
     * A backup from an older unit carries Wi-Fi and NTP keys a V2 unit has no use for.
     * Writing them is harmless and misleading at once — the honest answer is to leave
     * them alone and say how many were.
     */
    @Test
    fun aRestoreLeavesKeysThisUnitCannotUseAlone() = runBlocking {
        val store = store(int("A_ONE", def = 1), hidden = listOf("WIFI_SSID", "WIFI_PASS"))
        val report = store.restore("""{"A_ONE":9,"WIFI_SSID":"old network","WIFI_PASS":"hunter2"}""")
        assertEquals(1, report.applied)
        assertEquals(2, report.skipped)
        assertTrue("the skipped keys were not named: ${report.problems}", report.problems.any { it.contains("no use for") })
        assertFalse("a credential was written to a unit that cannot use it", gateway.stored.containsKey("WIFI_SSID"))
        assertEquals(9, (gateway.stored["A_ONE"] as Number).toInt())
    }
}
