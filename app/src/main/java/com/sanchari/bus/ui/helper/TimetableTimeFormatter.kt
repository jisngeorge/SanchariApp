package com.sanchari.bus.ui.helper

import com.sanchari.bus.data.model.RouteSearchStatistics

object TimetableTimeFormatter {
    fun format(time: String): String {
        val totalMinutes = RouteSearchStatistics.parseMinutes(time) ?: return time
        val hour = totalMinutes / 60
        val displayHour = if (hour % 12 == 0) 12 else hour % 12
        val period = if (hour < 12) "AM" else "PM"
        return "$displayHour:${time[3]}${time[4]} $period"
    }
}
