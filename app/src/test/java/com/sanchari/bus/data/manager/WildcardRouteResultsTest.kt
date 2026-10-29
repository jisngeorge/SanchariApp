package com.sanchari.bus.data.manager

import com.sanchari.bus.data.manager.RouteResultsManager.SortOrder
import com.sanchari.bus.data.model.BusService
import com.sanchari.bus.data.model.RouteSearchMode
import com.sanchari.bus.data.model.RouteSearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WildcardRouteResultsTest {
    @Test
    fun detectsOnlyWholeFieldWildcards() {
        assertEquals(RouteSearchMode.DEPARTURES, RouteSearchMode.fromStops("A", "*"))
        assertEquals(RouteSearchMode.ARRIVALS, RouteSearchMode.fromStops("*", "A"))
        assertEquals(RouteSearchMode.FIXED_ROUTE, RouteSearchMode.fromStops("A*", "B"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTwoWildcards() {
        RouteSearchMode.fromStops("*", "*")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBlankInput() {
        RouteSearchMode.fromStops(" ", "A")
    }

    @Test
    fun incomingSearchSortsByArrivalNotOriginDeparture() {
        val earlyArrival = result(1, "14:00", "09:00")
        val lateArrival = result(2, "01:00", "10:00")
        val unknown = result(3, "00:00", "--:--")
        assertEquals(
            listOf(earlyArrival, lateArrival, unknown),
            apply(listOf(unknown, lateArrival, earlyArrival), RouteSearchMode.ARRIVALS)
        )
    }

    @Test
    fun nextArrivalsWrapAtMidnightAndKeepRepeatedVisitsAndUnknownTimes() {
        val first = result(1, "08:00", "23:59")
        val second = result(2, "08:00", "00:00")
        val repeated = result(3, "08:00", "00:00")
        val unknown = result(4, "08:00", "invalid")
        val results = listOf(first, second, repeated, unknown)
        assertEquals(results, apply(results, RouteSearchMode.ARRIVALS, SortOrder.NEXT_DEPARTURE, 1439))
        assertEquals(
            listOf(second, repeated, first, unknown),
            apply(results, RouteSearchMode.ARRIVALS, SortOrder.NEXT_DEPARTURE, 0)
        )
        assertEquals(4, results.size)
    }

    @Test
    fun outboundSearchSortsAtTheKnownStopWithUnknownTimesLast() {
        val now = result(1, "10:00", "11:00")
        val passed = result(2, "09:59", "10:00")
        val unknown = result(3, "--:--", "08:00")
        assertEquals(
            listOf(passed, now, unknown),
            apply(listOf(unknown, now, passed), RouteSearchMode.DEPARTURES)
        )
        assertEquals(
            listOf(now, passed, unknown),
            apply(listOf(unknown, now, passed), RouteSearchMode.DEPARTURES, SortOrder.NEXT_DEPARTURE, 600)
        )
    }

    @Test
    fun fixedRouteDefaultOrderAndTypeFilteringArePreserved() {
        val first = result(1, "10:00", "11:00")
        val second = result(2, "08:00", "09:00")
        val express = second.copy(service = second.service.copy(type = "EXPRESS"))
        assertEquals(listOf(first, second), apply(listOf(first, second), RouteSearchMode.FIXED_ROUTE))
        assertEquals(
            listOf(first),
            RouteResultsManager.applySearchOptions(
                listOf(first, express), setOf("ORDINARY"), SortOrder.DEPARTURE_TIME, 0, RouteSearchMode.ARRIVALS
            )
        )
        assertTrue(RouteResultsManager.applySearchOptions(
            listOf(first), emptySet(), SortOrder.DEPARTURE_TIME, 0, RouteSearchMode.DEPARTURES
        ).isEmpty())
    }

    @Test
    fun wildcardModesExcludeDurationSortOptions() {
        for (mode in listOf(RouteSearchMode.ARRIVALS, RouteSearchMode.DEPARTURES)) {
            assertEquals(
                listOf(SortOrder.DEPARTURE_TIME, SortOrder.NEXT_DEPARTURE),
                RouteResultsManager.sortOrders(mode)
            )
        }
        assertEquals(SortOrder.entries, RouteResultsManager.sortOrders(RouteSearchMode.FIXED_ROUTE))
    }

    private fun apply(
        results: List<RouteSearchResult>,
        mode: RouteSearchMode,
        order: SortOrder = SortOrder.DEPARTURE_TIME,
        minute: Int = 0
    ) = RouteResultsManager.applySearchOptions(results, null, order, minute, mode)

    private fun result(visit: Int, from: String, to: String) = RouteSearchResult(
        BusService("same-service", "Loop", "ORDINARY", false, 0L, from, to),
        "Origin", "Destination", visit, 100
    )
}
