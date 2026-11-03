package com.sanchari.bus.ui.helper

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.ActivityResultRegistry
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.sanchari.bus.R
import com.sanchari.bus.data.manager.StopLocationManager
import com.sanchari.bus.data.model.GeoPoint
import com.sanchari.bus.data.model.NearbyStop
import com.sanchari.bus.data.model.SelectedStopIdentity
import com.sanchari.bus.data.model.StopLocation
import com.sanchari.bus.databinding.ActivityMainBinding
import com.sanchari.bus.databinding.DialogStopLocationBinding
import com.sanchari.bus.ui.activity.SearchResultsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.roundToInt

class StopLocationHandler(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val validateMapStop: (Boolean) -> String?,
    private val deviceLocation: DeviceLocationSource = AndroidDeviceLocationSource(activity),
    private val nearbyLookup: suspend (GeoPoint, Double) -> List<NearbyStop> = { point, radius ->
        withContext(Dispatchers.IO) { StopLocationManager(activity).nearbyStops(point, radius) }
    },
    private val stopLookup: suspend (Int) -> StopLocation? = {
        withContext(Dispatchers.IO) { StopLocationManager(activity).locationById(it) }
    },
    private val maps: StopMapActions = StopMapActions(activity),
    private val permissionGranted: () -> Boolean = {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED }
    },
    permissionRegistry: ActivityResultRegistry? = null
) {
    private var fromSelection: SelectedStopIdentity? = null
    private var toSelection: SelectedStopIdentity? = null
    private var dialog: AlertDialog? = null
    private var content: DialogStopLocationBinding? = null
    private var job: Job? = null
    private var requestGeneration = 0L
    private var targetFrom = true
    private var manualMode = false
    private var coordinates = ""
    private var radius = StopLocationManager.DEFAULT_RADIUS_KM.toString()
    private var currentFix: DeviceLocationFix? = null
    private var restoreManual = false
    private var pendingPermission = false
    private var permissionReady = false
    private var settingsAction = Settings.ACTION_LOCATION_SOURCE_SETTINGS

    private val permissionLauncher = if (permissionRegistry == null) {
        activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions(), ::onPermissionResult)
    } else {
        permissionRegistry.register("stop-location-permission",
            ActivityResultContracts.RequestMultiplePermissions(), ::onPermissionResult)
    }

    private fun onPermissionResult(result: Map<String, Boolean>) {
        if (!pendingPermission || dialog?.isShowing != true || content == null) return
        pendingPermission = false
        if (result.values.any { it } && permissionGranted()) {
            if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) findCurrentLocation()
            else permissionReady = true
        } else {
            settingsAction = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            showFailure(LocationFailure.PERMISSION)
        }
    }

    fun setup(savedState: Bundle?) {
        binding.fromLocationButton.setOnClickListener { showActions(true) }
        binding.toLocationButton.setOnClickListener { showActions(false) }
        binding.fromAutocomplete.doAfterTextChanged {
            if (fromSelection?.matches(it.toString()) != true) fromSelection = null
            maps.cancel()
        }
        binding.toAutocomplete.doAfterTextChanged {
            if (toSelection?.matches(it.toString()) != true) toSelection = null
            maps.cancel()
        }
        if (savedState != null) {
            binding.fromAutocomplete.setText(savedState.getString(KEY_FROM_TEXT, ""), false)
            binding.toAutocomplete.setText(savedState.getString(KEY_TO_TEXT, ""), false)
            fromSelection = restoreSelection(savedState, "from", binding.fromAutocomplete.text.toString())
            toSelection = restoreSelection(savedState, "to", binding.toAutocomplete.text.toString())
            coordinates = savedState.getString(KEY_COORDINATES, "")
            radius = savedState.getString(KEY_RADIUS, StopLocationManager.DEFAULT_RADIUS_KM.toString())
            targetFrom = savedState.getBoolean(KEY_TARGET_FROM, true)
            restoreManual = savedState.getBoolean(KEY_MANUAL_OPEN)
        }
    }

    private fun showActions(from: Boolean) {
        close()
        targetFrom = from
        dismissAutocomplete()
        val text = field(from).text.toString().trim()
        val mapEnabled = text.isNotEmpty() && text != "*"
        val labels = listOf(R.string.location_use_current, R.string.location_enter_coordinates,
            R.string.location_show_stop, R.string.location_show_stop_coordinates).map(activity::getString)
        val adapter = object : ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1, labels) {
            override fun isEnabled(position: Int) = position < 2 || mapEnabled
            override fun areAllItemsEnabled() = mapEnabled
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                super.getView(position, convertView, parent).apply { isEnabled = isEnabled(position) }
        }
        val popup = AlertDialog.Builder(activity)
            .setTitle(if (from) R.string.location_from_action else R.string.location_to_action)
            .setAdapter(adapter) { selectedDialog, which ->
                selectedDialog.dismiss()
                when (which) {
                    0 -> {
                        showNearbyPopup(from, manual = false)
                        requestCurrentLocation()
                    }
                    1 -> showNearbyPopup(from, manual = true)
                    2, 3 -> {
                        val name = validateMapStop(from) ?: return@setAdapter
                        val preferred = selectedId(from, name)
                        val onSelected: (StopLocation) -> Unit = { selected ->
                            if (field(from).text.toString().trim().equals(name, ignoreCase = true)) {
                                remember(from, selected)
                            }
                        }
                        if (which == 3) maps.showStopCoordinates(name, preferred, onSelected)
                        else maps.showStop(name, preferred, onSelected)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog = popup
        popup.setOnDismissListener { if (dialog === popup) dialog = null }
        popup.show()
    }

    private fun showNearbyPopup(from: Boolean, manual: Boolean) {
        close()
        targetFrom = from
        manualMode = manual
        dismissAutocomplete()
        val view = DialogStopLocationBinding.inflate(activity.layoutInflater)
        content = view
        view.coordinateInputs.isVisible = manual
        view.coordinatesInput.setText(coordinates)
        view.radiusInput.setText(radius)
        view.findNearbyStops.setText(if (manual) R.string.location_find_nearby else R.string.location_retry)
        if (manual) {
            view.coordinatesInput.doAfterTextChanged {
                coordinates = it.toString()
                view.coordinatesLayout.error = null
                invalidateCoordinateResults(view)
            }
        }
        view.radiusInput.doAfterTextChanged {
            radius = it.toString()
            view.radiusLayout.error = null
            invalidateCoordinateResults(view)
        }
        view.findNearbyStops.setOnClickListener {
            if (manual) {
                val center = GeoPoint.parse(view.coordinatesInput.text.toString())
                view.coordinatesLayout.error = if (center == null)
                    activity.getString(R.string.location_coordinates_invalid) else null
                val radiusMeters = validateRadius(view)
                if (center == null || radiusMeters == null) return@setOnClickListener
                runLookup(view) { showNearby(view, center, distanceNote(radiusMeters), radiusMeters) }
            } else requestCurrentLocation()
        }
        val builder = AlertDialog.Builder(activity)
            .setTitle(if (from) R.string.location_from_action else R.string.location_to_action)
            .setView(view.root)
            .setNegativeButton(R.string.close, null)
        if (!manual) builder.setNeutralButton(R.string.location_settings) { _, _ -> openSettings() }
        val popup = builder.create()
        dialog = popup
        popup.setOnDismissListener {
            if (dialog === popup) {
                job?.cancel()
                job = null
                pendingPermission = false
                permissionReady = false
                currentFix = null
                dialog = null
                content = null
            }
        }
        popup.show()
    }

    private fun invalidateCoordinateResults(view: DialogStopLocationBinding) {
        requestGeneration++
        job?.cancel()
        job = null
        view.nearbyStops.isVisible = false
        setBusy(view, false)
        view.locationStatus.setText(R.string.location_distance_note)
    }

    private fun requestCurrentLocation() {
        if (pendingPermission) return
        val view = content ?: return
        val radiusMeters = validateRadius(view) ?: return
        currentFix?.let { fix ->
            runLookup(view) { showNearby(view, fix.point, fixNote(fix, radiusMeters), radiusMeters) }
            return
        }
        if (permissionGranted()) findCurrentLocation()
        else {
            pendingPermission = true
            content?.locationStatus?.setText(R.string.location_permission_needed)
            permissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
    }

    private fun findCurrentLocation() {
        val view = content ?: return
        val radiusMeters = validateRadius(view) ?: return
        settingsAction = Settings.ACTION_LOCATION_SOURCE_SETTINGS
        runLookup(view) {
            view.locationStatus.setText(R.string.location_finding)
            val fix = deviceLocation.currentLocation()
            currentCoroutineContext().ensureActive()
            currentFix = fix
            view.findNearbyStops.setText(R.string.location_find_nearby)
            showNearby(view, fix.point, fixNote(fix, radiusMeters), radiusMeters)
        }
    }

    private fun validateRadius(view: DialogStopLocationBinding): Double? {
        val meters = StopLocationManager.parseRadiusMeters(view.radiusInput.text.toString())
        view.radiusLayout.error = if (meters == null)
            activity.getString(R.string.location_radius_invalid) else null
        return meters
    }

    private fun distanceNote(radiusMeters: Double) =
        activity.getString(R.string.location_radius_note, (radiusMeters / 1000).toString())

    private fun fixNote(fix: DeviceLocationFix, radiusMeters: Double): String {
        val accuracy = fix.accuracyMeters?.let {
            activity.getString(R.string.location_accuracy, it.roundToInt())
        }.orEmpty()
        return activity.getString(
            if (fix.approximate) R.string.location_fix_approximate else R.string.location_fix_precise, accuracy
        ) + "\n" + distanceNote(radiusMeters)
    }

    private fun runLookup(view: DialogStopLocationBinding, action: suspend () -> Unit) {
        val generation = ++requestGeneration
        job?.cancel()
        setBusy(view, true)
        view.nearbyStops.isVisible = false
        job = activity.lifecycleScope.launch {
            try {
                action()
            } catch (e: LocationAccessException) {
                if (content === view && generation == requestGeneration) showFailure(e.reason)
            } catch (e: SQLiteException) {
                Log.e("StopLocationHandler", "Stop-coordinate lookup failed", e)
                if (content === view && generation == requestGeneration) {
                    view.locationStatus.setText(R.string.location_lookup_failed)
                }
            } finally {
                if (content === view && generation == requestGeneration) setBusy(view, false)
            }
        }
    }

    private suspend fun showNearby(
        view: DialogStopLocationBinding, center: GeoPoint, note: String, radiusMeters: Double
    ) {
        view.locationStatus.setText(R.string.location_loading_stops)
        val results = nearbyLookup(center, radiusMeters)
        currentCoroutineContext().ensureActive()
        if (content !== view || dialog?.isShowing != true) return
        view.locationStatus.text = if (results.isEmpty()) {
            note + "\n" + activity.getString(R.string.location_no_nearby, (radiusMeters / 1000).toString())
        } else note
        val duplicateNames = results.groupingBy { it.stop.name }.eachCount()
        val labels = results.map { result ->
            val stop = result.stop
            val name = if (duplicateNames.getValue(stop.name) > 1) {
                activity.getString(R.string.location_physical_stop, stop.name, stop.stopId,
                    stop.point.latitude.toString(), stop.point.longitude.toString())
            } else stop.name
            val distance = if (result.distanceMeters < 1000) {
                activity.getString(R.string.location_distance_meters, result.distanceMeters.roundToInt())
            } else activity.getString(R.string.location_distance_km, result.distanceMeters / 1000)
            activity.getString(R.string.location_nearby_row, name, distance)
        }
        view.nearbyStops.adapter = ArrayAdapter(activity, R.layout.item_nearby_stop, labels)
        view.nearbyStops.isVisible = results.isNotEmpty()
        view.nearbyStops.setOnItemClickListener { _, _, position, _ ->
            val chosen = results[position].stop
            val from = targetFrom
            runLookup(view) {
                val fresh = stopLookup(chosen.stopId)
                currentCoroutineContext().ensureActive()
                if (content !== view || dialog?.isShowing != true) return@runLookup
                if (fresh != chosen) {
                    view.locationStatus.setText(R.string.location_selection_changed)
                    return@runLookup
                }
                field(from).setText(fresh.name, false)
                field(from).setSelection(field(from).length())
                remember(from, fresh)
                dismissAutocomplete()
                dialog?.dismiss()
            }
        }
    }

    private fun showFailure(reason: LocationFailure) {
        if (reason == LocationFailure.PERMISSION) settingsAction = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        content?.locationStatus?.setText(when (reason) {
            LocationFailure.PERMISSION -> R.string.location_permission_needed
            LocationFailure.DISABLED -> R.string.location_disabled
            LocationFailure.UNAVAILABLE -> R.string.location_fix_unavailable
        })
    }

    private fun setBusy(view: DialogStopLocationBinding, busy: Boolean) {
        view.locationProgress.isVisible = busy
        view.findNearbyStops.isEnabled = !busy
    }

    private fun openSettings() {
        val intent = Intent(settingsAction)
        if (settingsAction == Settings.ACTION_APPLICATION_DETAILS_SETTINGS) {
            intent.data = Uri.parse("package:${activity.packageName}")
        }
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, R.string.location_settings_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun field(from: Boolean) = if (from) binding.fromAutocomplete else binding.toAutocomplete

    private fun remember(from: Boolean, stop: StopLocation) {
        val selection = SelectedStopIdentity(stop.stopId, stop.name)
        if (from) fromSelection = selection else toSelection = selection
    }

    fun selectedId(from: Boolean, name: String): Int? =
        (if (from) fromSelection else toSelection)?.takeIf { it.matches(name) }?.stopId

    fun swapFields() {
        val fromText = binding.fromAutocomplete.text.toString()
        val toText = binding.toAutocomplete.text.toString()
        val oldFrom = fromSelection
        val oldTo = toSelection
        binding.fromAutocomplete.setText(toText, false)
        binding.toAutocomplete.setText(fromText, false)
        fromSelection = oldTo?.takeIf { it.matches(toText) }
        toSelection = oldFrom?.takeIf { it.matches(fromText) }
        dismissAutocomplete()
    }

    fun addSearchMetadata(intent: Intent, from: String, to: String) {
        selectedId(true, from)?.let { intent.putExtra(SearchResultsActivity.EXTRA_FROM_STOP_ID, it) }
        selectedId(false, to)?.let { intent.putExtra(SearchResultsActivity.EXTRA_TO_STOP_ID, it) }
    }

    private fun dismissAutocomplete() {
        binding.fromAutocomplete.dismissDropDown()
        binding.toAutocomplete.dismissDropDown()
        binding.busNameAutocomplete.dismissDropDown()
    }

    fun onPause() {
        currentFix = null
        if (!manualMode) content?.findNearbyStops?.setText(R.string.location_retry)
        if (job?.isActive == true) {
            requestGeneration++
            job?.cancel()
            job = null
            content?.let {
                setBusy(it, false)
                it.locationStatus.setText(R.string.location_interrupted)
            }
        }
        maps.cancel()
    }

    fun onResume() {
        if (restoreManual) {
            restoreManual = false
            showNearbyPopup(targetFrom, manual = true)
        } else if (permissionReady && dialog?.isShowing == true) {
            permissionReady = false
            findCurrentLocation()
        }
    }

    fun close() {
        requestGeneration++
        job?.cancel()
        job = null
        maps.cancel()
        pendingPermission = false
        permissionReady = false
        restoreManual = false
        currentFix = null
        dialog?.dismiss()
        dialog = null
        content = null
    }

    fun onStop(changingConfigurations: Boolean) {
        if (!changingConfigurations) {
            pendingPermission = false
            permissionReady = false
            return
        }
        val reopenManual = manualMode &&
            content != null && dialog?.isShowing == true
        close()
        restoreManual = reopenManual
    }

    fun destroy() {
        close()
        permissionLauncher.unregister()
    }

    fun saveState(out: Bundle) {
        out.putString(KEY_FROM_TEXT, binding.fromAutocomplete.text.toString())
        out.putString(KEY_TO_TEXT, binding.toAutocomplete.text.toString())
        for ((prefix, selection) in listOf("from" to fromSelection, "to" to toSelection)) {
            selection?.let {
                out.putInt("location_${prefix}_id", it.stopId)
                out.putString("location_${prefix}_name", it.name)
            }
        }
        out.putBoolean(KEY_MANUAL_OPEN, restoreManual ||
            (manualMode && content != null && dialog?.isShowing == true))
        out.putBoolean(KEY_TARGET_FROM, targetFrom)
        out.putString(KEY_COORDINATES, coordinates)
        out.putString(KEY_RADIUS, radius)
    }

    private fun restoreSelection(state: Bundle, prefix: String, text: String): SelectedStopIdentity? {
        val id = state.getInt("location_${prefix}_id", -1)
        val name = state.getString("location_${prefix}_name") ?: return null
        return if (id >= 0) SelectedStopIdentity(id, name).takeIf { it.matches(text) } else null
    }

    companion object {
        private const val KEY_FROM_TEXT = "location_from_text"
        private const val KEY_TO_TEXT = "location_to_text"
        private const val KEY_COORDINATES = "location_coordinates"
        private const val KEY_RADIUS = "location_radius"
        private const val KEY_TARGET_FROM = "location_target_from"
        private const val KEY_MANUAL_OPEN = "location_manual_open"
    }
}
