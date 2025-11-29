package com.sanchari.bus.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Data class to hold information about a recently viewed bus service.
 * Using Parcelable in case we ever want to pass this list.
 */
@Parcelize
data class RecentSearch(
    val serviceId: String,
    val serviceName: String,
    val viewedTimestamp: Long
) : Parcelable
