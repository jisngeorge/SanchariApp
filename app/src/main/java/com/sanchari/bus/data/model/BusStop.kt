package com.sanchari.bus.data.model

import kotlinx.parcelize.Parcelize
import android.os.Parcelable

@Parcelize
data class BusStop(
    val stopId: Int,
    val serviceId: String,
    val locationName: String,
    val latitude: Double,
    val longitude: Double,
    val scheduledTime: String,
    val stopOrder: Int
) : Parcelable

