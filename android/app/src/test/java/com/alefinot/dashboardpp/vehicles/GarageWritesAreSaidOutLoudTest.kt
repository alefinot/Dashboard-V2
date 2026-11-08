package com.alefinot.dashboardpp.vehicles

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class GarageWritesAreSaidOutLoudTest {

    private val writes = listOf("vehicles.put(", "vehicles.delete(")

    @Test
    fun `every garage write in the app checks the answer`() {
        val offenders = mainSources().mapNotNull { f ->
            val hits = f.readLines().mapIndexedNotNull { n, line ->
                val trimmed = line.trim()
                if (writes.none { trimmed.contains(it) }) return@mapIndexedNotNull null
                val checked = listOf("!vehicles.put(", "!vehicles.delete(").any { trimmed.contains(it) }
                if (checked) null else "${f.name}:${n + 1}: $trimmed"
            }
            if (hits.isEmpty()) null else f.name + "\n" + hits.joinToString("\n")
        }

        if (offenders.isNotEmpty()) {
            fail(
                "A garage write is ignoring the false that means \"the file did not change\":\n" +
                    offenders.joinToString("\n") + "\n\n" +
                    "Show the user the refusal — VehicleStore.saveFailure(\"…\") says which file " +
                    "and why. See GarageWritesAreSaidOutLoudTest.",
            )
        }
    }

    @Test
    fun `the writes this guard is watching are still there`() {
        val checked = mainSources().sumOf { f ->
            f.readLines().count { line ->
                val trimmed = line.trim()
                writes.any { trimmed.contains(it) } &&
                    listOf("!vehicles.put(", "!vehicles.delete(").any { trimmed.contains(it) }
            }
        }

        assertTrue(
            "expected at least the six known garage writes to check their answer, found $checked: " +
                "save, photo attach, photo remove, unbind, delete, and the two identity persists",
            checked >= 6,
        )
    }

    /** Every Kotlin file under `app/src/main`, the same tree the other source guards read. */
    @Test
    fun `an empty garage that is not empty says so`() {
        val screen = mainSources().first { it.name == "VehicleScreen.kt" }.readText()
        assertTrue(
            "the vehicle page asks the store why the garage is empty",
            screen.contains("garageDamage"),
        )
        assertTrue(
            "and says the file exists but could not be read, instead of " +
                "\"Nothing has been met yet\"",
            screen.contains("cannot read it") && screen.contains("HudColors.Amber"),
        )
    }

    private fun mainSources(): List<File> {
        val root = File("src/main")
            .takeIf { it.isDirectory }
            ?: File("android/app/src/main")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }
}
