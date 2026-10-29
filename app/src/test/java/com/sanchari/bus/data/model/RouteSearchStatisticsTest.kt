package com.sanchari.bus.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteSearchStatisticsTest {
    @Test
    fun calculatesAverageUsingSelectedEndpointTimes() {
        val slow = service("slow", "08:00", "10:00")
        val fast = service("fast", "09:00", "09:45")

        val statistics = RouteSearchStatistics.fromServices(listOf(slow, fast))

        assertEquals(2, statistics.totalCount)
        assertEquals(2, statistics.timedCount)
        assertEquals(83, statistics.averageMinutes)
    }

    @Test
    fun handlesOvernightAndMidnightArrivals() {
        val overnight = service("overnight", "23:30", "01:00")
        val midnight = service("midnight", "23:30", "00:00")

        val statistics = RouteSearchStatistics.fromServices(listOf(overnight, midnight))

        assertEquals(60, statistics.averageMinutes)
    }

    @Test
    fun excludesMissingMalformedOutOfRangeAndAmbiguousTimes() {
        val invalidTimes = listOf("--:--", "", "8:00", "24:00", "12:60", "ab:cd", " 08:00", "08:00 ")
        val services = invalidTimes.flatMap { time ->
            listOf(service(time, time, "10:00"), service(time, "08:00", time))
        } + service("equal", "08:00", "08:00")
        val valid = service("valid", "08:00", "09:00")

        val statistics = RouteSearchStatistics.fromServices(services + valid)

        assertEquals(services.size + 1, statistics.totalCount)
        assertEquals(1, statistics.timedCount)
        assertEquals(60, statistics.averageMinutes)
    }

    @Test
    fun reportsUnavailableForEmptyOrEntirelyInvalidResults() {
        for (services in listOf(emptyList(), listOf(service("unknown", "--:--", "--:--")))) {
            val statistics = RouteSearchStatistics.fromServices(services)

            assertEquals(services.size, statistics.totalCount)
            assertEquals(0, statistics.timedCount)
            assertNull(statistics.averageMinutes)
        }
    }

    @Test
    fun includesNonRunningServices() {
        val stopped = service("stopped", "08:00", "08:30", isRunning = false)
        val running = service("running", "09:00", "09:30")
        val slow = service("slow", "10:00", "11:00")

        val statistics = RouteSearchStatistics.fromServices(listOf(stopped, running, slow))

        assertEquals(3, statistics.timedCount)
        assertEquals(40, statistics.averageMinutes)
    }

    @Test
    fun countsRepeatedServiceMatchesSeparatelyWithoutChangingResults() {
        val first = service("loop", "08:00", "09:00")
        val second = service("loop", "12:00", "12:30")
        val results = listOf(first, second)

        val statistics = RouteSearchStatistics.fromServices(results)

        assertEquals(2, statistics.timedCount)
        assertEquals(45, statistics.averageMinutes)
        assertEquals(listOf(first, second), results)
    }

    @Test
    fun slowWarningRequiresStrictlyMoreThanOneAndAHalfTimesAverage() {
        val fast = service("fast", "08:00", "08:30")
        val average = service("average", "08:00", "09:00")
        val boundary = service("boundary", "08:00", "09:30")
        val statistics = RouteSearchStatistics.fromServices(listOf(fast, average, boundary))
        assertEquals(60, statistics.averageMinutes)
        assertFalse(statistics.isSlow(fast))
        assertFalse(statistics.isSlow(average))
        assertFalse(statistics.isSlow(boundary))
        assertTrue(statistics.isSlow(service("above", "08:00", "09:31")))
    }

    @Test
    fun slowWarningUsesTheUnroundedAverage() {
        val services = listOf(
            service("one", "08:00", "08:01"), service("another-one", "08:00", "08:01"),
            service("two", "08:00", "08:02"), service("three", "08:00", "08:03")
        )
        val statistics = RouteSearchStatistics.fromServices(services)
        assertEquals(2, statistics.averageMinutes)
        assertTrue(statistics.isSlow(services.last()))
        assertFalse(statistics.isSlow(services[2]))
    }

    @Test
    fun slowWarningIncludesNonRunningServicesInAverageAndHandlesMidnight() {
        val stopped = service("stopped", "08:00", "08:30", isRunning = false)
        val fast = service("fast", "23:30", "00:00")
        val slow = service("slow", "23:00", "01:00")
        val unknown = service("unknown", "--:--", "--:--")
        val equal = service("equal", "08:00", "08:00")
        val statistics = RouteSearchStatistics.fromServices(listOf(stopped, fast, slow, unknown, equal))
        assertEquals(60, statistics.averageMinutes)
        assertTrue(statistics.isSlow(slow))
        assertFalse(statistics.isSlow(unknown))
        assertFalse(statistics.isSlow(equal))
    }

    @Test
    fun slowWarningIsUnavailableWithoutTimedServicesAndNeverFlagsASingleService() {
        val slow = service("slow", "08:00", "12:00")
        assertFalse(RouteSearchStatistics.fromServices(emptyList()).isSlow(slow))
        assertFalse(RouteSearchStatistics.fromServices(listOf(slow)).isSlow(slow))
        val unknown = service("unknown", "invalid", "12:00")
        assertFalse(RouteSearchStatistics.fromServices(listOf(unknown)).isSlow(unknown))
    }

    @Test
    fun retainsSingleMinuteAndLongOvernightDurations() {
        val short = service("short", "23:59", "00:00")
        val long = service("long", "00:01", "00:00")

        val statistics = RouteSearchStatistics.fromServices(listOf(short, long))

        assertEquals(720, statistics.averageMinutes)
    }

    private fun service(
        id: String,
        from: String,
        to: String,
        isRunning: Boolean = true
    ) = BusService(id, id, "ORDINARY", isRunning, 0L, from, to)
}
