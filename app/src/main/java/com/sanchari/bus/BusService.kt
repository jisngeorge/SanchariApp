package com.sanchari.bus

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Data class to hold information about a single bus service.
 * Updated to be Parcelable so it can be passed between activities.
 */
@Parcelize
data class BusService(
    val serviceId: String,
    val name: String,
    val type: String,
    val isRunning: Boolean,
    val lastReportedTime: Long
) : Parcelable

