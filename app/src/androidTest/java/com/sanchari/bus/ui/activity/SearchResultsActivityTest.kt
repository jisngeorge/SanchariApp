package com.sanchari.bus.ui.activity

import android.content.Intent
import android.content.res.Configuration
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.appbar.MaterialToolbar
import com.sanchari.bus.R
import com.sanchari.bus.data.model.BusService
import com.sanchari.bus.data.model.RouteSearchResult
import com.sanchari.bus.ui.adapter.BusServiceAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Calendar

@RunWith(AndroidJUnit4::class)
class SearchResultsActivityTest {
    @Test
    fun slowWarningUsesReadableYellowOrangeDistinctFromRedInBothThemes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (night in listOf(false, true)) {
            val configuration = Configuration(context.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            val themed = context.createConfigurationContext(configuration)
            val amber = ContextCompat.getColor(themed, R.color.service_slow)
            val red = ContextCompat.getColor(themed, R.color.service_not_running)
            val amberHsl = FloatArray(3)
            val redHsl = FloatArray(3)
            ColorUtils.colorToHSL(amber, amberHsl)
            ColorUtils.colorToHSL(red, redHsl)
            assertTrue(amberHsl[0] in 35f..55f)
            assertTrue(amberHsl[0] - redHsl[0] >= 30f)
            assertTrue(ColorUtils.calculateContrast(
                amber, if (night) 0xFF121212.toInt() else 0xFFFAFAFA.toInt()
            ) >= 4.5)
        }
    }

    @Test
    fun ordinaryRouteUsesCompactCardsWithoutRepeatingEndpointNames() {
        launch(listOf(service("Compact service", "08:00", "09:30"))).use { scenario ->
            awaitNames(scenario, listOf("Compact service"))
            fun assertCompactRow() {
                scenario.onActivity { activity ->
                    val recycler = activity.findViewById<RecyclerView>(R.id.searchResultsRecyclerView)
                    val row = requireNotNull(recycler.findViewHolderForAdapterPosition(0)).itemView
                    assertEquals(LinearLayout.HORIZONTAL, (row as LinearLayout).orientation)
                    assertEquals(View.GONE, row.findViewById<View>(R.id.fromPlace).visibility)
                    assertEquals(View.GONE, row.findViewById<View>(R.id.toPlace).visibility)
                    assertEquals("Compact service", row.findViewById<TextView>(R.id.serviceName).text.toString())
                    assertEquals("8:00 AM", row.findViewById<TextView>(R.id.fromTime).text.toString())
                    assertEquals("9:30 AM", row.findViewById<TextView>(R.id.toTime).text.toString())
                    val header = row.findViewById<View>(R.id.serviceHeader)
                    val times = row.findViewById<View>(R.id.endpointTimes)
                    assertTrue(header.right <= times.left)
                    assertEquals(0, (times.layoutParams as LinearLayout.LayoutParams).topMargin)
                }
            }
            assertCompactRow()
            scenario.recreate()
            awaitNames(scenario, listOf("Compact service"))
            assertCompactRow()
        }
    }

    @Test
    fun summaryShowsCountAndAverageWithAlternativeRouteBulbVisible() {
        launch(results()).use { scenario ->
            onView(withId(R.id.action_sort_duration)).check(matches(isDisplayed()))
            onView(withId(R.id.action_filter_bus_type)).check(matches(isDisplayed()))
            onView(withId(R.id.action_suggest_nearby)).check(matches(isDisplayed()))
            scenario.onActivity { activity ->
                assertEquals(3, activity.findViewById<MaterialToolbar>(R.id.toolbar).menu.size())
                assertTrue(activity.findViewById<View>(R.id.searchResultsRecyclerView).height > 0)
            }
            assertSummary(scenario, 4, "1h 00m")
            scenario.recreate()
            assertSummary(scenario, 4, "1h 00m")
            onView(withId(R.id.action_suggest_nearby)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun durationSortingAndBusTypeFilteringComposeAndSurviveRecreation() {
        launch(results()).use { scenario ->
            onView(withId(R.id.action_sort_duration)).perform(click())
            onView(withText(R.string.sort_shortest_journey)).perform(click())
            awaitNames(scenario, listOf("Fast B", "Fast C", "Express D", "Slow A"))

            onView(withId(R.id.action_filter_bus_type)).perform(click())
            onView(withText("FAST PASSENGER")).perform(click())
            onView(withText("EXPRESS")).perform(click())
            onView(withText(R.string.filter_apply)).perform(click())
            awaitNames(scenario, listOf("Fast B", "Slow A"))

            assertSummary(scenario, 2, "1h 15m")
            scenario.recreate()
            awaitNames(scenario, listOf("Fast B", "Slow A"))
            assertSummary(scenario, 2, "1h 15m")

            onView(withId(R.id.action_filter_bus_type)).perform(click())
            onView(withText(R.string.filter_all_types)).perform(click())
            onView(withId(R.id.action_sort_duration)).perform(click())
            onView(withText(R.string.sort_longest_journey)).perform(click())
            awaitNames(scenario, listOf("Slow A", "Express D", "Fast B", "Fast C"))

            onView(withId(R.id.action_sort_duration)).perform(click())
            onView(withText(R.string.sort_departure_time)).perform(click())
            awaitNames(scenario, listOf("Slow A", "Fast B", "Fast C", "Express D"))
        }
    }

    @Test
    fun emptyTypeSelectionShowsRecoverableEmptyState() {
        launch(results()).use { scenario ->
            onView(withId(R.id.action_filter_bus_type)).perform(click())
            onView(withText("ORDINARY")).perform(click())
            onView(withText("FAST PASSENGER")).perform(click())
            onView(withText("EXPRESS")).perform(click())
            onView(withText(R.string.filter_apply)).perform(click())
            awaitNames(scenario, emptyList())
            onView(withId(R.id.emptyResults)).check(matches(isDisplayed()))
            assertSummary(scenario, 0, "--")
            scenario.recreate()
            onView(withId(R.id.emptyResults)).check(matches(isDisplayed()))
            onView(withId(R.id.action_filter_bus_type)).perform(click())
            onView(withText(R.string.filter_all_types)).perform(click())
            awaitNames(scenario, listOf("Slow A", "Fast B", "Fast C", "Express D"))
        }
    }

    @Test
    fun sortDialogOffersNextDeparturesAndRestoresOriginalOrder() {
        val now = Calendar.getInstance()
        val minutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val passed = service("Passed", time(minutes - 20), time(minutes + 40))
        val later = service("Later", time(minutes + 20), time(minutes + 80))
        val next = service("Next", time(minutes + 5), time(minutes + 65))
        val unknown = service("Unknown", "--:--", "--:--")
        launch(listOf(passed, later, next, unknown)).use { scenario ->
            onView(withId(R.id.action_sort_duration)).perform(click())
            onView(withText(R.string.sort_next_departure)).perform(click())
            awaitNames(scenario, listOf("Next", "Later", "Passed", "Unknown"))
            scenario.recreate()
            awaitNames(scenario, listOf("Next", "Later", "Passed", "Unknown"))
            onView(withId(R.id.action_sort_duration)).perform(click())
            onView(withText(R.string.sort_departure_time)).perform(click())
            awaitNames(scenario, listOf("Passed", "Later", "Next", "Unknown"))
        }
    }

    @Test
    fun slowNamesUseFilteredAverageWithRedPriorityAndRefreshAfterFiltering() {
        val services = listOf(
            service("Fast A", "08:00", "08:30", "EXPRESS"),
            service("Fast B", "08:00", "08:30", "EXPRESS"),
            service("Slow", "08:00", "10:00"),
            service("Stopped", "08:00", "10:00").copy(isRunning = false),
            service("Unknown", "--:--", "--:--")
        )
        launch(services).use { scenario ->
            awaitNames(scenario, services.map { it.name })
            var orange = 0
            var red = 0
            var normal = 0
            val warningUpdates = mutableListOf<String>()
            var fullUpdates = 0
            lateinit var observer: RecyclerView.AdapterDataObserver
            scenario.onActivity { activity ->
                orange = ContextCompat.getColor(activity, R.color.service_slow)
                red = ContextCompat.getColor(activity, R.color.service_not_running)
                val recycler = activity.findViewById<RecyclerView>(R.id.searchResultsRecyclerView)
                normal = requireNotNull(recycler.findViewHolderForAdapterPosition(0)).itemView
                    .findViewById<TextView>(R.id.serviceName).currentTextColor
                assertTrue(normal != orange && normal != red)
            }
            awaitNameColour(scenario, "Slow", orange)
            awaitNameColour(scenario, "Stopped", red)
            awaitNameColour(scenario, "Unknown", normal)
            assertSummary(scenario, 5, "1h 15m")
            scenario.onActivity { activity ->
                val recycler = activity.findViewById<RecyclerView>(R.id.searchResultsRecyclerView)
                val row = requireNotNull(recycler.findViewHolderForAdapterPosition(2)).itemView
                assertTrue(row.findViewById<TextView>(R.id.serviceName).contentDescription
                    .contains(activity.getString(R.string.service_slow_warning)))
                val adapter = recycler.adapter as BusServiceAdapter
                observer = object : RecyclerView.AdapterDataObserver() {
                    override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
                        if (payload == null) {
                            fullUpdates += itemCount
                        } else {
                            warningUpdates.addAll(
                                adapter.currentList.subList(positionStart, positionStart + itemCount)
                                    .map { it.service.name }
                            )
                        }
                    }
                }
                adapter.registerAdapterDataObserver(observer)
            }
            onView(withId(R.id.action_filter_bus_type)).perform(click())
            onView(withText("EXPRESS")).perform(click())
            onView(withText(R.string.filter_apply)).perform(click())
            awaitNames(scenario, listOf("Slow", "Stopped", "Unknown"))
            awaitNameColour(scenario, "Slow", normal)
            awaitNameColour(scenario, "Stopped", red)
            assertSummary(scenario, 3, "2h 00m")
            scenario.onActivity { activity ->
                assertEquals(listOf("Slow"), warningUpdates)
                assertEquals(0, fullUpdates)
                activity.findViewById<RecyclerView>(R.id.searchResultsRecyclerView).adapter
                    ?.unregisterAdapterDataObserver(observer)
            }
            scenario.recreate()
            awaitNames(scenario, listOf("Slow", "Stopped", "Unknown"))
            awaitNameColour(scenario, "Slow", normal)
            awaitNameColour(scenario, "Stopped", red)
            onView(withId(R.id.action_filter_bus_type)).perform(click())
            onView(withText(R.string.filter_all_types)).perform(click())
            awaitNames(scenario, services.map { it.name })
            awaitNameColour(scenario, "Slow", orange)
            onView(withId(R.id.action_sort_duration)).perform(click())
            onView(withText(R.string.sort_shortest_journey)).perform(click())
            awaitNames(scenario, services.map { it.name })
            awaitNameColour(scenario, "Slow", orange)
            awaitNameColour(scenario, "Stopped", red)
        }
    }

    @Test
    fun summaryKeepsCompactTextAndExplainsExcludedTimesAccessibly() {
        launch(listOf(
            service("Timed", "08:00", "08:30"),
            service("Unknown", "--:--", "--:--")
        )).use { scenario ->
            assertSummary(scenario, 2, "0h 30m")
            scenario.onActivity {
                val details = it.findViewById<TextView>(R.id.resultsSummary).contentDescription.toString()
                assertTrue(details.contains(it.resources.getQuantityString(
                    R.plurals.statistics_coverage, 2, 1, 2
                )))
                assertTrue(details.contains(it.getString(R.string.statistics_exclusions)))
            }
        }
        launch(listOf(service("Unknown", "--:--", "--:--"))).use { scenario ->
            assertSummary(scenario, 1, "--")
        }
    }

    private fun assertSummary(
        scenario: ActivityScenario<SearchResultsActivity>,
        shown: Int,
        duration: String
    ) {
        scenario.onActivity {
            val summary = it.findViewById<TextView>(R.id.resultsSummary).text.toString()
            assertEquals("Services: $shown   Average time: $duration", summary)
        }
    }

    private fun launch(services: List<BusService>): ActivityScenario<SearchResultsActivity> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, SearchResultsActivity::class.java).apply {
            putParcelableArrayListExtra(
                SearchResultsActivity.EXTRA_ROUTE_RESULTS,
                ArrayList(services.mapIndexed { index, service ->
                    RouteSearchResult(service, "Test A", "Test B", index * 2, index * 2 + 1)
                })
            )
            putExtra(SearchResultsActivity.EXTRA_FROM_STOP, "Test A")
            putExtra(SearchResultsActivity.EXTRA_TO_STOP, "Test B")
        }
        return ActivityScenario.launch(intent)
    }

    private fun awaitNames(scenario: ActivityScenario<SearchResultsActivity>, expected: List<String>) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        var actual = emptyList<String>()
        do {
            scenario.onActivity {
                val adapter = it.findViewById<RecyclerView>(R.id.searchResultsRecyclerView).adapter
                    as BusServiceAdapter
                actual = adapter.currentList.map { result -> result.service.name }
            }
            if (actual == expected) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        assertEquals(expected, actual)
    }

    private fun time(minutes: Int): String {
        val clockMinutes = (minutes + 1440) % 1440
        return "${(clockMinutes / 60).toString().padStart(2, '0')}:${(clockMinutes % 60).toString().padStart(2, '0')}"
    }

    private fun awaitNameColour(
        scenario: ActivityScenario<SearchResultsActivity>,
        name: String,
        expected: Int
    ) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        var actual: Int? = null
        do {
            scenario.onActivity { activity ->
                val recycler = activity.findViewById<RecyclerView>(R.id.searchResultsRecyclerView)
                val adapter = recycler.adapter as BusServiceAdapter
                val position = adapter.currentList.indexOfFirst { it.service.name == name }
                if (position >= 0) {
                    actual = recycler.findViewHolderForAdapterPosition(position)?.itemView
                        ?.findViewById<TextView>(R.id.serviceName)?.currentTextColor
                }
            }
            if (actual == expected) return
            SystemClock.sleep(25)
        } while (SystemClock.uptimeMillis() < deadline)
        assertEquals(expected, actual)
    }

    private fun results() = listOf(
        service("Slow A", "08:00", "10:00"),
        service("Fast B", "09:00", "09:30"),
        service("Fast C", "10:00", "10:30", "FAST PASSENGER"),
        service("Express D", "11:00", "12:00", "EXPRESS")
    )

    private fun service(name: String, from: String, to: String, type: String = "ORDINARY") =
        BusService(name, name, type, true, 0L, from, to)
}
