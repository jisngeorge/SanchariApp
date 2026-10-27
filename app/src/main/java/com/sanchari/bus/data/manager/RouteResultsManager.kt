package com.sanchari.bus.data.manager

import com.sanchari.bus.data.model.BusService
import com.sanchari.bus.data.model.RouteSearchStatistics
import java.util.Locale

object RouteResultsManager {
    enum class SortOrder {
        DEPARTURE_TIME,
        SHORTEST_JOURNEY,
        LONGEST_JOURNEY,
        NEXT_DEPARTURE
    }

    fun busTypeKey(type: String): String = type.trim().uppercase(Locale.ROOT)

    fun applyOptions(
        services: List<BusService>,
        selectedTypes: Set<String>?,
        sortOrder: SortOrder,
        currentMinutes: Int
    ): List<BusService> {
        require(currentMinutes in 0 until 1440)
        val filtered = services.filter { selectedTypes == null || busTypeKey(it.type) in selectedTypes }
        return when (sortOrder) {
            SortOrder.DEPARTURE_TIME -> filtered
            SortOrder.SHORTEST_JOURNEY -> filtered.sortedBy {
                RouteSearchStatistics.journeyMinutes(it) ?: Int.MAX_VALUE
            }
            SortOrder.LONGEST_JOURNEY -> filtered.sortedBy {
                RouteSearchStatistics.journeyMinutes(it)?.let { minutes -> -minutes } ?: Int.MAX_VALUE
            }
            SortOrder.NEXT_DEPARTURE -> filtered.sortedBy {
                RouteSearchStatistics.parseMinutes(it.fromTime)?.let { departure ->
                    (departure - currentMinutes + 1440) % 1440
                } ?: Int.MAX_VALUE
            }
        }
    }
}
