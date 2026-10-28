package com.sanchari.bus.ui.helper

object TimetableTimeFormatter {
    private val clockTime = Regex("([01][0-9]|2[0-3]):[0-5][0-9]")

    fun format(time: String): String {
        if (!clockTime.matches(time)) return time
        val hour = time.substring(0, 2).toInt()
        val displayHour = if (hour % 12 == 0) 12 else hour % 12
        val period = if (hour < 12) "AM" else "PM"
        return "$displayHour:${time.substring(3, 5)} $period"
    }
}
