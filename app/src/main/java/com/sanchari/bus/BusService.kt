package com.sanchari.bus

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Represents a Bus Service.
 *
 * @param fromTime The departure time from the 'from' location. (NEW)
 * @param toTime The arrival time at the 'to' location. (NEW)
 */
@Parcelize
data class BusService(
    val serviceId: String,
    val name: String,
    val type: String,
    val isRunning: Boolean,
    val lastReportedTime: Long,
    val fromTime: String, // NEW: e.g., "08:30"
    val toTime: String   // NEW: e.g., "10:15"
) : Parcelable

