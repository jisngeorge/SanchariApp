package com.sanchari.bus.ui.activity

import android.content.Intent
import android.os.SystemClock
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.assertion.PositionAssertions.isCompletelyLeftOf
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
import com.sanchari.bus.ui.helper.BusSearchHandler
import com.sanchari.bus.ui.helper.TimetableTimeFormatter
import com.sanchari.bus.databinding.ActivityMainBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Calendar

@RunWith(AndroidJUnit4::class)
class WildcardSearchResultsTest {
    @Test
    fun outboundShowsPairedPlacesTimesAndAllEligibleServices() {
        val longDestination = "A very long destination name that must wrap across multiple lines"
        val service = BusService("same", "Loop service", "ORDINARY", false, 0L, "14:30", "16:00")
        val results = listOf(
            RouteSearchResult(service, "Known stop", longDestination, 1, 5),
            RouteSearchResult(service.copy(serviceId = "second-service"), "Known stop", longDestination, 3, 5)
        )
        launch("Known stop", "*", results).use { scenario ->
            awaitResults(scenario, listOf(1, 3))
            onView(withId(R.id.tvToolbarTitle)).check(matches(withText("Known stop → Any destination")))
            onView(withId(R.id.resultsSummary)).check(matches(withText("Services: 2")))
            onView(withId(R.id.action_suggest_nearby)).check(doesNotExist())
            scenario.onActivity { activity ->
                val recycler = activity.findViewById<RecyclerView>(R.id.searchResultsRecyclerView)
                val row = requireNotNull(recycler.findViewHolderForAdapterPosition(0)).itemView
                assertEquals("From: Known stop", row.findViewById<TextView>(R.id.fromPlace).text.toString())
                assertEquals("To: $longDestination", row.findViewById<TextView>(R.id.toPlace).text.toString())
                assertEquals("2:30 PM", row.findViewById<TextView>(R.id.fromTime).text.toString())
                assertEquals("4:00 PM", row.findViewById<TextView>(R.id.toTime).text.toString())
                assertTrue(row.findViewById<TextView>(R.id.toPlace).lineCount > 1)
                assertFalse(activity.findViewById<MaterialToolbar>(R.id.toolbar)
                    .menu.findItem(R.id.action_suggest_nearby).isVisible)
            }
            onView(withId(R.id.action_sort_duration)).perform(click())
            onView(withText(R.string.sort_shortest_journey)).check(doesNotExist())
            onView(withText(R.string.sort_longest_journey)).check(doesNotExist())
            onView(withText(R.string.sort_next_departure)).perform(click())
            scenario.recreate()
            awaitResults(scenario, listOf(1, 3))
            onView(withId(R.id.resultsSummary)).check(matches(withText("Services: 2")))
        }
    }

    @Test
    fun outboundKeepsThreeLegsOfOneServiceThroughSortingFilteringAndRecreation() {
        val now = Calendar.getInstance()
        val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val service = BusService("multi-leg", "Multi-leg service", "ORDINARY", true, 0L,
            time(minute - 20), time(minute + 5))
        val first = RouteSearchResult(service, "A", "C", 1, 3)
        val next = RouteSearchResult(
            service.copy(fromTime = time(minute + 10), toTime = time(minute + 40)),
            "A", "E", 7, 9
        )
        val third = RouteSearchResult(service, "A", "C", 13, 15)
        val results = listOf(first, next, third)
        launch("A", "*", results).use { scenario ->
            awaitResults(scenario, results.sortedBy { it.service.fromTime }.map { it.fromRouteStopId })
            onView(withId(R.id.resultsSummary)).check(matches(withText("Services: 3")))
            onView(withId(R.id.action_sort_duration)).perform(click())
            onView(withText(R.string.sort_next_departure)).perform(click())
            awaitResults(scenario, listOf(7, 1, 13))
            scenario.onActivity { activity ->
                val recycler = activity.findViewById<RecyclerView>(R.id.searchResultsRecyclerView)
                val adapter = recycler.adapter as BusServiceAdapter
                assertEquals(listOf(next, first, third), adapter.currentList)
                val row = requireNotNull(recycler.findViewHolderForAdapterPosition(0)).itemView
                assertEquals("From: A", row.findViewById<TextView>(R.id.fromPlace).text.toString())
                assertEquals("To: E", row.findViewById<TextView>(R.id.toPlace).text.toString())
                assertEquals(TimetableTimeFormatter.format(next.service.fromTime),
                    row.findViewById<TextView>(R.id.fromTime).text.toString())
                assertEquals(TimetableTimeFormatter.format(next.service.toTime),
                    row.findViewById<TextView>(R.id.toTime).text.toString())
            }
            scenario.recreate()
            awaitResults(scenario, listOf(7, 1, 13))
            onView(withId(R.id.action_filter_bus_type)).perform(click())
            onView(withText("ORDINARY")).perform(click())
            onView(withText(R.string.filter_apply)).perform(click())
            awaitResults(scenario, emptyList())
            onView(withId(R.id.resultsSummary)).check(matches(withText("Services: 0")))
            scenario.recreate()
            awaitResults(scenario, emptyList())
            onView(withId(R.id.action_filter_bus_type)).perform(click())
            onView(withText(R.string.filter_all_types)).perform(click())
            awaitResults(scenario, listOf(7, 1, 13))
            scenario.recreate()
            awaitResults(scenario, listOf(7, 1, 13))
            onView(withId(R.id.resultsSummary)).check(matches(withText("Services: 3")))
        }
    }

    @Test
    fun inboundOffersArrivalOrderingAndRetainsItAfterRecreationAndFiltering() {
        val now = Calendar.getInstance()
        val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val passed = result(1, "Origin X", "00:00", time(minute - 20), "ORDINARY")
        val next = result(2, "Origin Y", "23:00", time(minute + 10), "EXPRESS")
        val unknown = result(3, "Origin Z", "12:00", "--:--", "ORDINARY")
        launch("*", "Known stop", listOf(passed, next, unknown)).use { scenario ->
            onView(withId(R.id.tvToolbarTitle)).check(matches(withText("Any origin → Known stop")))
            onView(withId(R.id.action_sort_duration)).perform(click())
            onView(withText(R.string.sort_departure_time)).check(doesNotExist())
            onView(withText(R.string.sort_arrival_time)).check(matches(isDisplayed()))
            onView(withText(R.string.sort_next_arrival)).perform(click())
            awaitResults(scenario, listOf(2, 1, 3))
            scenario.recreate()
            awaitResults(scenario, listOf(2, 1, 3))
            onView(withId(R.id.action_filter_bus_type)).perform(click())
            onView(withText("ORDINARY")).perform(click())
            onView(withText(R.string.filter_apply)).perform(click())
            awaitResults(scenario, listOf(2))
            onView(withId(R.id.resultsSummary)).check(matches(withText("Services: 1")))
            scenario.recreate()
            awaitResults(scenario, listOf(2))
        }
    }

    @Test
    fun inboundKeepsRepeatedArrivalsOfOneServiceWithTheirOwnOriginsAndTimes() {
        val now = Calendar.getInstance()
        val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val service = BusService("inbound-legs", "Returning service", "ORDINARY", true, 0L,
            time(minute - 40), time(minute - 20))
        val first = RouteSearchResult(service, "C", "A", 4, 6)
        val second = RouteSearchResult(
            service.copy(fromTime = time(minute - 5), toTime = time(minute + 10)),
            "E", "A", 10, 12
        )
        val third = RouteSearchResult(service, "C", "A", 16, 18)
        val results = listOf(first, second, third)
        launch("*", "A", results).use { scenario ->
            awaitResults(scenario, results.sortedBy { it.service.toTime }.map { it.fromRouteStopId })
            onView(withId(R.id.resultsSummary)).check(matches(withText("Services: 3")))
            onView(withId(R.id.action_sort_duration)).perform(click())
            onView(withText(R.string.sort_next_arrival)).perform(click())
            awaitResults(scenario, listOf(10, 4, 16))
            scenario.onActivity { activity ->
                val recycler = activity.findViewById<RecyclerView>(R.id.searchResultsRecyclerView)
                val adapter = recycler.adapter as BusServiceAdapter
                assertEquals(listOf(second, first, third), adapter.currentList)
                val row = requireNotNull(recycler.findViewHolderForAdapterPosition(0)).itemView
                assertEquals("From: E", row.findViewById<TextView>(R.id.fromPlace).text.toString())
                assertEquals("To: A", row.findViewById<TextView>(R.id.toPlace).text.toString())
                assertEquals(TimetableTimeFormatter.format(second.service.fromTime),
                    row.findViewById<TextView>(R.id.fromTime).text.toString())
                assertEquals(TimetableTimeFormatter.format(second.service.toTime),
                    row.findViewById<TextView>(R.id.toTime).text.toString())
            }
            scenario.recreate()
            awaitResults(scenario, listOf(10, 4, 16))
            onView(withId(R.id.action_filter_bus_type)).perform(click())
            onView(withText("ORDINARY")).perform(click())
            onView(withText(R.string.filter_apply)).perform(click())
            awaitResults(scenario, emptyList())
            scenario.recreate()
            awaitResults(scenario, emptyList())
            onView(withId(R.id.action_filter_bus_type)).perform(click())
            onView(withText(R.string.filter_all_types)).perform(click())
            awaitResults(scenario, listOf(10, 4, 16))
            onView(withId(R.id.resultsSummary)).check(matches(withText("Services: 3")))
        }
    }

    @Test
    fun wildcardModesNeverShowSlowJourneyColourButKeepNotRunningRed() {
        val services = listOf(
            BusService("fast", "Fast", "ORDINARY", true, 0L, "08:00", "08:30"),
            BusService("another-fast", "Another fast", "ORDINARY", true, 0L, "08:00", "08:30"),
            BusService("slow", "Slow", "ORDINARY", true, 0L, "08:00", "10:00"),
            BusService("stopped", "Stopped", "ORDINARY", false, 0L, "08:00", "10:00")
        )
        for ((from, to) in listOf("A" to "*", "*" to "A")) {
            val results = services.mapIndexed { index, service ->
                RouteSearchResult(service, if (from == "*") "Origin" else "A",
                    if (to == "*") "Destination" else "A", index * 2, index * 2 + 1)
            }
            launch(from, to, results).use { scenario ->
                awaitResults(scenario, listOf(0, 2, 4, 6))
                scenario.onActivity { activity ->
                    val recycler = activity.findViewById<RecyclerView>(R.id.searchResultsRecyclerView)
                    fun nameAt(position: Int): TextView =
                        requireNotNull(recycler.findViewHolderForAdapterPosition(position)).itemView
                            .findViewById(R.id.serviceName)
                    val normal = nameAt(0).currentTextColor
                    assertEquals(normal, nameAt(2).currentTextColor)
                    assertNull(nameAt(2).contentDescription)
                    assertEquals(
                        androidx.core.content.ContextCompat.getColor(activity, R.color.service_not_running),
                        nameAt(3).currentTextColor
                    )
                }
            }
        }
    }

    @Test
    fun mainStopWildcardButtonsReplaceOnlyTheirFieldAndKeepClearWorking() {
        launch("Known stop", "*", listOf(result(1, "Origin", "08:00", "09:00", "ORDINARY"))).use { scenario ->
            scenario.onActivity { activity ->
                val binding = ActivityMainBinding.inflate(activity.layoutInflater)
                activity.setContentView(binding.root)
                BusSearchHandler(activity, binding).setup()
                assertFalse(binding.fromClearButton.isEnabled)
                assertFalse(binding.toClearButton.isEnabled)
                binding.toAutocomplete.setText("Destination", false)
            }
            onView(withId(R.id.fromWildcardButton)).check(matches(isDisplayed()))
                .check(isCompletelyLeftOf(withId(R.id.fromClearButton)))
                .perform(click())
            onView(withId(R.id.fromAutocomplete)).check(matches(withText("*")))
            onView(withId(R.id.toAutocomplete)).check(matches(withText("Destination")))
            onView(withId(R.id.fromClearButton)).perform(click())
            onView(withId(R.id.fromAutocomplete)).check(matches(withText("")))
            scenario.onActivity { activity ->
                val field = activity.findViewById<com.google.android.material.textfield.MaterialAutoCompleteTextView>(
                    R.id.fromAutocomplete
                )
                field.setText("Original origin", false)
            }
            onView(withId(R.id.toWildcardButton)).check(matches(isDisplayed()))
                .check(isCompletelyLeftOf(withId(R.id.toClearButton)))
                .perform(click())
            onView(withId(R.id.fromAutocomplete)).check(matches(withText("Original origin")))
            onView(withId(R.id.toAutocomplete)).check(matches(withText("*")))
            scenario.onActivity { activity ->
                for ((fieldId, buttonId) in listOf(
                    R.id.fromAutocomplete to R.id.fromWildcardButton,
                    R.id.toAutocomplete to R.id.toWildcardButton
                )) {
                    val field = activity.findViewById<com.google.android.material.textfield.MaterialAutoCompleteTextView>(fieldId)
                    val button = activity.findViewById<android.widget.ImageButton>(buttonId)
                    assertFalse(field.isPopupShowing)
                    assertTrue(button.width >= (48 * activity.resources.displayMetrics.density).toInt())
                    val fieldPosition = IntArray(2)
                    val buttonPosition = IntArray(2)
                    field.getLocationOnScreen(fieldPosition)
                    button.getLocationOnScreen(buttonPosition)
                    assertTrue(fieldPosition[0] + field.width - field.compoundPaddingRight <= buttonPosition[0])
                }
                assertFalse(activity.isFinishing)
            }
            onView(withId(R.id.toClearButton)).perform(click())
            onView(withId(R.id.toAutocomplete)).check(matches(withText("")))
            onView(withId(R.id.fromAutocomplete)).check(matches(withText("Original origin")))
            onView(withText("Enter * in one field for any origin or destination.")).check(doesNotExist())
        }
    }

    @Test
    fun inputRejectsTwoWildcardsAndKeepsBlankFieldValidation() {
        launch("Known stop", "*", listOf(result(1, "Origin", "08:00", "09:00", "ORDINARY"))).use { scenario ->
            scenario.onActivity { activity ->
                val binding = ActivityMainBinding.inflate(activity.layoutInflater)
                BusSearchHandler(activity, binding).setup()
                binding.fromAutocomplete.setText("*", false)
                binding.toAutocomplete.setText("*", false)
                binding.searchButton.performClick()
                assertFalse(activity.isFinishing)
                binding.fromAutocomplete.setText("", false)
                binding.searchButton.performClick()
                assertFalse(activity.isFinishing)
            }
        }
    }

    private fun launch(from: String, to: String, results: List<RouteSearchResult>): ActivityScenario<SearchResultsActivity> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, SearchResultsActivity::class.java).apply {
            putParcelableArrayListExtra(SearchResultsActivity.EXTRA_ROUTE_RESULTS, ArrayList(results))
            putExtra(SearchResultsActivity.EXTRA_FROM_STOP, from)
            putExtra(SearchResultsActivity.EXTRA_TO_STOP, to)
        }
        return ActivityScenario.launch(intent)
    }

    private fun awaitResults(scenario: ActivityScenario<SearchResultsActivity>, expected: List<Int>) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        var actual = emptyList<Int>()
        do {
            scenario.onActivity {
                val adapter = it.findViewById<RecyclerView>(R.id.searchResultsRecyclerView).adapter as BusServiceAdapter
                actual = adapter.currentList.map { result -> result.fromRouteStopId }
            }
            if (actual == expected) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        assertEquals(expected, actual)
    }

    private fun result(id: Int, origin: String, from: String, to: String, type: String) = RouteSearchResult(
        BusService("service-$id", "Service $id", type, true, 0L, from, to), origin, "Known stop", id, 100 + id
    )

    private fun time(minutes: Int): String {
        val wrapped = (minutes + 1440) % 1440
        return "${(wrapped / 60).toString().padStart(2, '0')}:${(wrapped % 60).toString().padStart(2, '0')}"
    }
}
