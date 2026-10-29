package com.sanchari.bus.ui.helper

import com.sanchari.bus.data.model.BusService
import com.sanchari.bus.data.model.EditableStop
import com.sanchari.bus.ui.helper.ScheduleEditorLogic.EstimateSource
import org.junit.Assert.*
import org.junit.Test

class ScheduleEditorLogicTest {
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
