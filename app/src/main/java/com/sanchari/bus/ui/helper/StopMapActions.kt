package com.sanchari.bus.ui.helper

import android.database.sqlite.SQLiteException
import android.util.Log
import android.widget.Toast
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sanchari.bus.R
import com.sanchari.bus.data.manager.StopLocationManager
import com.sanchari.bus.data.model.StopLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.resume

class StopMapActions(
    private val activity: AppCompatActivity,
    private val loadLocations: suspend (String) -> List<StopLocation> = {
        withContext(Dispatchers.IO) { StopLocationManager(activity).locationsForName(it) }
    },
    private val loadStop: suspend (Int) -> StopLocation? = {
        withContext(Dispatchers.IO) { StopLocationManager(activity).locationById(it) }
    }
) {
    private var job: Job? = null
    private var chooser: AlertDialog? = null

    fun showStopOptions(name: String, stopIds: List<Int>) {
        cancel()
        job = activity.lifecycleScope.launch {
            try {
                val candidates = stopIds.distinct().map { id ->
                    loadStop(id)?.takeIf { it.name == name }
                }
                currentCoroutineContext().ensureActive()
                if (candidates.isEmpty() || candidates.any { it == null }) {
                    Toast.makeText(activity, activity.getString(R.string.location_stop_unavailable, name), Toast.LENGTH_LONG).show()
                    return@launch
                }
                val locations = candidates.filterNotNull()
                val stop = locations.singleOrNull() ?: choose(name, locations) ?: return@launch
                val action = chooseStopAction(stop.name) ?: return@launch
                val fresh = loadStop(stop.stopId)
                currentCoroutineContext().ensureActive()
                if (fresh == null || fresh != stop) {
                    Toast.makeText(activity, R.string.location_selection_changed, Toast.LENGTH_LONG).show()
                    return@launch
                }
                if (action == 0) {
                    MapNavigationHelper.showStop(activity, fresh.point)
                } else {
                    showCoordinates(fresh)
                }
            } catch (e: SQLiteException) {
                showLookupError(e)
            }
        }
    }

    private suspend fun chooseStopAction(name: String): Int? =
        suspendCancellableCoroutine { continuation ->
            val dialog = AlertDialog.Builder(activity)
                .setTitle(name)
                .setItems(arrayOf(activity.getString(R.string.stop_open_map),
                    activity.getString(R.string.stop_show_coordinates))) { _, index ->
                    if (continuation.isActive) continuation.resume(index)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
            chooser = dialog
            dialog.setOnDismissListener {
                if (chooser === dialog) chooser = null
                if (continuation.isActive) continuation.resume(null)
            }
            continuation.invokeOnCancellation { activity.runOnUiThread { dialog.dismiss() } }
            dialog.show()
        }

    private fun showCoordinates(stop: StopLocation) {
        val dialog = AlertDialog.Builder(activity)
            .setTitle(stop.name)
            .setMessage(activity.getString(R.string.stop_coordinates_value,
                stop.point.latitude.toString(), stop.point.longitude.toString()))
            .setPositiveButton(android.R.string.ok, null)
            .create()
        chooser = dialog
        dialog.setOnDismissListener { if (chooser === dialog) chooser = null }
        dialog.show()
        dialog.findViewById<TextView>(android.R.id.message)?.setTextIsSelectable(true)
    }

    fun showStop(name: String, preferredId: Int?, onSelected: (StopLocation) -> Unit = {}) {
        showResolvedStop(name, preferredId, false, onSelected)
    }

    fun showStopCoordinates(name: String, preferredId: Int?, onSelected: (StopLocation) -> Unit = {}) {
        showResolvedStop(name, preferredId, true, onSelected)
    }

    private fun showResolvedStop(
        name: String,
        preferredId: Int?,
        coordinatesOnly: Boolean,
        onSelected: (StopLocation) -> Unit
    ) {
        cancel()
        job = activity.lifecycleScope.launch {
            try {
                val stop = resolve(name, preferredId) ?: return@launch
                val fresh = loadStop(stop.stopId)
                currentCoroutineContext().ensureActive()
                if (fresh == null || fresh != stop) {
                    Toast.makeText(activity, R.string.location_selection_changed, Toast.LENGTH_LONG).show()
                    return@launch
                }
                onSelected(fresh)
                if (coordinatesOnly) showCoordinates(fresh) else MapNavigationHelper.showStop(activity, fresh.point)
            } catch (e: SQLiteException) {
                showLookupError(e)
            }
        }
    }

    fun showDirections(from: String, to: String, fromId: Int?, toId: Int?) {
        cancel()
        job = activity.lifecycleScope.launch {
            try {
                val origin = resolve(from, fromId) ?: return@launch
                val destination = resolve(to, toId) ?: return@launch
                // Re-read after a chooser, since a database update can replace coordinates.
                val endpoints = loadStop(origin.stopId) to loadStop(destination.stopId)
                currentCoroutineContext().ensureActive()
                val currentOrigin = endpoints.first
                val currentDestination = endpoints.second
                if (currentOrigin == null || currentDestination == null ||
                    currentOrigin != origin || currentDestination != destination) {
                    Toast.makeText(activity, R.string.location_selection_changed, Toast.LENGTH_LONG).show()
                    return@launch
                }
                MapNavigationHelper.showDirections(activity, currentOrigin.point, currentDestination.point)
            } catch (e: SQLiteException) {
                showLookupError(e)
            }
        }
    }

    private suspend fun resolve(name: String, preferredId: Int?): StopLocation? {
        if (name.isBlank() || name.trim() == "*") {
            Toast.makeText(activity, R.string.location_enter_stop, Toast.LENGTH_LONG).show()
            return null
        }
        val candidates = loadLocations(name)
        currentCoroutineContext().ensureActive()
        if (candidates.isEmpty()) {
            Toast.makeText(activity, activity.getString(R.string.location_stop_unavailable, name), Toast.LENGTH_LONG).show()
            return null
        }
        candidates.singleOrNull { it.stopId == preferredId }?.let { return it }
        if (preferredId != null) {
            Toast.makeText(activity, R.string.location_selection_changed, Toast.LENGTH_LONG).show()
        } else if (candidates.size == 1) return candidates.single()
        return choose(name, candidates)
    }

    private suspend fun choose(name: String, candidates: List<StopLocation>): StopLocation? =
        suspendCancellableCoroutine { continuation ->
            val dialog = AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.location_choose_physical_stop, name))
                .setItems(candidates.map {
                    activity.getString(R.string.location_physical_stop, it.name, it.stopId,
                        it.point.latitude.toString(), it.point.longitude.toString())
                }.toTypedArray()) { _, index ->
                    if (continuation.isActive) continuation.resume(candidates[index])
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
            chooser = dialog
            dialog.setOnDismissListener {
                if (chooser === dialog) chooser = null
                if (continuation.isActive) continuation.resume(null)
            }
            continuation.invokeOnCancellation { activity.runOnUiThread { dialog.dismiss() } }
            dialog.show()
        }

    private fun showLookupError(error: SQLiteException) {
        Log.e("StopMapActions", "Stop-coordinate lookup failed", error)
        Toast.makeText(activity, R.string.location_lookup_failed, Toast.LENGTH_LONG).show()
    }

    fun cancel() {
        job?.cancel()
        job = null
        chooser?.dismiss()
        chooser = null
    }
}
