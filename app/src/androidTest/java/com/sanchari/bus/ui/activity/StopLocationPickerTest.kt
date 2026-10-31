package com.sanchari.bus.ui.activity

import android.content.Intent
import android.database.sqlite.SQLiteException
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.RootMatchers.withDecorView
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Lifecycle
import com.sanchari.bus.R
import com.sanchari.bus.data.manager.StopLocationManager
import com.sanchari.bus.data.model.*
import com.sanchari.bus.databinding.ActivityMainBinding
import com.sanchari.bus.databinding.DialogStopLocationBinding
import com.sanchari.bus.ui.helper.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.hamcrest.Matchers.anything
import org.hamcrest.Matchers.allOf
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class StopLocationPickerTest {
    private lateinit var binding: ActivityMainBinding
    private lateinit var handler: StopLocationHandler
    private lateinit var popupBinding: DialogStopLocationBinding
    private val palai = StopLocation(1, "Palai", GeoPoint(10.0, 76.0))
    private val kochi = StopLocation(2, "Kochi", GeoPoint(10.1, 76.1))
    private val queryCount = AtomicInteger()
    private val permissionCount = AtomicInteger()
    private var receivedRadius = 0.0
    private var permission = false
    private var grantPermission = false
    private var currentFix = DeviceLocationSource { DeviceLocationFix(GeoPoint(10.0, 76.0), true, 500f) }
    private var lookup: suspend (GeoPoint) -> List<NearbyStop> = {
        listOf(NearbyStop(palai, 100.0), NearbyStop(kochi, 200.0))
    }
    private var reload: suspend (Int) -> StopLocation? = { if (it == 1) palai else kochi }

    @Test
    fun manualCoordinatesValidateAndOnlyFillTheInitiatingFieldWithoutPermissions() {
        launch().use { scenario ->
            scenario.onActivity {
                binding.fromAutocomplete.setText("Palai", false)
                binding.toLocationButton.performClick()
            }
            chooseManual()
            scenario.onActivity {
                popup().coordinatesInput.setText("91, 181")
                popup().findNearbyStops.performClick()
                assertNotNull(popup().coordinatesLayout.error)
                assertEquals(0, queryCount.get())
                popup().coordinatesInput.setText("10, 76")
                popup().findNearbyStops.performClick()
            }
            await(scenario) { popup().nearbyStops.adapter?.count == 2 }
            chooseNearby(1)
            scenario.onActivity {
                assertEquals("Palai", binding.fromAutocomplete.text.toString())
                assertEquals("Kochi", binding.toAutocomplete.text.toString())
                assertEquals(2, handler.selectedId(false, "Kochi"))
                assertEquals(0, permissionCount.get())
                assertFalse(binding.toAutocomplete.isPopupShowing)
                assertTrue(binding.searchButton.isEnabled)
            }
        }
    }

    @Test
    fun manualRadiusDefaultsToFiveKmValidatesAndFiltersAtTheRequestedDistance() {
        launch().use { scenario ->
            scenario.onActivity { binding.fromLocationButton.performClick() }
            chooseManual()
            scenario.onActivity { assertEquals("5", popup().radiusInput.text.toString()) }
            findAt(scenario, "10", "76")
            await(scenario) { popup().nearbyStops.adapter?.count == 2 }
            scenario.onActivity {
                assertEquals(5_000.0, receivedRadius, 0.0)
                popup().radiusInput.setText("0")
                assertFalse(popup().nearbyStops.isShown)
                popup().findNearbyStops.performClick()
                assertNotNull(popup().radiusLayout.error)
                assertEquals(1, queryCount.get())
                popup().radiusInput.setText("0.15")
                assertNull(popup().radiusLayout.error)
                popup().findNearbyStops.performClick()
            }
            await(scenario) { popup().nearbyStops.adapter?.count == 1 && popup().nearbyStops.isShown }
            scenario.onActivity {
                assertEquals(150.0, receivedRadius, 0.0)
                popup().radiusInput.setText("40")
                popup().findNearbyStops.performClick()
            }
            await(scenario) { popup().nearbyStops.adapter?.count == 2 && popup().nearbyStops.isShown }
            scenario.onActivity {
                assertEquals(40_000.0, receivedRadius, 0.0)
                assertTrue(popup().locationStatus.text.contains("40.0 km"))
                assertEquals(0, permissionCount.get())
            }
        }
    }

    @Test
    fun currentLocationRadiusChangesReuseOnlyTheForegroundFix() {
        permission = true
        val fixCount = AtomicInteger()
        currentFix = DeviceLocationSource {
            fixCount.incrementAndGet()
            DeviceLocationFix(GeoPoint(10.0, 76.0), false, 10f)
        }
        launch().use { scenario ->
            scenario.onActivity { binding.fromLocationButton.performClick() }
            chooseCurrent()
            await(scenario) { popup().nearbyStops.adapter?.count == 2 }
            scenario.onActivity {
                assertEquals(5_000.0, receivedRadius, 0.0)
                popup().radiusInput.setText("0.15")
                popup().findNearbyStops.performClick()
            }
            await(scenario) { popup().nearbyStops.adapter?.count == 1 && popup().nearbyStops.isShown }
            scenario.onActivity {
                assertEquals(1, fixCount.get())
                assertEquals(150.0, receivedRadius, 0.0)
                handler.onPause()
                popup().radiusInput.setText("40")
                popup().findNearbyStops.performClick()
            }
            await(scenario) { popup().nearbyStops.adapter?.count == 2 && popup().nearbyStops.isShown }
            scenario.onActivity {
                assertEquals(2, fixCount.get())
                assertEquals(40_000.0, receivedRadius, 0.0)
                handler.close()
                binding.toLocationButton.performClick()
            }
            chooseCurrent()
            await(scenario) { popup().nearbyStops.adapter?.count == 2 }
            scenario.onActivity { assertEquals(3, fixCount.get()) }
        }
    }

    @Test
    fun editingRadiusCancelsSlowResultsAndDoesNotRestoreThePreviousRadiusList() {
        val release = CompletableDeferred<Unit>()
        lookup = {
            if (queryCount.get() == 1) withContext(NonCancellable) { release.await() }
            listOf(NearbyStop(palai, 100.0), NearbyStop(kochi, 200.0))
        }
        try {
            launch().use { scenario ->
                scenario.onActivity { binding.fromLocationButton.performClick() }
                chooseManual()
                findAt(scenario, "10", "76")
                scenario.onActivity {
                    assertEquals(1, queryCount.get())
                    popup().radiusInput.setText("0.15")
                    popup().findNearbyStops.performClick()
                    release.complete(Unit)
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity {
                    assertEquals(1, popup().nearbyStops.adapter.count)
                    assertTrue(popup().locationStatus.text.contains("0.15 km"))
                    assertTrue(popup().findNearbyStops.isEnabled)
                }
            }
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun deniedPermissionShowsAnErrorAndManualCoordinatesRemainUsable() {
        launch().use { scenario ->
            scenario.onActivity { binding.fromLocationButton.performClick() }
            chooseCurrent()
            await(scenario) {
                popup().locationStatus.text.toString().contains("Allow location access")
            }
            scenario.onActivity {
                assertEquals(1, permissionCount.get())
                assertEquals(0, queryCount.get())
            }
            onView(withText(R.string.close)).inRoot(isDialog()).perform(click())
            scenario.onActivity { binding.fromLocationButton.performClick() }
            chooseManual()
            findAt(scenario, "10", "76")
            await(scenario) { popup().nearbyStops.adapter?.count == 2 }
            chooseNearby(0)
            scenario.onActivity {
                assertEquals("Palai", binding.fromAutocomplete.text.toString())
                assertEquals(1, permissionCount.get())
            }
        }
    }

    @Test
    fun approximatePermissionAndCurrentFixShowAccuracyAndNearbyStops() {
        grantPermission = true
        launch().use { scenario ->
            scenario.onActivity { binding.fromLocationButton.performClick() }
            chooseCurrent()
            await(scenario) { popup().nearbyStops.adapter?.count == 2 }
            scenario.onActivity {
                assertTrue(popup().locationStatus.text.contains("Approximate"))
                assertTrue(popup().locationStatus.text.contains("500"))
                assertEquals(1, permissionCount.get())
            }
        }
    }

    @Test
    fun disabledAndUnavailableProvidersProduceExplicitRetryStates() {
        permission = true
        for (reason in listOf(LocationFailure.DISABLED, LocationFailure.UNAVAILABLE)) {
            currentFix = DeviceLocationSource { throw LocationAccessException(reason) }
            launch().use { scenario ->
                scenario.onActivity { binding.fromLocationButton.performClick() }
                chooseCurrent()
                await(scenario) { popup().findNearbyStops.isEnabled }
                scenario.onActivity { activity ->
                    assertEquals(activity.getString(if (reason == LocationFailure.DISABLED)
                        R.string.location_disabled else R.string.location_fix_unavailable),
                        popup().locationStatus.text.toString())
                    assertEquals(0, queryCount.get())
                }
            }
        }
    }

    @Test
    fun emptyNearbyResultsAreNotTreatedAsASelection() {
        lookup = { emptyList() }
        launch().use { scenario ->
            scenario.onActivity { binding.fromLocationButton.performClick() }
            chooseManual()
            findAt(scenario, "0", "0")
            await(scenario) { popup().locationStatus.text.contains("No stops") }
            scenario.onActivity {
                assertEquals("", binding.fromAutocomplete.text.toString())
                assertFalse(popup().nearbyStops.isShown)
            }
        }
    }

    @Test
    fun cancellingASlowRequestCannotFillOrReplaceAnotherFieldsPopup() {
        val oldStarted = AtomicInteger()
        lookup = {
            if (it.latitude == 10.0) {
                oldStarted.incrementAndGet()
                withContext(NonCancellable) { delay(400) }
                listOf(NearbyStop(palai, 100.0))
            } else listOf(NearbyStop(kochi, 200.0))
        }
        launch().use { scenario ->
            scenario.onActivity { binding.fromLocationButton.performClick() }
            chooseManual()
            findAt(scenario, "10", "76")
            await(scenario) { oldStarted.get() == 1 }
            onView(withText(R.string.close)).inRoot(isDialog()).perform(click())
            scenario.onActivity { binding.toLocationButton.performClick() }
            chooseManual()
            findAt(scenario, "11", "76")
            await(scenario) { popup().nearbyStops.adapter?.count == 1 }
            SystemClock.sleep(450)
            scenario.onActivity {
                assertTrue(popup().nearbyStops.adapter.getItem(0).toString().contains("Kochi"))
            }
            chooseNearby(0)
            scenario.onActivity {
                assertEquals("", binding.fromAutocomplete.text.toString())
                assertEquals("Kochi", binding.toAutocomplete.text.toString())
            }
        }
    }

    @Test
    fun changedCoordinateInputInvalidatesResultsAndChangedDatabaseRowsCannotBeSelected() {
        launch().use { scenario ->
            scenario.onActivity { binding.fromLocationButton.performClick() }
            chooseManual()
            findAt(scenario, "10", "76")
            await(scenario) { popup().nearbyStops.adapter?.count == 2 }
            scenario.onActivity {
                popup().coordinatesInput.setText("11, 76")
                assertFalse(popup().nearbyStops.isShown)
                reload = { palai.copy(point = GeoPoint(12.0, 76.0)) }
                popup().findNearbyStops.performClick()
            }
            await(scenario) { popup().nearbyStops.isShown }
            chooseNearby(0)
            scenario.onActivity { activity ->
                assertEquals("", binding.fromAutocomplete.text.toString())
                assertEquals(activity.getString(R.string.location_selection_changed), popup().locationStatus.text)
            }
        }
    }

    @Test
    fun chosenPhysicalIdsSwapRestoreAndInvalidateWithoutChangingNameBasedSearch() {
        launch().use { scenario ->
            scenario.onActivity { binding.fromLocationButton.performClick() }
            chooseManual()
            findAt(scenario, "10", "76")
            await(scenario) { popup().nearbyStops.adapter?.count == 2 }
            chooseNearby(0)
            scenario.onActivity { binding.toLocationButton.performClick() }
            chooseManual()
            findAt(scenario, "10", "76")
            await(scenario) { popup().nearbyStops.adapter?.count == 2 }
            chooseNearby(1)
            scenario.onActivity { activity ->
                handler.swapFields()
                assertEquals("Kochi", binding.fromAutocomplete.text.toString())
                assertEquals("Palai", binding.toAutocomplete.text.toString())
                val state = Bundle()
                handler.saveState(state)
                handler.destroy()
                install(activity, state)
                val intent = Intent()
                handler.addSearchMetadata(intent, "Kochi", "Palai")
                assertEquals(2, intent.getIntExtra(SearchResultsActivity.EXTRA_FROM_STOP_ID, -1))
                assertEquals(1, intent.getIntExtra(SearchResultsActivity.EXTRA_TO_STOP_ID, -1))
                binding.fromAutocomplete.setText("Changed", false)
                binding.toWildcardButton.performClick()
                assertNull(handler.selectedId(true, "Changed"))
                assertNull(handler.selectedId(false, "*"))
            }
        }
    }

    @Test
    fun databaseFailureShowsAnExplicitErrorAndCanBeRetried() {
        lookup = { throw SQLiteException("Test snapshot unavailable") }
        launch().use { scenario ->
            scenario.onActivity { binding.fromLocationButton.performClick() }
            chooseManual()
            findAt(scenario, "10", "76")
            scenario.onActivity { activity ->
                assertEquals(activity.getString(R.string.location_lookup_failed), popup().locationStatus.text)
                assertTrue(popup().findNearbyStops.isEnabled)
                lookup = { listOf(NearbyStop(palai, 100.0)) }
                popup().findNearbyStops.performClick()
            }
            await(scenario) { popup().nearbyStops.adapter?.count == 1 }
            chooseNearby(0)
            scenario.onActivity { assertEquals("Palai", binding.fromAutocomplete.text.toString()) }
        }
    }

    @Test
    fun pausingCancelsCurrentLocationEvenIfTheSourceReturnsAfterCancellation() {
        permission = true
        val result = CompletableDeferred<DeviceLocationFix>()
        val fix = DeviceLocationFix(GeoPoint(10.0, 76.0), false, 10f)
        currentFix = DeviceLocationSource {
            withContext(NonCancellable) { result.await() }
        }
        try {
            launch().use { scenario ->
                scenario.onActivity { binding.fromLocationButton.performClick() }
                chooseCurrent()
                scenario.onActivity {
                    handler.onPause()
                    result.complete(fix)
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { activity ->
                    assertEquals(0, queryCount.get())
                    assertEquals(activity.getString(R.string.location_interrupted), popup().locationStatus.text)
                    assertFalse(popup().locationProgress.isShown)
                    assertFalse(popup().nearbyStops.isShown)
                }
            }
        } finally {
            result.complete(fix)
        }
    }

    @Test
    fun switchingAppsKeepsTheCoordinatePopupAndDoesNotResumeCancelledLookups() {
        val release = CompletableDeferred<Unit>()
        lookup = {
            withContext(NonCancellable) { release.await() }
            listOf(NearbyStop(palai, 100.0))
        }
        try {
            launch().use { scenario ->
                scenario.onActivity { binding.fromLocationButton.performClick() }
                chooseManual()
                val originalPopup = popup()
                scenario.onActivity { popup().radiusInput.setText("12.5") }
                findAt(scenario, "10", "76")
                scenario.moveToState(Lifecycle.State.CREATED)
                release.complete(Unit)
                scenario.moveToState(Lifecycle.State.RESUMED)
                capturePopup()
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { activity ->
                    assertSame(originalPopup.root, popup().root)
                    assertEquals("10, 76", popup().coordinatesInput.text.toString())
                    assertEquals("12.5", popup().radiusInput.text.toString())
                    assertEquals(1, queryCount.get())
                    assertFalse(popup().nearbyStops.isShown)
                    assertFalse(popup().locationProgress.isShown)
                    assertEquals(activity.getString(R.string.location_interrupted), popup().locationStatus.text)
                }
            }
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun restoringCoordinateInputDoesNotRestartLocationOrNearbyAnalysis() {
        launch().use { scenario ->
            scenario.onActivity { binding.fromLocationButton.performClick() }
            chooseManual()
            scenario.onActivity { activity ->
                popup().coordinatesInput.setText("12.25, 77.5")
                popup().radiusInput.setText("12.5")
                val state = Bundle()
                handler.saveState(state)
                handler.destroy()
                install(activity, state)
                handler.onResume()
            }
            capturePopup()
            scenario.onActivity {
                assertEquals("12.25, 77.5", popup().coordinatesInput.text.toString())
                assertEquals("12.5", popup().radiusInput.text.toString())
                assertEquals(0, queryCount.get())
                assertEquals(0, permissionCount.get())
            }
        }
    }

    private fun chooseManual() {
        onData(anything()).inRoot(isDialog()).atPosition(1).perform(click())
        capturePopup()
    }

    private fun chooseCurrent() {
        onData(anything()).inRoot(isDialog()).atPosition(0).perform(click())
        capturePopup()
    }

    private fun chooseNearby(position: Int) =
        onData(anything()).inRoot(nearbyRoot()).inAdapterView(withId(R.id.nearbyStops))
            .atPosition(position).perform(click())

    private fun findAt(scenario: ActivityScenario<SearchResultsActivity>, lat: String, lon: String) {
        scenario.onActivity {
            popup().coordinatesInput.setText("$lat, $lon")
            popup().findNearbyStops.performClick()
        }
    }

    private fun popup(): DialogStopLocationBinding = popupBinding

    private fun capturePopup() {
        onView(withId(R.id.findNearbyStops)).inRoot(nearbyRoot()).check { button, error ->
            if (error != null) throw error
            var parent = button.parent as View
            while (parent !is androidx.core.widget.NestedScrollView) parent = parent.parent as View
            popupBinding = DialogStopLocationBinding.bind(parent)
        }

    }

    private fun nearbyRoot() = allOf(isDialog(),
        withDecorView(hasDescendant(withId(R.id.findNearbyStops))))

    private fun launch(): ActivityScenario<SearchResultsActivity> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val result = RouteSearchResult(
            BusService("fixture", "Fixture", "ORDINARY", true, 0L, "08:00", "09:00"),
            "Palai", "Kochi", 1, 2
        )
        val intent = Intent(context, SearchResultsActivity::class.java).apply {
            putParcelableArrayListExtra(SearchResultsActivity.EXTRA_ROUTE_RESULTS, arrayListOf(result))
            putExtra(SearchResultsActivity.EXTRA_FROM_STOP, "Palai")
            putExtra(SearchResultsActivity.EXTRA_TO_STOP, "Kochi")
        }
        return ActivityScenario.launch<SearchResultsActivity>(intent).also { scenario ->
            scenario.onActivity { install(it) }
        }
    }

    private fun install(activity: SearchResultsActivity, state: Bundle? = null) {
        binding = ActivityMainBinding.inflate(activity.layoutInflater)
        activity.setContentView(binding.root)
        val search = BusSearchHandler(activity, binding)
        search.setup()
        search.setStopSuggestions(listOf("Palai", "Kochi"))
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>,
                input: I, options: ActivityOptionsCompat?) {
                permissionCount.incrementAndGet()
                permission = grantPermission
                dispatchResult(requestCode, mapOf(
                    android.Manifest.permission.ACCESS_COARSE_LOCATION to grantPermission,
                    android.Manifest.permission.ACCESS_FINE_LOCATION to false
                ))
            }
        }
        handler = StopLocationHandler(activity, binding, search::validateMapStop,
            deviceLocation = currentFix,
            nearbyLookup = { point, meters ->
                queryCount.incrementAndGet()
                receivedRadius = meters
                StopLocationManager.rankNearby(lookup(point), meters)
            },
            stopLookup = { reload(it) },
            permissionGranted = { permission }, permissionRegistry = registry)
        handler.setup(state)
        val installed = handler
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onPause(owner: LifecycleOwner) = installed.onPause()
            override fun onStop(owner: LifecycleOwner) = installed.onStop(activity.isChangingConfigurations)
            override fun onDestroy(owner: LifecycleOwner) = installed.destroy()
        })
    }

    private fun await(scenario: ActivityScenario<SearchResultsActivity>, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        do {
            var matched = false
            scenario.onActivity { matched = condition() }
            if (matched) return
            SystemClock.sleep(25)
        } while (SystemClock.uptimeMillis() < deadline)
        fail("Location popup did not reach the expected state")
    }
}
