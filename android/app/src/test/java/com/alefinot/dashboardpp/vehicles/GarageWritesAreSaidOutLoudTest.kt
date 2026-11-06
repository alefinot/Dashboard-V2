package com.alefinot.dashboardpp.vehicles

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * A garage write that nobody checks is a lie waiting for a restart.
 *
 * [VehicleStore.put] and [VehicleStore.delete] answer `false` when `vehicles.json` did not take
 * the change, and the store deliberately keeps the change in memory while it does — so a caller
 * that throws the answer away leaves the screen showing one garage and the file holding another,
 * and the file wins the next time the app starts. That was true of six call sites at once; this
 * test is what keeps it from quietly becoming true again.
 *
 * It reads sources rather than driving the ViewModel because the ViewModel needs a real app: it
 * builds [VehiclePhotoStore], `WeatherSettings` and a BLE link out of a `Context`, none of which
 * exist in a plain JVM test. So the guard is textual, and it is written to fail loudly in the
 * useful direction — a new unchecked write fails the build, and a removed site fails with a
 * message asking for the list to be updated rather than a silent green.
 */
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
        // The other half of a textual guard: an allow-list nobody maintains, or a check that
        // was deleted along with the code it guarded, both read as green. Counting the
        // checked writes pins that the sites this test exists to watch are still watched.
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
        // `GarageFile.read` answers Damaged for a file it will not write over, and the store then
        // shows no cards at all. Silent, that is indistinguishable from a garage nobody has ever
        // searched with — and the page's old one-liner said exactly that, over cards still on disk.
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
