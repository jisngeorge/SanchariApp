package com.sanchari.bus.data.manager

import com.sanchari.bus.data.model.BusService
import com.sanchari.bus.data.model.RouteSearchStatistics
import com.sanchari.bus.data.model.RouteSearchMode
import com.sanchari.bus.data.model.RouteSearchResult
import java.util.Locale

object RouteResultsManager {
    enum class SortOrder {
        DEPARTURE_TIME,
        SHORTEST_JOURNEY,
        LONGEST_JOURNEY,
        NEXT_DEPARTURE
    }

    fun busTypeKey(type: String): String = type.trim().uppercase(Locale.ROOT)

    fun sortOrders(mode: RouteSearchMode): List<SortOrder> = when (mode) {
        RouteSearchMode.FIXED_ROUTE -> SortOrder.entries
        else -> listOf(SortOrder.DEPARTURE_TIME, SortOrder.NEXT_DEPARTURE)
    }

    fun applySearchOptions(
        results: List<RouteSearchResult>,
        selectedTypes: Set<String>?,
        sortOrder: SortOrder,
        currentMinutes: Int,
        mode: RouteSearchMode
    ): List<RouteSearchResult> {
        require(currentMinutes in 0 until 1440)
        require(sortOrder in sortOrders(mode))
        val filtered = results.filter { selectedTypes == null || busTypeKey(it.service.type) in selectedTypes }
        return if (sortOrder == SortOrder.DEPARTURE_TIME && mode == RouteSearchMode.FIXED_ROUTE) filtered
        else filtered.sortedBy { sortKey(it.service, sortOrder, currentMinutes, mode) }
    }

    fun applyOptions(
        services: List<BusService>,
        selectedTypes: Set<String>?,
        sortOrder: SortOrder,
        currentMinutes: Int
    ): List<BusService> {
        require(currentMinutes in 0 until 1440)
        val filtered = services.filter { selectedTypes == null || busTypeKey(it.type) in selectedTypes }
        return if (sortOrder == SortOrder.DEPARTURE_TIME) filtered
        else filtered.sortedBy { sortKey(it, sortOrder, currentMinutes, RouteSearchMode.FIXED_ROUTE) }
    }

    private fun sortKey(service: BusService, order: SortOrder, currentMinutes: Int, mode: RouteSearchMode): Int {
        return when (order) {
            SortOrder.DEPARTURE_TIME, SortOrder.NEXT_DEPARTURE -> {
                val time = RouteSearchStatistics.parseMinutes(
                    if (mode == RouteSearchMode.ARRIVALS) service.toTime else service.fromTime
                )
                if (time == null) Int.MAX_VALUE
                else if (order == SortOrder.DEPARTURE_TIME) time
                else (time - currentMinutes + 1440) % 1440
            }
            SortOrder.SHORTEST_JOURNEY -> RouteSearchStatistics.journeyMinutes(service) ?: Int.MAX_VALUE
            SortOrder.LONGEST_JOURNEY -> RouteSearchStatistics.journeyMinutes(service)?.let { -it } ?: Int.MAX_VALUE
        }
    }
}
