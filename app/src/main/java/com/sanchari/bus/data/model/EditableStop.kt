package com.sanchari.bus.data.model

import android.os.Parcelable
import com.sanchari.bus.data.model.BusStop
import kotlinx.parcelize.Parcelize

/**
 * A mutable data class to hold stop information in the editor.
 *
 * --- UPDATED ---
 * Added 'originalStopId' to preserve the DB ID for existing stops.
 * Defaults to -1 for new stops.
 */
@Parcelize
data class EditableStop(
    var stopName: String,
    var scheduledTime: String,
    var stopOrder: Int,
    val originalStopId: Int = -1 // -1 indicates a new stop
) : Parcelable {

    companion object {
        fun fromBusStop(busStop: BusStop): EditableStop {
            return EditableStop(
                stopName = busStop.locationName,
                scheduledTime = busStop.scheduledTime,
                stopOrder = busStop.stopOrder,
                originalStopId = busStop.stopId // Capture the real ID
            )
        }
    }
}