package com.sanchari.bus.data.model

data class RouteSearchStatistics(
    val totalCount: Int,
    val timedCount: Int,
    val averageMinutes: Int?,
    val medianMinutes: Int?,
    val modeMinutes: List<Int>,
    private val totalJourneyMinutes: Long
) {
    fun isSlow(service: BusService): Boolean {
        val duration = journeyMinutes(service) ?: return false
        return timedCount > 0 && duration.toLong() * 2 * timedCount > totalJourneyMinutes * 3
    }

    companion object {
        private val clockTime = Regex("([01][0-9]|2[0-3]):[0-5][0-9]")

        fun fromServices(services: List<BusService>): RouteSearchStatistics {
            val statistics = JourneyDurationStatistics.fromDurations(
                services.asSequence().mapNotNull(::journeyMinutes)
            )
            return RouteSearchStatistics(
                totalCount = services.size,
                timedCount = statistics.sampleCount,
                averageMinutes = statistics.meanMinutes,
                medianMinutes = statistics.medianMinutes,
                modeMinutes = statistics.modeMinutes,
                totalJourneyMinutes = statistics.totalMinutes
            )
        }

        fun journeyMinutes(service: BusService): Int? = journeyMinutes(service.fromTime, service.toTime)

        fun journeyMinutes(fromTime: String, toTime: String): Int? {
            val departure = parseMinutes(fromTime) ?: return null
            val arrival = parseMinutes(toTime) ?: return null
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
