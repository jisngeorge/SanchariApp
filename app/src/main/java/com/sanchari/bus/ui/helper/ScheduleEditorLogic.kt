package com.sanchari.bus.ui.helper

import com.sanchari.bus.data.manager.RouteResultsManager
import com.sanchari.bus.data.model.BusService
import com.sanchari.bus.data.model.EditableStop
import com.sanchari.bus.data.model.RouteSearchStatistics
import java.util.Locale
import kotlin.math.roundToInt

object ScheduleEditorLogic {
    enum class EstimateSource { CURRENT_ROUTE, BUS_TYPE, ALL_TYPES }

    data class TimeEstimate(
        val minutes: Int,
        val sampleCount: Int,
        val source: EstimateSource
    ) {
        fun arrival(previousTime: String): String {
            val departure = requireNotNull(RouteSearchStatistics.parseMinutes(previousTime))
            val arrival = (departure + minutes) % 1440
            return String.format(Locale.ROOT, "%02d:%02d", arrival / 60, arrival % 60)
        }

        fun isNextDay(previousTime: String): Boolean =
            requireNotNull(RouteSearchStatistics.parseMinutes(previousTime)) + minutes >= 1440
    }

    fun reverseCopies(stops: List<EditableStop>, start: Int, end: Int): List<EditableStop> {
        require(start in stops.indices && end in stops.indices && start < end)
        val selected = stops.subList(start, end + 1)
        require(selected.all { it.stopName.isNotBlank() })
        return selected.asReversed().mapIndexed { index, stop ->
            stop.copy(scheduledTime = "", stopOrder = stops.size + index + 1)
        }
    }

    fun canEstimate(stops: List<EditableStop>, position: Int): Boolean =
        position in 1 until stops.size &&
            stops[position - 1].stopName.isNotBlank() &&
            stops[position].stopName.isNotBlank() &&
            stops[position - 1].stopName.trim() != stops[position].stopName.trim() &&
            RouteSearchStatistics.parseMinutes(stops[position - 1].scheduledTime) != null

    fun editorEstimate(stops: List<EditableStop>, position: Int): TimeEstimate? {
        if (!canEstimate(stops, position)) return null
        val from = stops[position - 1].stopName.trim()
        val to = stops[position].stopName.trim()
        val durations = mutableListOf<Int>()
        var departure: Int? = null
        for (index in stops.indices) {
            when (stops[index].stopName.trim()) {
                from -> departure = index
                to -> {
                    departure?.let { start ->
                        if (start != position - 1 || index != position) {
                            RouteSearchStatistics.journeyMinutes(
                                stops[start].scheduledTime, stops[index].scheduledTime
                            )?.let(durations::add)
                        }
                    }
                    departure = null
                }
            }
        }
        return average(durations, EstimateSource.CURRENT_ROUTE)
    }

    fun timetableEstimate(services: List<BusService>, type: String): TimeEstimate? {
        val key = RouteResultsManager.busTypeKey(type)
        val matching = if (key.isEmpty()) emptyList() else services.filter {
            RouteResultsManager.busTypeKey(it.type) == key
        }
        return average(matching.mapNotNull(RouteSearchStatistics::journeyMinutes), EstimateSource.BUS_TYPE)
            ?: average(services.mapNotNull(RouteSearchStatistics::journeyMinutes), EstimateSource.ALL_TYPES)
    }

    private fun average(durations: List<Int>, source: EstimateSource): TimeEstimate? {
        if (durations.isEmpty()) return null
        return TimeEstimate(
            (durations.sumOf { it.toLong() }.toDouble() / durations.size).roundToInt(),
            durations.size,
            source
        )
    }
}
