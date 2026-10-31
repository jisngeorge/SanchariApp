package com.sanchari.bus.ui.helper

import com.sanchari.bus.data.model.BusService
import com.sanchari.bus.data.model.EditableStop
import com.sanchari.bus.ui.helper.ScheduleEditorLogic.EstimateSource
import org.junit.Assert.*
import org.junit.Test

class ScheduleEditorLogicTest {
    @Test
    fun selectingTimeMovesNewStopsBetweenExistingTimesAndKeepsTheirIdentity() {
        val stops = route("A" to "08:00", "B" to "09:00", "C" to "")
        stops[2] = stops[2].copy(originalStopId = -1)
        val edited = stops[2]
        assertEquals(1, ScheduleEditorLogic.updateTimeAndSort(stops, 2, "08:30"))
        assertEquals(listOf("A", "C", "B"), stops.map { it.stopName })
        assertSame(edited, stops[1])
        assertEquals(-1, stops[1].originalStopId)
        assertEquals("08:30", stops[1].scheduledTime)
    }

    @Test
    fun editingExistingTimesMovesRowsEarlierAndLater() {
        val stops = route("A" to "08:00", "B" to "09:00", "C" to "10:00")
        assertEquals(2, ScheduleEditorLogic.updateTimeAndSort(stops, 1, "11:00"))
        assertEquals(listOf("A", "C", "B"), stops.map { it.stopName })
        assertEquals(0, ScheduleEditorLogic.updateTimeAndSort(stops, 2, "07:00"))
        assertEquals(listOf("B", "A", "C"), stops.map { it.stopName })
    }

    @Test
    fun sortingPreservesMidnightCrossingAndInsertsWithinTheNextDay() {
        val stops = route("A" to "23:00", "B" to "00:30", "C" to "01:00", "D" to "")
        assertEquals(1, ScheduleEditorLogic.updateTimeAndSort(stops, 3, "00:10"))
        assertEquals(listOf("A", "D", "B", "C"), stops.map { it.stopName })
        assertEquals(listOf("23:00", "00:10", "00:30", "01:00"), stops.map { it.scheduledTime })
    }

    @Test
    fun sortingChoosesNearestDayForEarlyStartsAndMidnightArrivals() {
        val late = route("A" to "22:00", "B" to "23:00", "C" to "")
        assertEquals(2, ScheduleEditorLogic.updateTimeAndSort(late, 2, "00:15"))
        assertEquals(listOf("A", "B", "C"), late.map { it.stopName })
        val morning = route("A" to "08:00", "B" to "09:00", "C" to "")
        assertEquals(0, ScheduleEditorLogic.updateTimeAndSort(morning, 2, "07:30"))
        assertEquals(listOf("C", "A", "B"), morning.map { it.stopName })
    }

    @Test
    fun equalTimesAreStableAndTheEditedDuplicateHasItsOwnPosition() {
        val stops = route("A" to "08:00", "B" to "09:00", "A" to "")
        stops[2] = stops[0].copy(scheduledTime = "")
        val edited = stops[2]
        assertEquals(1, ScheduleEditorLogic.updateTimeAndSort(stops, 2, "08:00"))
        assertSame(edited, stops[1])
        assertNotSame(edited, stops[0])
        assertEquals(listOf("A", "A", "B"), stops.map { it.stopName })
    }

    @Test
    fun unfilledAndInvalidRowsSortLastWithoutInventingTimes() {
        val stops = route("Blank" to "", "A" to "08:00", "Invalid" to "--:--", "B" to "")
        assertEquals(1, ScheduleEditorLogic.updateTimeAndSort(stops, 3, "09:00"))
        assertEquals(listOf("A", "B", "Blank", "Invalid"), stops.map { it.stopName })
        assertEquals(listOf("08:00", "09:00", "", "--:--"), stops.map { it.scheduledTime })
    }

    @Test
    fun missingReferenceOrInvalidEditedTimesLeaveOrderUnchanged() {
        val stops = route("A" to "", "B" to "")
        assertEquals(1, ScheduleEditorLogic.updateTimeAndSort(stops, 1, "09:00"))
        assertEquals(listOf("A", "B"), stops.map { it.stopName })
        assertEquals(1, ScheduleEditorLogic.updateTimeAndSort(stops, 1, "9:00 AM"))
        assertEquals(listOf("A", "B"), stops.map { it.stopName })
        val single = route("A" to "")
        assertEquals(0, ScheduleEditorLogic.updateTimeAndSort(single, 0, "08:00"))
    }

    @Test
    fun manualMovesRemainUntilAnotherTimeEditAndOtherActionsDoNotSort() {
        val stops = route("A" to "08:00", "B" to "09:00", "C" to "")
        ScheduleEditorLogic.updateTimeAndSort(stops, 2, "08:30")
        java.util.Collections.swap(stops, 0, 1)
        ScheduleEditorLogic.missingTimePositions(stops)
        ScheduleEditorLogic.excessiveTimeIntervalPositions(stops)
        assertEquals(listOf("C", "A", "B"), stops.map { it.stopName })
        assertEquals(1, ScheduleEditorLogic.updateTimeAndSort(stops, 0, "08:45"))
        assertEquals(listOf("A", "C", "B"), stops.map { it.stopName })
    }

    @Test
    fun adjacentIntervalsAllowExactlyEightHoursButRejectOneMinuteMore() {
        assertTrue(ScheduleEditorLogic.excessiveTimeIntervalPositions(
            route("A" to "08:00", "B" to "16:00")
        ).isEmpty())
        assertEquals(listOf(1), ScheduleEditorLogic.excessiveTimeIntervalPositions(
            route("A" to "08:00", "B" to "16:01")
        ))
    }

    @Test
    fun adjacentIntervalsUseMidnightRolloverAndCatchAmPmMistakesInBothDirections() {
        assertTrue(ScheduleEditorLogic.excessiveTimeIntervalPositions(
            route("A" to "23:50", "B" to "00:10", "C" to "08:10")
        ).isEmpty())
        assertEquals(listOf(1), ScheduleEditorLogic.excessiveTimeIntervalPositions(
            route("A" to "23:50", "B" to "07:51")
        ))
        assertEquals(listOf(1, 2), ScheduleEditorLogic.excessiveTimeIntervalPositions(
            route("A" to "08:00", "B" to "20:15", "C" to "08:30")
        ))
    }

    @Test
    fun adjacentChecksIncludeExistingNewAndHaltRowsWithoutCheckingTotalJourney() {
        val stops = route("A" to "00:00", "A" to "08:01", "B" to "09:00", "C" to "17:01")
        stops[2] = stops[2].copy(originalStopId = -1)
        assertEquals(listOf(1, 3), ScheduleEditorLogic.excessiveTimeIntervalPositions(stops))
        stops[1].scheduledTime = "08:00"
        stops[3].scheduledTime = "17:00"
        assertTrue(ScheduleEditorLogic.excessiveTimeIntervalPositions(stops).isEmpty())
    }

    @Test
    fun intervalChecksLeaveMissingInvalidAndEqualTimesToRequiredFieldValidation() {
        assertTrue(ScheduleEditorLogic.excessiveTimeIntervalPositions(
            route("A" to "08:00", "B" to "08:00", "C" to "", "D" to "24:00", "E" to "09:00")
        ).isEmpty())
        assertEquals(listOf(2, 3), ScheduleEditorLogic.missingTimePositions(
            route("A" to "08:00", "B" to "08:00", "C" to "", "D" to "24:00", "E" to "09:00")
        ))
        assertTrue(ScheduleEditorLogic.excessiveTimeIntervalPositions(emptyList()).isEmpty())
        assertTrue(ScheduleEditorLogic.excessiveTimeIntervalPositions(route("A" to "09:00")).isEmpty())
    }

    @Test
    fun everyRowRequiresAValidTimeIncludingHaltsAndUnknownPlaceholders() {
        val stops = route("A" to "00:00", "A" to "00:00", "B" to "23:59",
            "C" to "", "D" to " ", "E" to "--:--", "F" to "24:00",
            "G" to "09:60", "H" to "9:00", "I" to "9:00 AM")
        assertEquals((3..9).toList(), ScheduleEditorLogic.missingTimePositions(stops))
        stops.drop(3).forEach { it.scheduledTime = "09:00" }
        assertTrue(ScheduleEditorLogic.missingTimePositions(stops).isEmpty())
    }

    @Test
    fun reversingKeepsTurnaroundIdsAndBlankIndependentTimes() {
        val stops = route("A" to "08:00", "B" to "08:20", "C" to "08:40", "D" to "09:00")
        val copies = ScheduleEditorLogic.reverseCopies(stops, 0, 3)
        assertEquals(listOf("D", "C", "B", "A"), copies.map { it.stopName })
        assertEquals(listOf(4, 3, 2, 1), copies.map { it.originalStopId })
        assertEquals(listOf(5, 6, 7, 8), copies.map { it.stopOrder })
        assertTrue(copies.all { it.scheduledTime.isEmpty() })
        copies.last().stopName = "Changed"
        assertEquals("A", stops.first().stopName)
        assertEquals("08:00", stops.first().scheduledTime)
    }

    @Test
    fun reversingAnInternalSectionPreservesItsHaltAndNewStops() {
        val stops = route("X" to "", "A" to "", "B" to "", "B" to "", "Y" to "")
        stops[2] = stops[2].copy(originalStopId = -1)
        val copies = ScheduleEditorLogic.reverseCopies(stops, 1, 3)
        assertEquals(listOf("B", "B", "A"), copies.map { it.stopName })
        assertEquals(listOf(4, -1, 2), copies.map { it.originalStopId })
    }

    @Test
    fun repeatedReverseAppendsCanBuildAnotherOutboundLegWithoutSharingRows() {
        val stops = route("A" to "08:00", "B" to "08:20", "C" to "08:40", "D" to "09:00")
        stops.addAll(ScheduleEditorLogic.reverseCopies(stops, 0, 3))
        stops.addAll(ScheduleEditorLogic.reverseCopies(stops, 4, 7))
        assertEquals(listOf("A", "B", "C", "D", "D", "C", "B", "A", "A", "B", "C", "D"), stops.map { it.stopName })
        assertTrue(stops.drop(4).all { it.scheduledTime.isEmpty() })
        stops[8].scheduledTime = "10:00"
        assertEquals("", stops[7].scheduledTime)
        assertEquals("08:00", stops[0].scheduledTime)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBlankReverseSection() {
        ScheduleEditorLogic.reverseCopies(route("A" to "", "" to ""), 0, 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsSingleRowSection() {
        ScheduleEditorLogic.reverseCopies(route("A" to "", "B" to ""), 1, 1)
    }

    @Test
    fun editorSamplesIncludeIntermediateStopsAndExcludeTargetInterval() {
        val stops = route(
            "A" to "08:00", "X" to "08:10", "B" to "08:30",
            "A" to "10:00", "B" to "12:00"
        )
        val estimate = requireNotNull(ScheduleEditorLogic.editorEstimate(stops, 4))
        assertEquals(30, estimate.minutes)
        assertEquals(1, estimate.sampleCount)
        assertEquals(EstimateSource.CURRENT_ROUTE, estimate.source)
        assertEquals("10:30", estimate.arrival("10:00"))
        assertFalse(estimate.isNextDay("10:00"))
    }

    @Test
    fun repeatedEndpointsUseLastDepartureAndFirstArrival() {
        val stops = route(
            "A" to "08:00", "A" to "08:10", "X" to "08:20",
            "B" to "08:40", "B" to "09:00", "A" to "10:00", "B" to ""
        )
        val estimate = requireNotNull(ScheduleEditorLogic.editorEstimate(stops, 6))
        assertEquals(30, estimate.minutes)
        assertEquals(1, estimate.sampleCount)
    }

    @Test
    fun editorIntervalsAreDirectionalAndCannotSuggestTheirOwnValue() {
        val stops = route("B" to "08:00", "A" to "08:45", "B" to "09:15")
        assertNull(ScheduleEditorLogic.editorEstimate(stops, 2))
        assertNull(ScheduleEditorLogic.editorEstimate(route("A" to "08:00", "B" to "09:00"), 1))
    }

    @Test
    fun editorMeanRoundsOnlyAfterAggregatingAndSupportsMidnight() {
        val stops = route(
            "A" to "23:50", "B" to "00:10",
            "A" to "09:00", "B" to "09:21", "A" to "23:55", "B" to ""
        )
        val estimate = requireNotNull(ScheduleEditorLogic.editorEstimate(stops, 5))
        assertEquals(21, estimate.minutes)
        assertEquals(2, estimate.sampleCount)
        assertEquals("00:16", estimate.arrival("23:55"))
        assertTrue(estimate.isNextDay("23:55"))
    }

    @Test
    fun missingPreviousTimeFirstRowAndHaltsCannotBeEstimated() {
        assertFalse(ScheduleEditorLogic.canEstimate(route("A" to "08:00", "B" to ""), 0))
        assertFalse(ScheduleEditorLogic.canEstimate(route("A" to "--:--", "B" to ""), 1))
        assertFalse(ScheduleEditorLogic.canEstimate(route("A" to "08:00", "A" to ""), 1))
        assertFalse(ScheduleEditorLogic.canEstimate(route("" to "08:00", "B" to ""), 1))
    }

    @Test
    fun sameTypeTakesPriorityAndUsesExistingNormalization() {
        val estimate = requireNotNull(ScheduleEditorLogic.timetableEstimate(
            listOf(service("Express", "08:00", "08:20"), service("LOCAL", "08:00", "09:00")), " express "
        ))
        assertEquals(20, estimate.minutes)
        assertEquals(1, estimate.sampleCount)
        assertEquals(EstimateSource.BUS_TYPE, estimate.source)
    }

    @Test
    fun missingOrInvalidTypeSamplesFallBackToAllTypesIncludingNonRunning() {
        val samples = listOf(
            service("EXPRESS", "--:--", "08:30"),
            service("LOCAL", "08:00", "08:40").copy(isRunning = false)
        )
        listOf("", "NEW TYPE", "EXPRESS").forEach { type ->
            val estimate = requireNotNull(ScheduleEditorLogic.timetableEstimate(samples, type))
            assertEquals(40, estimate.minutes)
            assertEquals(EstimateSource.ALL_TYPES, estimate.source)
            assertEquals(1, estimate.sampleCount)
        }
    }

    @Test
    fun invalidEqualAndUnknownIntervalsNeverInventAnEstimate() {
        val invalid = listOf(
            service("A", "08:00", "08:00"), service("A", "25:00", "09:00"),
            service("A", "", "09:00"), service("A", "8:00", "09:00")
        )
        assertNull(ScheduleEditorLogic.timetableEstimate(invalid, "A"))
        assertNull(ScheduleEditorLogic.timetableEstimate(emptyList(), "A"))
    }

    private fun route(vararg stops: Pair<String, String>) = stops.mapIndexed { index, (name, time) ->
        EditableStop(name, time, index + 1, index + 1)
    }.toMutableList()

    private fun service(type: String, from: String, to: String) =
        BusService("test", "Test", type, true, 0, from, to)
}
