package com.sanchari.bus.ui.helper

import org.junit.Assert.assertEquals
import org.junit.Test

class TimetableTimeFormatterTest {
    @Test
    fun displaysMidnightNoonAndBothPeriods() {
        val examples = mapOf(
            "00:00" to "12:00 AM",
            "00:15" to "12:15 AM",
            "01:05" to "1:05 AM",
            "11:59" to "11:59 AM",
            "12:00" to "12:00 PM",
            "12:30" to "12:30 PM",
            "14:30" to "2:30 PM",
            "23:59" to "11:59 PM"
        )
        examples.forEach { (stored, displayed) ->
            assertEquals(displayed, TimetableTimeFormatter.format(stored))
        }
    }

    @Test
    fun preservesEmptyUnknownAndMalformedValues() {
        listOf("", "--:--", "24:00", "12:60", "9:05", " 09:05", "unknown").forEach {
            assertEquals(it, TimetableTimeFormatter.format(it))
        }
    }
}
