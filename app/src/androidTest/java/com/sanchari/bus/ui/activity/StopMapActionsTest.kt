package com.sanchari.bus.ui.activity

import android.app.Activity
import android.app.Instrumentation
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.os.SystemClock
import android.view.ViewGroup
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sanchari.bus.R
import com.sanchari.bus.data.model.BusService
import com.sanchari.bus.data.model.BusStop
import com.sanchari.bus.data.model.GeoPoint
import com.sanchari.bus.data.model.RouteSearchResult
import com.sanchari.bus.data.model.StopLocation
import com.sanchari.bus.ui.helper.MapNavigationHelper
import com.sanchari.bus.ui.helper.StopMapActions
import com.sanchari.bus.ui.adapter.BusStopAdapter
import com.sanchari.bus.data.local.DatabaseManager
import com.sanchari.bus.data.manager.SearchManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.hamcrest.Matchers.anything
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StopMapActionsTest {
    private val first = StopLocation(1, "Same", GeoPoint(10.0, 76.0))
    private val second = StopLocation(2, "Same", GeoPoint(10.1, 76.1))
    private val destination = StopLocation(3, "Other", GeoPoint(11.0, 77.0))
    private val stops = listOf(first, second, destination)

    @Test
    fun detailsActivityStopRowOpensCoordinatePopupAndClosesItOnPause() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking { DatabaseManager.initializeDatabases(context) }
        val location = SearchManager.getStopLocations(context).first()
        val service = BusService("stop-actions-fixture", "Fixture", "ORDINARY", true, 0L, "08:00", "09:00")
        ActivityScenario.launch<BusDetailsActivity>(BusDetailsActivity.newIntent(context, service)).use { scenario ->
            val deadline = SystemClock.uptimeMillis() + 5_000
            var loaded = false
            do {
                scenario.onActivity { loaded = it.findViewById<View>(R.id.progressBar).visibility == View.GONE }
                if (!loaded) SystemClock.sleep(20)
            } while (!loaded && SystemClock.uptimeMillis() < deadline)
            assertTrue("Detail stops did not finish loading", loaded)
            scenario.onActivity { activity ->
                val recycler = activity.findViewById<RecyclerView>(R.id.recyclerViewBusStops)
                val adapter = recycler.adapter as BusStopAdapter
                adapter.updateStops(listOf(BusStop(location.stopId, service.serviceId, location.name,
                    location.point.latitude, location.point.longitude, "08:00", 1)))
                val row = adapter.onCreateViewHolder(recycler, 0)
                adapter.onBindViewHolder(row, 0)
                row.itemView.performClick()
            }
            onData(anything()).inRoot(isDialog()).atPosition(1).perform(click())
            onView(withId(android.R.id.message)).inRoot(isDialog()).check(matches(withText(
                context.getString(R.string.stop_coordinates_value,
                    location.point.latitude.toString(), location.point.longitude.toString())
            )))
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            onView(withId(android.R.id.message)).check(doesNotExist())
        }
    }

    @Test
    fun tappedRowRetainsPhysicalIdsIncludingHaltsAndRecycledRows() = fixture { scenario, _ ->
        scenario.onActivity { activity ->
            val raw = listOf(
                BusStop(1, "fixture", "Same", 10.0, 76.0, "08:00", 1),
                BusStop(2, "fixture", "Same", 10.1, 76.1, "08:05", 2),
                BusStop(3, "fixture", "Other", 11.0, 77.0, "09:00", 3)
            )
            var selected: BusStopAdapter.DisplayStop? = null
            val adapter = BusStopAdapter(raw) { selected = it }
            val row = adapter.onCreateViewHolder(activity.findViewById<ViewGroup>(android.R.id.content), 0)
            adapter.onBindViewHolder(row, 0)
            assertTrue(row.itemView.isFocusable)
            assertTrue(row.itemView.performClick())
            assertEquals(listOf(1, 2), selected?.stopIds)
            assertEquals("8:00 AM - 8:05 AM", selected?.timeText)
            adapter.onBindViewHolder(row, 1)
            row.itemView.performClick()
            assertEquals(listOf(3), selected?.stopIds)
            assertEquals("Other", selected?.locationName)
        }
    }

    @Test
    fun stopOptionsShowSelectableCoordinatesWithoutOpeningMaps() = fixture { scenario, intents ->
        scenario.onActivity { actions(it).showStopOptions("Same", listOf(2)) }
        onData(anything()).inRoot(isDialog()).atPosition(1).perform(click())
        onView(withId(android.R.id.message)).inRoot(isDialog())
            .check(matches(withText("Latitude, longitude\n10.1, 76.1")))
        scenario.onActivity { assertTrue(intents.isEmpty()) }
        onView(withId(android.R.id.message)).inRoot(isDialog()).check { view, error ->
            if (error != null) throw error
            assertTrue((view as TextView).isTextSelectable)
        }
        onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
    }

    @Test
    fun mergedDifferentPhysicalStopsRequireChoiceBeforeMapAction() = fixture { scenario, intents ->
        scenario.onActivity { actions(it).showStopOptions("Same", listOf(1, 2)) }
        onData(anything()).inRoot(isDialog()).atPosition(1).perform(click())
        onView(withText(R.string.stop_open_map)).inRoot(isDialog()).check(matches(isDisplayed()))
        onData(anything()).inRoot(isDialog()).atPosition(0).perform(click())
        scenario.onActivity {
            assertEquals(MapNavigationHelper.markerUrl(second.point), intents.single().data.toString())
        }
    }

    @Test
    fun missingOrChangedTappedStopNeverFallsBackToAnotherSameNameStop() = fixture { scenario, intents ->
        scenario.onActivity { actions(it).showStopOptions("Same", listOf(99)) }
        onView(withText(R.string.stop_open_map)).check(doesNotExist())
        lateinit var maps: StopMapActions
        var current = second
        scenario.onActivity {
            maps = StopMapActions(it, { listOf(first, second) }, { id -> current.takeIf { id == 2 } })
            maps.showStopOptions("Same", listOf(2))
            current = second.copy(point = GeoPoint(12.0, 78.0))
        }
        onData(anything()).inRoot(isDialog()).atPosition(0).perform(click())
        scenario.onActivity { assertTrue(intents.isEmpty()) }
    }

    @Test
    fun cancellingStopPopupNeverOpensCoordinatesOrMaps() = fixture { scenario, intents ->
        lateinit var maps: StopMapActions
        scenario.onActivity { maps = actions(it); maps.showStopOptions("Same", listOf(2)) }
        scenario.onActivity { maps.cancel() }
        onView(withText(R.string.stop_open_map)).check(doesNotExist())
        scenario.onActivity { assertTrue(intents.isEmpty()) }
    }

    @Test
    fun typedDuplicateStopCoordinatesRequirePhysicalChoice() = fixture { scenario, intents ->
        scenario.onActivity { actions(it).showStopCoordinates("Same", null) }
        onData(anything()).inRoot(isDialog()).atPosition(1).perform(click())
        onView(withId(android.R.id.message)).inRoot(isDialog())
            .check(matches(withText("Latitude, longitude\n10.1, 76.1")))
        scenario.onActivity { assertTrue(intents.isEmpty()) }
        onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
    }

    @Test
    fun preferredPhysicalIdOpensItsMarkerWithoutChoosingTheFirstDuplicate() = fixture { scenario, intents ->
        scenario.onActivity { activity ->
            actions(activity).showStop("Same", 2)
            assertEquals(1, intents.size)
            assertEquals(MapNavigationHelper.markerUrl(second.point), intents.single().data.toString())
            assertEquals("com.google.android.apps.maps", intents.single().`package`)
        }
    }

    @Test
    fun ambiguousTypedNameRequiresAChoiceAndCancellationDoesNotLaunchMaps() = fixture { scenario, intents ->
        lateinit var maps: StopMapActions
        scenario.onActivity { maps = actions(it); maps.showStop("Same", null) }
        onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click())
        scenario.onActivity {
            assertTrue(intents.isEmpty())
            maps.showStop("Same", null)
        }
        onData(anything()).inRoot(isDialog()).atPosition(1).perform(click())
        scenario.onActivity {
            assertEquals(MapNavigationHelper.markerUrl(second.point), intents.single().data.toString())
        }
    }

    @Test
    fun vanishedPreferredIdRequiresDisambiguationRatherThanASilentFallback() = fixture { scenario, intents ->
        scenario.onActivity { activity ->
            StopMapActions(activity, { listOf(first) }, { first }).showStop("Same", 99)
        }
        onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click())
        scenario.onActivity { assertTrue(intents.isEmpty()) }
    }

    @Test
    fun directionsExplicitlyUseBothStoredEndpointsAndDrivingMode() = fixture { scenario, intents ->
        scenario.onActivity { activity ->
            actions(activity).showDirections("Same", "Other", 2, 3)
            val uri = intents.single().data!!
            assertEquals("10.1,76.1", uri.getQueryParameter("origin"))
            assertEquals("11.0,77.0", uri.getQueryParameter("destination"))
            assertEquals("driving", uri.getQueryParameter("travelmode"))
        }
    }

    @Test
    fun missingOrChangedCoordinatesDoNotLaunchAnExternalActivity() = fixture { scenario, intents ->
        scenario.onActivity { activity ->
            StopMapActions(activity, { emptyList() }, { null }).showStop("Missing", null)
            StopMapActions(activity, { listOf(first) }, { first.copy(point = GeoPoint(12.0, 78.0)) })
                .showStop("Same", 1)
            StopMapActions(activity, { name -> stops.filter { it.name == name } },
                { id -> stops.find { it.stopId == id }?.takeIf { id != 3 } })
                .showDirections("Same", "Other", 1, 3)
            assertTrue(intents.isEmpty())
        }
    }

    @Test
    fun cancelledSlowLookupCannotLaunchMapsLater() = fixture { scenario, intents ->
        scenario.onActivity { activity ->
            val maps = StopMapActions(activity, {
                withContext(NonCancellable) { delay(300) }
                listOf(first)
            }, { first })
            maps.showStop("Same", 1)
            maps.cancel()
        }
        SystemClock.sleep(400)
        scenario.onActivity { assertTrue(intents.isEmpty()) }
    }

    @Test
    fun mapsMissingUsesTheSameCoordinateUrlInTheBrowserAndNoHandlerDoesNotCrash() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        for (failures in listOf(1, 2)) {
            val attempts = mutableListOf<Intent>()
            val context = object : ContextWrapper(base) {
                override fun startActivity(intent: Intent) {
                    attempts.add(Intent(intent))
                    if (attempts.size <= failures) throw ActivityNotFoundException("Test handler unavailable")
                }
            }
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                MapNavigationHelper.showDirections(context, first.point, destination.point)
            }
            assertEquals(2, attempts.size)
            assertEquals("com.google.android.apps.maps", attempts.first().`package`)
            assertNull(attempts.last().`package`)
            assertEquals(attempts.first().data, attempts.last().data)
        }
    }

    @Test
    fun navigationActionIsAvailableOnlyForFixedEndpointResults() {
        for ((from, to) in listOf("Same" to "Other", "*" to "Other", "Same" to "*")) {
            launch(from, to).use { scenario ->
                scenario.onActivity {
                    val item = it.findViewById<Toolbar>(R.id.toolbar).menu.findItem(R.id.action_map_directions)
                    assertEquals(from != "*" && to != "*", item.isVisible)
                }
            }
        }
    }

    private fun actions(activity: SearchResultsActivity) =
        StopMapActions(activity, { name -> stops.filter { it.name == name } },
            { id -> stops.find { it.stopId == id } })

    private fun launch(from: String = "Same", to: String = "Other"): ActivityScenario<SearchResultsActivity> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val result = RouteSearchResult(
            BusService("fixture", "Fixture", "ORDINARY", true, 0L, "08:00", "09:00"),
            "Same", "Other", 1, 2
        )
        val intent = Intent(context, SearchResultsActivity::class.java).apply {
            putParcelableArrayListExtra(SearchResultsActivity.EXTRA_ROUTE_RESULTS, arrayListOf(result))
            putExtra(SearchResultsActivity.EXTRA_FROM_STOP, from)
            putExtra(SearchResultsActivity.EXTRA_TO_STOP, to)
        }
        return ActivityScenario.launch(intent)
    }

    private fun fixture(test: (ActivityScenario<SearchResultsActivity>, MutableList<Intent>) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val intents = mutableListOf<Intent>()
        launch().use { scenario ->
            val monitor = object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                    if (intent.action != Intent.ACTION_VIEW) return null
                    intents.add(Intent(intent))
                    return Instrumentation.ActivityResult(Activity.RESULT_OK, null)
                }
            }
            instrumentation.addMonitor(monitor)
            try {
                test(scenario, intents)
            } finally {
                instrumentation.removeMonitor(monitor)
            }
        }
    }
}
