package com.alefinot.dashboardpp.trips

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionKeysTest {

    @Test
    fun `an address names exactly one row`() {
        val keys = RecorderSettings.sessionKeysToRemove(
            existing = setOf("session:192.168.1.9", "session:192.168.1.30"),
            ips = listOf("192.168.1.9"),
        )
        assertEquals(listOf("session:192.168.1.9"), keys)
    }

    @Test
    fun `a shorter address never takes the longer one that starts with it`() {
        val keys = RecorderSettings.sessionKeysToRemove(
            existing = setOf("session:192.168.1.9", "session:192.168.1.99", "session:192.168.1.109"),
            ips = listOf("192.168.1.9"),
        )
        assertEquals(listOf("session:192.168.1.9"), keys)
    }

    @Test
    fun `nothing else in the settings store is ever touched`() {
        val existing = setOf(
            "recording_enabled", "motion_enabled", "notification_enabled",
            "battery_prompt_shown", "session:192.168.1.9",
        )
        val keys = RecorderSettings.sessionKeysToRemove(existing, listOf("192.168.1.9"))
        assertEquals(setOf("session:192.168.1.9"), keys.toSet())
    }

    @Test
    fun `an address the recorder never wrote about is simply absent`() {
        val keys = RecorderSettings.sessionKeysToRemove(setOf("session:192.168.1.9"), listOf("10.0.0.5"))
        assertTrue(keys.isEmpty())
    }

    @Test
    fun `the same address named twice still removes one row`() {
        val keys = RecorderSettings.sessionKeysToRemove(
            existing = setOf("session:192.168.4.1", "session:192.168.1.9"),
            ips = listOf("192.168.4.1", "192.168.4.1", "192.168.1.9"),
        )
        assertEquals(2, keys.size)
        assertTrue("session:192.168.4.1" in keys)
        assertTrue("session:192.168.1.9" in keys)
    }

    @Test
    fun `no addresses asks for no removals at all`() {
        assertTrue(RecorderSettings.sessionKeysToRemove(setOf("session:192.168.1.9"), emptyList()).isEmpty())
    }
}
