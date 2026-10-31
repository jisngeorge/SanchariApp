package com.sanchari.bus.ui.activity

import android.app.Activity
import android.app.Instrumentation
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.os.SystemClock
import androidx.appcompat.widget.Toolbar
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sanchari.bus.R
import com.sanchari.bus.data.model.BusService
import com.sanchari.bus.data.model.GeoPoint
import com.sanchari.bus.data.model.RouteSearchResult
import com.sanchari.bus.data.model.StopLocation
import com.sanchari.bus.ui.helper.MapNavigationHelper
import com.sanchari.bus.ui.helper.StopMapActions
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
