package com.sanchari.bus.ui.helper

import com.sanchari.bus.data.manager.RouteResultsManager
import com.sanchari.bus.data.model.BusService
import com.sanchari.bus.data.model.EditableStop
import com.sanchari.bus.data.model.JourneyDurationStatistics
import com.sanchari.bus.data.model.RouteSearchStatistics
import java.util.Locale
import kotlin.math.abs

object ScheduleEditorLogic {
    fun isExcessiveConsecutiveStop(stops: List<EditableStop>, position: Int): Boolean =
        position >= 2 && position < stops.size && stops[position].stopName.isNotBlank() &&
            stops[position].stopName.trim().equals(stops[position - 1].stopName.trim(), ignoreCase = true) &&
            stops[position].stopName.trim().equals(stops[position - 2].stopName.trim(), ignoreCase = true)

    fun excessiveConsecutiveStopPositions(stops: List<EditableStop>): List<Int> =
        stops.indices.filter { isExcessiveConsecutiveStop(stops, it) }

    fun updateTimeAndSort(stops: MutableList<EditableStop>, position: Int, time: String): Int {
        require(position in stops.indices)
        val updatedStop = stops[position]
        updatedStop.scheduledTime = time
        val minutes = RouteSearchStatistics.parseMinutes(time) ?: return position
        val timeline = IntArray(stops.size) { Int.MAX_VALUE }
        var previousMinutes: Int? = null
        var dayOffset = 0
        var first = Int.MAX_VALUE
        var last = Int.MIN_VALUE
        for (index in stops.indices) {
            if (index == position) continue
            val existingMinutes = RouteSearchStatistics.parseMinutes(stops[index].scheduledTime) ?: continue
            if (previousMinutes != null && existingMinutes < previousMinutes) dayOffset += 1440
            val value = existingMinutes + dayOffset
            timeline[index] = value
            first = minOf(first, value)
            last = maxOf(last, value)
            previousMinutes = existingMinutes
        }
        if (first == Int.MAX_VALUE) return position

        // Match the edited clock time to the existing route's day or next day.
        val nextDay = minutes + 1440
        timeline[position] = when {
            minutes in first..last -> minutes
            nextDay in first..last -> nextDay
            abs(nextDay - last) < abs(minutes - first) -> nextDay
            else -> minutes
        }
        val ordered = stops.indices.sortedBy { timeline[it] }.map { stops[it] }
        stops.clear()
        stops.addAll(ordered)
        return stops.indexOfFirst { it === updatedStop }
    }

    fun missingTimePositions(stops: List<EditableStop>): List<Int> =
        stops.indices.filter { RouteSearchStatistics.parseMinutes(stops[it].scheduledTime) == null }

    fun excessiveTimeIntervalPositions(stops: List<EditableStop>): List<Int> =
        (1 until stops.size).filter { position ->
            val minutes = RouteSearchStatistics.journeyMinutes(
                stops[position - 1].scheduledTime, stops[position].scheduledTime
            )
            minutes != null && minutes > 8 * 60
        }

    enum class EstimateSource { CURRENT_ROUTE, BUS_TYPE, ALL_TYPES }

    data class TimeEstimate(
        val statistics: JourneyDurationStatistics,
        val source: EstimateSource
    ) {
        val minutes: Int get() = requireNotNull(statistics.meanMinutes)
        val sampleCount: Int get() = statistics.sampleCount

        fun arrival(previousTime: String, minutes: Int = this.minutes): String {
            val departure = requireNotNull(RouteSearchStatistics.parseMinutes(previousTime))
            val arrival = (departure + minutes) % 1440
            return String.format(Locale.ROOT, "%02d:%02d", arrival / 60, arrival % 60)
        }

        fun isNextDay(previousTime: String, minutes: Int = this.minutes): Boolean =
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
        return summarize(durations.asSequence(), EstimateSource.CURRENT_ROUTE)
    }

    fun timetableEstimate(services: List<BusService>, type: String): TimeEstimate? {
        val key = RouteResultsManager.busTypeKey(type)
        val matching = if (key.isEmpty()) emptyList() else services.filter {
            RouteResultsManager.busTypeKey(it.type) == key
        }
        return summarize(matching.asSequence().mapNotNull(RouteSearchStatistics::journeyMinutes), EstimateSource.BUS_TYPE)
            ?: summarize(services.asSequence().mapNotNull(RouteSearchStatistics::journeyMinutes), EstimateSource.ALL_TYPES)
    }

    private fun summarize(durations: Sequence<Int>, source: EstimateSource): TimeEstimate? {
        val statistics = JourneyDurationStatistics.fromDurations(durations)
        return if (statistics.sampleCount == 0) null else TimeEstimate(statistics, source)
    }
}
