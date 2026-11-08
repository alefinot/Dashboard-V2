package com.alefinot.dashboardpp.viewmodel

import com.alefinot.dashboardpp.trips.RecorderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TripsFilterTest {

    private fun row(
        path: String,
        vehicleId: String?,
        name: String? = vehicleId,
        eff: String? = "AA:BB:CC:DD:EE:FF",
    ): TripRow = TripRow(
        file = File(path),
        startMs = 1_730_000_000_000L,
        sizeBytes = 1024L,
        summary = null,
        vehicleId = vehicleId,
        vehicleName = name,
        eff = eff,
        inGarage = vehicleId != null,
    )

    private val twoCars = listOf(
        row("/x/trip_20240101090000.csv", "veh-1", "Caminone"),
        row("/x/trip_20240102090000.csv", "veh-2", "Furgoncino"),
        row("/x/trip_20240103090000.csv", null, null, null),
    )

    @Test
    fun allShowsEveryFile() {
        assertEquals(3, TripsViewModel.applyFilter(twoCars, RecorderSettings.FILTER_ALL).size)
    }

    @Test
    fun aVehicleIdShowsOnlyItsOwnDrives() {
        val kept = TripsViewModel.applyFilter(twoCars, "veh-2")
        assertEquals(listOf("trip_20240102090000.csv"), kept.map { it.file.name })
    }

    @Test
    fun unknownShowsOnlyFilesWithoutATag() {
        val kept = TripsViewModel.applyFilter(twoCars, RecorderSettings.FILTER_UNKNOWN)
        assertEquals(listOf("trip_20240103090000.csv"), kept.map { it.file.name })
    }

    @Test
    fun oneVehicleWithNothingUntaggedGetsNoFilterAtAll() {
        val oneCar = listOf(row("/x/a.csv", "veh-1"), row("/x/b.csv", "veh-1"))
        val options = TripsViewModel.filterOptionsFor(oneCar, mapOf("veh-1" to "Caminone"))
        assertTrue(options.isEmpty())
    }

    @Test
    fun anEmptyTripsListGetsNoFilterEither() {
        assertTrue(TripsViewModel.filterOptionsFor(emptyList(), mapOf("veh-1" to "Caminone")).isEmpty())
    }

    @Test
    fun oneVehiclePlusUntaggedDrivesIsWorthAFilter() {
        val rows = listOf(row("/x/a.csv", "veh-1"), row("/x/b.csv", null, null, null))
        assertEquals(
            listOf(
                TripFilterOption(RecorderSettings.FILTER_ALL, "ALL"),
                TripFilterOption("veh-1", "Caminone"),
                TripFilterOption(RecorderSettings.FILTER_UNKNOWN, "UNKNOWN"),
            ),
            TripsViewModel.filterOptionsFor(rows, mapOf("veh-1" to "Caminone")),
        )
    }

    @Test
    fun twoVehiclesAndNoUntaggedDrivesStillGetNoUnknownChip() {
        val rows = listOf(row("/x/a.csv", "veh-1"), row("/x/b.csv", "veh-2"))
        assertEquals(
            listOf("ALL", "Caminone", "Furgoncino"),
            TripsViewModel.filterOptionsFor(rows, mapOf("veh-1" to "Caminone", "veh-2" to "Furgoncino"))
                .map { it.label },
        )
    }

    @Test
    fun chipsAreNamedFromTheGarageRatherThanFromTheFile() {
        val rows = listOf(row("/x/a.csv", "veh-1", "Old name"), row("/x/b.csv", "veh-2", "Other"))
        val labels = TripsViewModel.filterOptionsFor(
            rows,
            mapOf("veh-1" to "New name", "veh-2" to "Other"),
        ).map { it.label }
        assertEquals(listOf("ALL", "New name", "Other"), labels)
    }

    @Test
    fun aFileWhoseCardWasDeletedStillGetsAChip() {
        val rows = listOf(row("/x/a.csv", "veh-9", "Ghost"), row("/x/b.csv", "veh-1"))
        val options = TripsViewModel.filterOptionsFor(rows, mapOf("veh-1" to "Caminone"))
        assertEquals(listOf("ALL", "(no card)", "Caminone"), options.map { it.label })
        assertEquals("veh-9", options[1].value)
    }

    @Test
    fun aRememberedFilterForAVehicleThatIsGoneYieldsNothingAndIsNotOffered() {
        assertTrue(TripsViewModel.applyFilter(twoCars, "veh-deleted").isEmpty())
        val options = TripsViewModel.filterOptionsFor(
            twoCars,
            mapOf("veh-1" to "Caminone", "veh-2" to "Furgoncino"),
        )
        assertTrue(options.none { it.value == "veh-deleted" })
    }

    @Test
    fun aFilterThatHidesRowsStaysOfferedEvenWhenThereIsNothingElseToNarrow() {
        val oneCar = listOf(row("/x/a.csv", "veh-1", "Caminone"), row("/x/b.csv", "veh-1", "Caminone"))
        val options = TripsViewModel.filterOptionsFor(oneCar, mapOf("veh-1" to "Caminone"), RecorderSettings.FILTER_UNKNOWN)
        assertEquals(listOf("ALL", "Caminone", "UNKNOWN"), options.map { it.label })
        // …and the empty state can name the chip that is on, instead of printing its code.
        assertEquals("UNKNOWN", options.first { it.value == RecorderSettings.FILTER_UNKNOWN }.label)
    }

    @Test
    fun aFilterThatHidesNothingStillLeavesTheRowOut() {
        val oneCar = listOf(row("/x/a.csv", "veh-1", "Caminone"))
        assertTrue(TripsViewModel.filterOptionsFor(oneCar, mapOf("veh-1" to "Caminone"), "veh-1").isEmpty())
    }
}
