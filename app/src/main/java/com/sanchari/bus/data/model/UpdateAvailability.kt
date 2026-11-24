package com.sanchari.bus.data.model

internal data class UpdateAvailability(
    val app: Boolean = false,
    val timetable: Boolean = false,
    val community: Boolean = false
) {
    val any: Boolean get() = app || timetable || community

    fun showHomeAction(autoDatabaseUpdates: Boolean): Boolean =
        app || (!autoDatabaseUpdates && (timetable || community))
}

/** Last remote versions seen in a successful manifest check. */
internal data class RemoteVersions(val app: Int, val timetable: Int, val community: Int)
