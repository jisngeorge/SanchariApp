package com.sanchari.bus.data.model

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToInt
import kotlin.random.Random

class JourneyDurationStatisticsTest {
    @Test
    fun emptySamplesHaveNoStatistics() {
        val statistics = JourneyDurationStatistics.fromDurations(emptySequence())
        assertEquals(0, statistics.sampleCount)
        assertEquals(0L, statistics.totalMinutes)
        assertNull(statistics.meanMinutes)
        assertNull(statistics.medianMinutes)
        assertTrue(statistics.modeMinutes.isEmpty())
    }

    @Test
    fun calculatesDistinctMeanMedianAndAllTiedModes() {
        val statistics = JourneyDurationStatistics.fromDurations(sequenceOf(90, 40, 20, 10, 40, 20))
        assertEquals(6, statistics.sampleCount)
        assertEquals(220L, statistics.totalMinutes)
        assertEquals(37, statistics.meanMinutes)
        assertEquals(30, statistics.medianMinutes)
        assertEquals(listOf(20, 40), statistics.modeMinutes)
    }

    @Test
    fun oddMedianAndSingleModeIgnoreOutlier() {
        val statistics = JourneyDurationStatistics.fromDurations(sequenceOf(20, 90, 10, 20, 40))
        assertEquals(36, statistics.meanMinutes)
        assertEquals(20, statistics.medianMinutes)
        assertEquals(listOf(20), statistics.modeMinutes)
    }

    @Test
    fun roundsEvenMedianWithoutCombiningDistinctModes() {
        val statistics = JourneyDurationStatistics.fromDurations(sequenceOf(1, 2, 1, 2))
        assertEquals(2, statistics.meanMinutes)
        assertEquals(2, statistics.medianMinutes)
        assertEquals(listOf(1, 2), statistics.modeMinutes)
    }

    @Test
    fun uniqueAndSingleSamplesHaveNoModeAndBoundaryDurationsRemainValid() {
        val single = JourneyDurationStatistics.fromDurations(sequenceOf(1439))
        assertEquals(1439, single.meanMinutes)
        assertEquals(1439, single.medianMinutes)
        assertTrue(single.modeMinutes.isEmpty())
        val pair = JourneyDurationStatistics.fromDurations(sequenceOf(1, 1439))
        assertEquals(720, pair.meanMinutes)
        assertEquals(720, pair.medianMinutes)
        assertTrue(pair.modeMinutes.isEmpty())
        assertEquals(listOf(1439),
            JourneyDurationStatistics.fromDurations(sequenceOf(1439, 1439)).modeMinutes)
    }

    @Test
    fun histogramMatchesSortedReferenceWithoutChangingInput() {
        val random = Random(51)
        repeat(100) {
            val samples = List(random.nextInt(1, 100)) { random.nextInt(1, 1440) }
            val sorted = samples.sorted()
            val frequencies = samples.groupingBy { it }.eachCount()
            val maximum = frequencies.values.max()
            val statistics = JourneyDurationStatistics.fromDurations(samples.asSequence())
            assertEquals(samples.average().roundToInt(), statistics.meanMinutes)
            assertEquals(((sorted[(sorted.size - 1) / 2] + sorted[sorted.size / 2]) / 2.0).roundToInt(),
                statistics.medianMinutes)
            assertEquals(if (maximum == 1) emptyList<Int>() else
                frequencies.filterValues { it == maximum }.keys.sorted(), statistics.modeMinutes)
        }
    }
}
