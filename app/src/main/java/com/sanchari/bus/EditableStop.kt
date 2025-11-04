package com.sanchari.bus

/**
 * A mutable data class to hold stop information in the editor.
 * This is different from BusStop, which is immutable.
 */
data class EditableStop(
    var stopName: String,
    var scheduledTime: String,
    var stopOrder: Int
) {
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
