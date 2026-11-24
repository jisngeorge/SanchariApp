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
        fun fromServices(services: List<BusService>): RouteSearchStatistics =
            fromServiceSequence(services.asSequence(), services.size)

        fun fromResults(results: List<RouteSearchResult>): RouteSearchStatistics =
            fromServiceSequence(results.asSequence().map { it.service }, results.size)

        private fun fromServiceSequence(services: Sequence<BusService>, totalCount: Int): RouteSearchStatistics {
            val statistics = JourneyDurationStatistics.fromDurations(
                services.mapNotNull(::journeyMinutes)
            )
            return RouteSearchStatistics(
                totalCount = totalCount,
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
            if (time.length != 5 || time[2] != ':') return null
            val h1 = time[0] - '0'
            val h2 = time[1] - '0'
            val m1 = time[3] - '0'
            val m2 = time[4] - '0'
            if (h1 !in 0..9 || h2 !in 0..9 || m1 !in 0..9 || m2 !in 0..9) return null
            val hour = h1 * 10 + h2
            val minute = m1 * 10 + m2
            if (hour > 23 || minute > 59) return null
            return hour * 60 + minute
        }
    }
}
