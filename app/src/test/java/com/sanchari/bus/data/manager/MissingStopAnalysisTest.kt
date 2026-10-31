package com.sanchari.bus.data.manager

import com.sanchari.bus.data.model.EditableStop
import org.junit.Assert.*
import org.junit.Test

class MissingStopAnalysisTest {
    private fun stop(id: Int) = MissingStopAnalysis.Stop(id, "Stop $id",
        10.0 + (if (id == 3) 2.5 else id.toDouble()) * 0.01, 76.0)
    private fun editor(vararg ids: Int) = ids.mapIndexed { index, id ->
        EditableStop("Stop $id", "09:00", index + 1, id)
    }
    private fun route(id: String, type: String = "Ordinary", vararg ids: Int) =
        MissingStopAnalysis.Route(id, type, ids.map(::stop))

    @Test
    fun sameTypeRanksAheadOfMoreCommonOtherTypeAndCountsDistinctServices() {
        val result = MissingStopAnalysis.analyse(editor(1, 4), listOf(
            route("a", "express", 1, 2, 4, 1, 2, 4),
            route("b", "Ordinary", 1, 3, 4),
            route("c", "Ordinary", 1, 3, 4)
        ), " EXPRESS ", null).single().alternatives
        assertEquals(listOf(2, 3), result.map { it.stops.single().id })
        assertEquals(1, result[0].serviceCount)
        assertEquals(1, result[0].sameTypeCount)
        assertEquals(2, result[1].serviceCount)
    }

    @Test
    fun repeatedEndpointsSelectNearestForwardVisitsAndCollapseHalts() {
        val results = MissingStopAnalysis.analyse(editor(1, 4), listOf(
            route("a", "Ordinary", 1, 2, 1, 1, 3, 3, 4, 4, 2, 4)
        ), "", null).single().alternatives
        assertEquals(listOf(listOf(3)), results.map { it.stops.map { stop -> stop.id } })
    }

    @Test
    fun reverseOnlyDirectAndSelfRoutesDoNotSuggestAnything() {
        assertTrue(MissingStopAnalysis.analyse(editor(1, 4), listOf(
            route("reverse", "", 4, 2, 1), route("direct", "", 1, 4),
            route("self", "", 1, 2, 4)
        ), "", "self").isEmpty())
    }

    @Test
    fun alternativesStaySeparateAndRepeatedGapsReceiveIndependentResults() {
        val gaps = MissingStopAnalysis.analyse(editor(1, 4, 1, 4), listOf(
            route("a", "", 1, 2, 4), route("b", "", 1, 3, 4)
        ), "", null)
        assertEquals(listOf(0, 2), gaps.map { it.position })
        assertEquals(listOf(listOf(2), listOf(3)),
            gaps.first().alternatives.map { it.stops.map { stop -> stop.id } })
    }

    @Test
    fun equalNamesDoNotMatchUnrelatedIdsAndHaltEndpointsRetainTheirIds() {
        val other = MissingStopAnalysis.Route("a", "", listOf(
            stop(9).copy(name = "Stop 1"), stop(2), stop(4)
        ))
        assertTrue(MissingStopAnalysis.analyse(editor(1, 4), listOf(other), "", null).isEmpty())
        val halt = other.copy(stops = listOf(
            stop(9).copy(name = "Stop 1"), stop(1), stop(2), stop(4)
        ))
        assertEquals(2, MissingStopAnalysis.analyse(editor(1, 4), listOf(halt), "", null)
            .single().alternatives.single().stops.single().id)
    }

    @Test
    fun repeatedIntermediateVisitsAreNotGloballyDeduplicated() {
        val result = MissingStopAnalysis.analyse(editor(1, 4), listOf(
            route("a", "", 1, 2, 3, 2, 4)
        ), "", null)
        assertEquals(listOf(2, 3, 2), result.single().alternatives.single().stops.map { it.id })
    }

    @Test
    fun unknownEndpointsAndSamePlaceGapsAreSkipped() {
        assertTrue(MissingStopAnalysis.analyse(editor(-1, 4), listOf(route("a", "", 1, 2, 4)), "", null).isEmpty())
        assertTrue(MissingStopAnalysis.analyse(editor(1, 1), listOf(route("a", "", 1, 2, 1)), "", null).isEmpty())
        assertTrue(MissingStopAnalysis.analyse(emptyList(), emptyList(), "", null).isEmpty())
    }

    @Test
    fun selectedStopsAreInsertedWithBlankTimesAndSequentialOrderWithoutMutatingSources() {
        val before = editor(1, 4, 5)
        val result = MissingStopAnalysis.insert(before, mapOf(0 to listOf(stop(2), stop(3)), 1 to listOf(stop(6))))
        assertEquals(listOf(1, 2, 3, 4, 6, 5), result.map { it.originalStopId })
        assertEquals(listOf("09:00", "", "", "09:00", "", "09:00"), result.map { it.scheduledTime })
        assertEquals((1..6).toList(), result.map { it.stopOrder })
        assertEquals(3, before.size)
        assertNotSame(before.first(), result.first())
    }

    @Test
    fun largeDetoursAreRemovedBeforeSameTypePriorityIsApplied() {
        val origin = stop(1).copy(latitude = 10.0)
        val destination = stop(4).copy(latitude = 10.1)
        val detour = stop(2).copy(latitude = 11.0)
        val direct = stop(3).copy(latitude = 10.05)
        val result = MissingStopAnalysis.analyse(editor(1, 4), listOf(
            MissingStopAnalysis.Route("same-type-detour", "Express", listOf(origin, detour, destination)),
            MissingStopAnalysis.Route("other-type-direct", "Ordinary", listOf(origin, direct, destination))
        ), "Express", null)
        assertEquals(listOf(3), result.single().alternatives.single().stops.map { it.id })
        assertEquals(0, result.single().alternatives.single().sameTypeCount)
    }

    @Test
    fun exactOnePointFiveLimitIsIncludedButAnythingAboveIsExcluded() {
        val origin = stop(1).copy(latitude = 10.0)
        val destination = stop(4).copy(latitude = 10.02)
        fun result(intermediateLatitude: Double) = MissingStopAnalysis.analyse(editor(1, 4), listOf(
            MissingStopAnalysis.Route("test", "", listOf(origin,
                stop(2).copy(latitude = intermediateLatitude), destination))
        ), "", null)
        assertEquals(1, result(10.025).size)
        assertEquals(1, result(10.02499999).size)
        assertTrue(result(10.02500001).isEmpty())
    }

    @Test
    fun wholeSequenceDistanceExcludesZigzagsEvenWhenIndividualStopsAreBetweenEndpoints() {
        val rows = listOf(
            stop(1).copy(latitude = 10.0),
            stop(2).copy(latitude = 10.08),
            stop(3).copy(latitude = 10.02),
            stop(5).copy(latitude = 10.08),
            stop(6).copy(latitude = 10.02),
            stop(4).copy(latitude = 10.1))
        assertTrue(MissingStopAnalysis.analyse(editor(1, 4), listOf(
            MissingStopAnalysis.Route("zigzag", "", rows)
        ), "", null).isEmpty())
    }

    @Test
    fun everyEndpointAndIntermediateStopRequiresValidCoordinates() {
        val rows = listOf(stop(1), stop(2), stop(4))
        val invalid = listOf<MissingStopAnalysis.Stop.() -> MissingStopAnalysis.Stop>(
            { copy(latitude = null) }, { copy(longitude = null) },
            { copy(latitude = Double.NaN) }, { copy(longitude = Double.POSITIVE_INFINITY) },
            { copy(latitude = 90.01) }, { copy(latitude = -90.01) },
            { copy(longitude = 180.01) }, { copy(longitude = -180.01) },
            { copy(latitude = 0.0, longitude = 0.0) })
        for (index in rows.indices) for (change in invalid) {
            val changed = rows.toMutableList().apply { this[index] = rows[index].change() }
            assertTrue("Invalid coordinates at route position $index must omit the sequence",
                MissingStopAnalysis.analyse(editor(1, 4), listOf(
                    MissingStopAnalysis.Route("invalid", "", changed)
                ), "", null).isEmpty())
        }
    }

    @Test
    fun identicalEndpointCoordinatesAndMissingCoordinatesDoNotCreateSuggestions() {
        val rows = listOf(stop(1), stop(2), stop(4).copy(latitude = stop(1).latitude))
        assertTrue(MissingStopAnalysis.analyse(editor(1, 4), listOf(
            MissingStopAnalysis.Route("same-place", "", rows)
        ), "", null).isEmpty())
        val missing = rows.map { it.copy(latitude = null, longitude = null) }
        assertTrue(MissingStopAnalysis.analyse(editor(1, 4), listOf(
            MissingStopAnalysis.Route("missing", "", missing)
        ), "", null).isEmpty())
    }

    @Test
    fun equatorAndAntimeridianCoordinatesAreHandledWithoutTreatingZeroAsAlwaysMissing() {
        for (rows in listOf(
            listOf(stop(1).copy(latitude = 0.0, longitude = 76.0),
                stop(2).copy(latitude = 0.0, longitude = 76.05),
                stop(4).copy(latitude = 0.0, longitude = 76.1)),
            listOf(stop(1).copy(latitude = 10.0, longitude = 179.8),
                stop(2).copy(latitude = 10.0, longitude = 180.0),
                stop(4).copy(latitude = 10.0, longitude = -179.8))
        )) {
            assertEquals(1, MissingStopAnalysis.analyse(editor(1, 4), listOf(
                MissingStopAnalysis.Route("valid", "", rows)
            ), "", null).size)
        }
    }

    @Test
    fun missingCoordinatesOutsideTheMatchedGapDoNotSuppressValidSuggestions() {
        val rows = listOf(stop(9).copy(latitude = null), stop(1), stop(2), stop(4),
            stop(8).copy(latitude = null))
        assertEquals(1, MissingStopAnalysis.analyse(editor(1, 4), listOf(
            MissingStopAnalysis.Route("valid-gap", "", rows)
        ), "", null).size)
    }
}
