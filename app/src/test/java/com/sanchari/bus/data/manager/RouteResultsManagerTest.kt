package com.sanchari.bus.data.manager

import com.sanchari.bus.data.manager.RouteResultsManager.SortOrder
import com.sanchari.bus.data.model.BusService
import com.sanchari.bus.data.model.RouteSearchStatistics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteResultsManagerTest {
    @Test
    fun sortsByJourneyDurationInBothDirectionsWithUnknownTimesLast() {
        val unknown = service("unknown", "--:--", "10:00")
        val slow = service("slow", "08:00", "10:00")
        val overnight = service("overnight", "23:30", "00:30")
        val fast = service("fast", "10:00", "10:30")
        val equalTimes = service("equal", "11:00", "11:00")
        val results = listOf(unknown, slow, overnight, fast, equalTimes)

        assertEquals(
            listOf(fast, overnight, slow, unknown, equalTimes),
            apply(results, SortOrder.SHORTEST_JOURNEY)
        )
        assertEquals(
            listOf(slow, overnight, fast, unknown, equalTimes),
            apply(results, SortOrder.LONGEST_JOURNEY)
        )
        assertEquals(listOf(unknown, slow, overnight, fast, equalTimes), results)
    }

    @Test
    fun nextDeparturesStartAtCurrentMinuteAndWrapPassedDeparturesToTomorrow() {
        val passed = service("passed", "09:59", "11:00")
        val now = service("now", "10:00", "11:00")
        val upcoming = service("upcoming", "10:01", "11:00")
        val later = service("later", "23:00", "23:30")
        val unknown = service("unknown", "--:--", "11:00")
        val unknownArrival = service("unknown-arrival", "10:02", "--:--")

        assertEquals(
            listOf(now, upcoming, unknownArrival, later, passed, unknown),
            apply(listOf(passed, later, unknown, upcoming, now, unknownArrival), SortOrder.NEXT_DEPARTURE, 600)
        )
    }

    @Test
    fun nextDeparturesReorderAcrossMidnightAndAsCurrentTimeAdvances() {
        val beforeMidnight = service("before", "23:59", "00:30")
        val midnight = service("midnight", "00:00", "00:30")
        val afterMidnight = service("after", "00:01", "00:30")
        val results = listOf(beforeMidnight, midnight, afterMidnight)

        assertEquals(results, apply(results, SortOrder.NEXT_DEPARTURE, 1439))
        assertEquals(
            listOf(midnight, afterMidnight, beforeMidnight),
            apply(results, SortOrder.NEXT_DEPARTURE, 0)
        )
        assertEquals(
            listOf(afterMidnight, beforeMidnight, midnight),
            apply(results, SortOrder.NEXT_DEPARTURE, 1)
        )
    }

    @Test
    fun filtersMultipleBusTypesAndPreservesOriginalDepartureOrderOnReset() {
        val ordinary = service("ordinary", "08:00", "09:00", type = "Ordinary")
        val fast = service("fast", "09:00", "09:30", type = " fast passenger ")
        val express = service("express", "10:00", "10:15", type = "EXPRESS")
        val results = listOf(ordinary, fast, express)

        assertEquals(
            listOf(ordinary, fast),
            RouteResultsManager.applyOptions(
                results, setOf("ORDINARY", "FAST PASSENGER"), SortOrder.DEPARTURE_TIME, 0
            )
        )
        assertEquals(results, apply(results, SortOrder.DEPARTURE_TIME))
        assertEquals(
            emptyList<BusService>(),
            RouteResultsManager.applyOptions(results, emptySet(), SortOrder.SHORTEST_JOURNEY, 0)
        )
        assertEquals(
            listOf(express, ordinary),
            RouteResultsManager.applyOptions(
                results, setOf("ORDINARY", "EXPRESS"), SortOrder.SHORTEST_JOURNEY, 0
            )
        )
    }

    @Test
    fun tiesRetainOriginalOrderAndNonRunningServicesRemainIncluded() {
        val first = service("loop", "08:00", "09:00", isRunning = false)
        val second = service("loop", "12:00", "13:00")
        val third = service("third", "08:00", "09:00")
        val results = listOf(first, second, third)

        assertEquals(results, apply(results, SortOrder.SHORTEST_JOURNEY))
        assertEquals(listOf(first, third, second), apply(results, SortOrder.NEXT_DEPARTURE, 480))
    }

    @Test
    fun averageUsesOnlyTheFilteredList() {
        val first = service("first", "08:00", "08:30")
        val second = service("second", "09:00", "09:30")
        val fasterOtherType = service("express", "10:00", "10:15", type = "EXPRESS")
        val slow = service("slow", "12:00", "13:00")
        val filtered = RouteResultsManager.applyOptions(
            listOf(first, second, fasterOtherType, slow),
            setOf("ORDINARY"),
            SortOrder.SHORTEST_JOURNEY,
            0
        )
        val statistics = RouteSearchStatistics.fromServices(filtered)

        assertEquals(40, statistics.averageMinutes)
        assertEquals(3, statistics.totalCount)
    }

    @Test
    fun emptyResultsAreSupportedForEverySortOrder() {
        for (order in SortOrder.entries) {
            assertTrue(apply(emptyList(), order).isEmpty())
        }
    }

    private fun apply(
        services: List<BusService>,
        order: SortOrder,
        currentMinutes: Int = 0
    ) = RouteResultsManager.applyOptions(services, null, order, currentMinutes)

    private fun service(
        id: String,
        from: String,
        to: String,
        type: String = "ORDINARY",
        isRunning: Boolean = true
    ) = BusService(id, id, type, isRunning, 0L, from, to)
}
