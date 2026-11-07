package com.sanchari.bus.data.model

import kotlin.math.roundToInt

data class JourneyDurationStatistics(
    val sampleCount: Int,
    val totalMinutes: Long,
    val meanMinutes: Int?,
    val medianMinutes: Int?,
    val modeMinutes: List<Int>
) {
    companion object {
        fun fromDurations(durations: Sequence<Int>): JourneyDurationStatistics {
            // Valid clock-time intervals fit in one day; a histogram avoids sorting samples.
            val frequencies = IntArray(1440)
            var count = 0
            var total = 0L
            var highestFrequency = 0
            for (minutes in durations) {
                require(minutes in 1 until 1440)
                count++
                total += minutes
                frequencies[minutes]++
                highestFrequency = maxOf(highestFrequency, frequencies[minutes])
            }
            if (count == 0) return JourneyDurationStatistics(0, 0, null, null, emptyList())

            val lowerRank = (count - 1) / 2
            val upperRank = count / 2
            var cumulative = 0
            var lower: Int? = null
            var upper: Int? = null
            val modes = mutableListOf<Int>()
            for (minutes in 1 until frequencies.size) {
                cumulative += frequencies[minutes]
                if (lower == null && cumulative > lowerRank) lower = minutes
                if (upper == null && cumulative > upperRank) upper = minutes
                if (highestFrequency > 1 && frequencies[minutes] == highestFrequency) modes.add(minutes)
            }
            return JourneyDurationStatistics(
                count, total, (total.toDouble() / count).roundToInt(),
                ((requireNotNull(lower) + requireNotNull(upper)) / 2.0).roundToInt(), modes
            )
        }
    }
}
