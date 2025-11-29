package com.sanchari.bus.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * A mutable data class to hold stop information in the editor.
 * This is different from BusStop, which is immutable.
 *
 * --- UPDATED: Made Parcelable to support saving instance state ---
 */
@Parcelize
data class EditableStop(
    var stopName: String,
    var scheduledTime: String,
    var stopOrder: Int
) : Parcelable { // <-- IMPLEMENT PARCELABLE
    // Helper to convert from our main BusStop model
    companion object {
        fun fromBusStop(busStop: BusStop): EditableStop {
            return EditableStop(
                stopName = busStop.locationName,
                scheduledTime = busStop.scheduledTime,
                stopOrder = busStop.stopOrder
            )
        }
    }
}