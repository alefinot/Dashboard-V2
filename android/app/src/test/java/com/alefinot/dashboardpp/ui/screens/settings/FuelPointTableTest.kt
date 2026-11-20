package com.alefinot.dashboardpp.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The three decisions the fuel point table makes, taken out of the pixels:
 * the fill ramp, the monotonic repair, and storing a live reading into a slot.
 *
 * All three are transcriptions — the fill of `fillFuelTable()` from the unit's page and
 * `fillFuelTableFromOhms()` in `src/config.cpp`, the repair of `repairFuelTableForm()` on
 * the page and `repairFuelTable()` in the firmware (issue #19: a point out of order leaves
 * a gap the gauge walk cannot match), the capture of `captureFuelSlot()`. The table on
 * screen must sort and fill exactly the way the device will, or the boxes show numbers the
 * unit does not keep.
 */
class FuelPointTableTest {

    /** The factory 8-point table: 10 Ω empty, 180 Ω full — the page's own default line. */
    @Test
    fun theFillIsTheStraightRampTheUnitRebuildsToo() {
        assertEquals(
            listOf(10.0, 34.3, 58.6, 82.9, 107.1, 131.4, 155.7, 180.0),
            fuelRamp(8, 10.0, 180.0),
        )
    }

    /** Both ends are exact at any count, and the ramp descends when the sender does. */
    @Test
    fun theFillEndsWhereTheSenderStartsAndStops() {
        val up = fuelRamp(3, 10.0, 200.0)
        assertEquals(listOf(10.0, 105.0, 200.0), up)
        val down = fuelRamp(5, 180.0, 10.0)
        assertEquals(180.0, down.first(), 0.001)
        assertEquals(10.0, down.last(), 0.001)
        org.junit.Assert.assertTrue(
            "a descending fill came out ascending",
            down.zipWithNext().all { (a, b) -> a >= b },
        )
    }

    /** A point count below 2 has no ramp — the fill button says so instead of writing one. */
    @Test
    fun thereIsNoRampBelowTwoPoints() {
        assertEquals(emptyList<Double>(), fuelRamp(1, 10.0, 180.0))
    }

    /** A table that already follows its ends is left alone, in either direction. */
    @Test
    fun aMonotonicTableIsNotRepaired() {
        assertNull(repairFuelRamp(listOf(10.0, 34.3, 180.0)))
        assertNull(repairFuelRamp(listOf(180.0, 100.0, 10.0)))
        assertNull("a table of one has no order to break", repairFuelRamp(listOf(42.0)))
    }

    /**
     * One point out of order, sorted back the way the ends imply — the issue #19 case,
     * with the count of moved points the table reports on screen.
     */
    @Test
    fun anOutOfOrderTableIsSortedTowardsItsOwnEnds() {
        val broken = listOf(10.0, 90.0, 50.0, 180.0)
        val (sorted, moved) = repairFuelRamp(broken)!!
        assertEquals(listOf(10.0, 50.0, 90.0, 180.0), sorted)
        assertEquals(1, moved)
    }

    /** Direction comes from the ends, never from "ascending is easier": a descending sender keeps descending. */
    @Test
    fun theRepairFollowsTheEndsAndNotTheHabit() {
        val broken = listOf(180.0, 120.0, 150.0, 10.0)
        val (sorted, moved) = repairFuelRamp(broken)!!
        assertEquals(listOf(180.0, 150.0, 120.0, 10.0), sorted)
        assertEquals(1, moved)
    }

    /** Capturing stores the live reading in the named slot, at the unit's own one decimal. */
    @Test
    fun aCaptureReplacesOneSlotAndNothingElse() {
        val table = listOf(10.0, 34.3, 58.6, 180.0)
        assertEquals(
            listOf(10.0, 34.3, 51.2, 180.0),
            captureIntoSlot(table, 2, 51.23),
        )
    }

    /** A slot outside the grid is the page's `Slot #n is not in the table`, and null. */
    @Test
    fun aCaptureCannotReachOutsideTheTable() {
        val table = listOf(10.0, 180.0)
        assertNull(captureIntoSlot(table, 2, 66.0))
        assertNull(captureIntoSlot(table, -1, 66.0))
    }

    /** A typed point lives in the firmware's band; anything past it is clamped, not posted. */
    @Test
    fun aTypedPointIsClampedToTheFirmwareBand() {
        assertEquals(66.4, parseFuelPoint(" 66.4 ") ?: Double.NaN, 0.001)
        assertEquals(0.0, parseFuelPoint("-12")!!, 0.001)
        assertEquals(100000.0, parseFuelPoint("9e9")!!, 0.001)
        assertNull(parseFuelPoint(""))
        assertNull(parseFuelPoint("."))
    }

    // ---------------------------------------------------------------------
    // The live bracket — which two rows a sender reading falls between, and
    // the tank percentage the level bar draws. The gauge walks the same
    // interpolation; the table must say the same place the gauge is at.
    // ---------------------------------------------------------------------

    /** A mid-segment reading reports the segment and its fraction, both directions. */
    @Test
    fun aLiveReadingBracketsTheRowsItSitsBetween() {
        val up = listOf(10.0, 50.0, 90.0)
        assertEquals(1, fuelBracket(up, 70.0)!!.rowA)
        assertEquals(2, fuelBracket(up, 70.0)!!.rowB)
        assertEquals(0.5, fuelBracket(up, 70.0)!!.fraction, 0.001)
        val down = listOf(90.0, 50.0, 10.0)
        assertEquals(0, fuelBracket(down, 70.0)!!.rowA)
        assertEquals(0.5, fuelBracket(down, 70.0)!!.fraction, 0.001)
    }

    /** A reading past either end clamps to it — the sender may read outside the ramp. */
    @Test
    fun aReadingPastTheRampClampsToTheEndRows() {
        val up = listOf(10.0, 50.0, 90.0)
        assertEquals(0.0, fuelBracket(up, 0.0)!!.fraction, 0.001)
        assertEquals(0, fuelBracket(up, 0.0)!!.rowA)
        assertEquals(1.0, fuelBracket(up, 500.0)!!.fraction, 0.001)
        assertEquals(2, fuelBracket(up, 500.0)!!.rowB)
    }

    /** Sitting exactly on a point brackets it from below, at the segment's far end. */
    @Test
    fun aReadingExactlyOnAPointIsNotDropped() {
        val b = fuelBracket(listOf(10.0, 50.0, 90.0), 50.0)!!
        assertEquals(0, b.rowA)
        assertEquals(1, b.rowB)
        assertEquals(1.0, b.fraction, 0.001)
    }

    /** Below two points there is no segment — and no percentage either. */
    @Test
    fun onePointCannotBracketAnything() {
        assertNull(fuelBracket(listOf(42.0), 20.0))
        assertNull(fuelLevelPercent(listOf(42.0), 20.0))
    }

    /** The level is the bracket read against the whole ramp: half a segment past row 1
     *  of a 3-row table is 75%. */
    @Test
    fun theLevelIsTheGaugesOwnInterpolation() {
        assertEquals(50, fuelLevelPercent(listOf(0.0, 190.0), 95.0))
        assertEquals(0, fuelLevelPercent(listOf(0.0, 190.0), -20.0))
        assertEquals(100, fuelLevelPercent(listOf(0.0, 190.0), 2000.0))
        assertEquals(75, fuelLevelPercent(listOf(10.0, 50.0, 90.0), 70.0))
        // The bench unit's own table (0…190 Ω, 8 points) at a 148 Ω sender: 5.45 rows
        // of 7 — 78%.
        assertEquals(78, fuelLevelPercent(fuelRamp(8, 0.0, 190.0), 148.0))
    }
}
