package com.sanchari.bus.data.model

import kotlin.math.roundToInt

data class RouteSearchStatistics(
    val totalCount: Int,
    val timedCount: Int,
    val averageMinutes: Int?
) {
    companion object {
        private val clockTime = Regex("([01][0-9]|2[0-3]):[0-5][0-9]")

        fun fromServices(services: List<BusService>): RouteSearchStatistics {
            val durations = services.mapNotNull { journeyMinutes(it) }
            return RouteSearchStatistics(
                totalCount = services.size,
                timedCount = durations.size,
                averageMinutes = if (durations.isEmpty()) null else {
                    (durations.sumOf { it.toLong() }.toDouble() / durations.size).roundToInt()
                }
            )
        }

        fun journeyMinutes(service: BusService): Int? {
            val departure = parseMinutes(service.fromTime) ?: return null
            val arrival = parseMinutes(service.toTime) ?: return null
            val duration = (arrival - departure + 1440) % 1440
            // Identical clock times cannot distinguish zero-duration and full-day journeys.
            return duration.takeIf { it > 0 }
        }

        fun parseMinutes(time: String): Int? {
            if (!clockTime.matches(time)) return null
            return time.substring(0, 2).toInt() * 60 + time.substring(3, 5).toInt()
        }
    }
}
