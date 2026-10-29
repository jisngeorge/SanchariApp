package com.sanchari.bus.ui.activity

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.widget.AutoCompleteTextView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sanchari.bus.R
import com.sanchari.bus.data.local.DatabaseManager
import com.sanchari.bus.data.manager.SearchManager
import com.sanchari.bus.data.model.EditableStop
import com.sanchari.bus.data.model.EditorStopOption
import com.sanchari.bus.data.model.RouteSearchStatistics
import com.sanchari.bus.ui.adapter.StopEditAdapter
import com.sanchari.bus.ui.helper.DraftManager
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScheduleEditorActivityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Before
    fun initializeLocalData() = runBlocking { DatabaseManager.initializeDatabases(context) }

    @Test
    fun freshNewAndEditSessionsShowQuickTipsButRecreationDoesNotRepeatThem() {
        val service = SearchManager.searchBusServicesByName(context, "").first()
        for (intent in listOf(SuggestEditActivity.newIntentForNew(context),
            SuggestEditActivity.newIntentForEdit(context, service))) {
            ActivityScenario.launch<SuggestEditActivity>(intent).use { scenario ->
                onView(withText(R.string.editor_quick_tips)).check(matches(isDisplayed()))
                onView(withText(R.string.editor_instructions)).check(matches(isDisplayed()))
                onView(withText(R.string.editor_got_it)).perform(click())
                scenario.recreate()
                onView(withText(R.string.editor_quick_tips)).check(doesNotExist())
                onView(withText(R.string.editor_instructions)).check(doesNotExist())
            }
        }
    }

    @Test
    fun autocompletePreservesSelectedIdAndFreeEntryResolvesOrClearsIdentity() {
        launch().use { scenario ->
            scenario.onActivity { activity ->
                adapter(activity).setStopOptions(listOf(EditorStopOption(41, "Known"), EditorStopOption(42, "Other")))
            }
            instrumentation.waitForIdleSync()
            onView(withId(R.id.editTextStopName)).perform(click(), replaceText("K"))
            onView(withText("Known")).inRoot(isPlatformPopup()).perform(click())
            scenario.onActivity { activity ->
                assertEquals(41, adapter(activity).getStopsData().first().originalStopId)
                activity.findViewById<AutoCompleteTextView>(R.id.editTextStopName).setText("New place", false)
                assertTrue(adapter(activity).commitNames())
                assertEquals(-1, adapter(activity).getStopsData().first().originalStopId)
                activity.findViewById<AutoCompleteTextView>(R.id.editTextStopName).setText(" other ", false)
                assertTrue(adapter(activity).commitNames())
                assertEquals("Other", adapter(activity).getStopsData().first().stopName)
                assertEquals(42, adapter(activity).getStopsData().first().originalStopId)
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals("Other", adapter(activity).getStopsData().first().stopName)
                assertEquals(42, adapter(activity).getStopsData().first().originalStopId)
            }
        }
    }

    @Test
    fun duplicateNamesRequireSelectionAndUnavailableLookupDoesNotStripUnchangedIds() {
        launch().use { scenario ->
            scenario.onActivity { activity ->
                adapter(activity).setStopOptions(listOf(
                    EditorStopOption(71, "Duplicate", "Duplicate (#71)"),
                    EditorStopOption(72, "Duplicate", "Duplicate (#72)")
                ))
            }
            instrumentation.waitForIdleSync()
            onView(withId(R.id.editTextStopName)).perform(click(), replaceText("Duplicate"))
            scenario.onActivity { activity ->
                assertFalse(adapter(activity).commitNames())
                assertEquals(-1, adapter(activity).getStopsData().first().originalStopId)
                activity.findViewById<AutoCompleteTextView>(R.id.editTextStopName).showDropDown()
            }
            onView(withText("Duplicate (#72)")).inRoot(isPlatformPopup()).perform(click())
            scenario.onActivity { activity ->
                assertEquals(72, adapter(activity).getStopsData().first().originalStopId)
                adapter(activity).setStopOptions(null, failed = true)
                assertTrue(adapter(activity).commitNames())
                assertEquals(72, adapter(activity).getStopsData().first().originalStopId)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                activity.findViewById<AutoCompleteTextView>(R.id.editTextStopName).setText("New place", false)
                assertTrue(adapter(activity).commitNames())
                assertEquals(-1, adapter(activity).getStopsData().first().originalStopId)
            }
        }
    }

    @Test
    fun reverseDialogCancelsThenAppendsIndependentRowsAndKeepsManualOrder() {
        launch().use { scenario ->
            scenario.onActivity { activity ->
                populate(activity, "A" to "08:00", "B" to "08:20", "C" to "08:40", "D" to "09:00")
                activity.findViewById<android.view.View>(R.id.buttonReverseStops).performClick()
            }
            onView(withText(android.R.string.cancel)).perform(click())
            scenario.onActivity { activity ->
                assertEquals(4, adapter(activity).itemCount)
                activity.findViewById<android.view.View>(R.id.buttonReverseStops).performClick()
            }
            onView(withText(R.string.editor_append)).perform(click())
            scenario.onActivity { activity ->
                val adapter = adapter(activity)
                val stops = adapter.getStopsData()
                assertEquals(listOf("A", "B", "C", "D", "D", "C", "B", "A"), stops.map { it.stopName })
                assertTrue(stops.drop(4).all { it.scheduledTime.isEmpty() })
                assertEquals(listOf(4, 3, 2, -1), stops.drop(4).map { it.originalStopId })
                adapter.updateTime(7, "07:00")
                assertEquals("A", stops.last().stopName)
                assertEquals("07:00", stops.last().scheduledTime)
                assertEquals("08:00", stops.first().scheduledTime)
            }
        }
    }

    @Test
    fun currentRouteEstimateRequiresAcceptanceAndInvalidatesWhenTypeChanges() {
        launch().use { scenario ->
            scenario.onActivity { activity ->
                populate(activity, "A" to "08:00", "B" to "08:30", "A" to "10:00", "B" to "")
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity -> estimate(activity, 3) }
            onView(withText(android.R.string.cancel)).perform(click())
            scenario.onActivity { activity ->
                assertEquals("", adapter(activity).getStopsData()[3].scheduledTime)
                estimate(activity, 3)
                activity.findViewById<AutoCompleteTextView>(R.id.editTextServiceType).setText("NEW TYPE", false)
            }
            onView(withText(R.string.editor_use_time)).check(doesNotExist())
            scenario.onActivity { activity -> estimate(activity, 3) }
            waitForDialog()
            onView(withText(R.string.editor_use_time)).perform(click())
            scenario.onActivity { activity ->
                assertEquals("10:30", adapter(activity).getStopsData()[3].scheduledTime)
                assertEquals(listOf("A", "B", "A", "B"), adapter(activity).getStopsData().map { it.stopName })
                adapter(activity).updateTime(3, "11:00")
                estimate(activity, 3)
            }
            waitForDialog(R.string.editor_replace_time)
            onView(withText(R.string.editor_replace_time)).perform(click())
            onView(withText(R.string.editor_replace_time)).check(doesNotExist())
            scenario.onActivity { activity ->
                assertEquals("10:30", adapter(activity).getStopsData()[3].scheduledTime)
            }
        }
    }

    @Test
    fun timetableEstimateShowsSameTypeThenAllTypeSourcesWithoutChangingTypeInput() {
        val service = SearchManager.searchBusServicesByName(context, "").first { service ->
            service.type.isNotBlank() && SearchManager.getBusStops(context, service.serviceId).zipWithNext().any {
                (from, to) -> from.locationName != to.locationName &&
                    RouteSearchStatistics.journeyMinutes(from.scheduledTime, to.scheduledTime) != null
            }
        }
        val (from, to) = SearchManager.getBusStops(context, service.serviceId).zipWithNext().first {
            (from, to) -> from.locationName != to.locationName &&
                RouteSearchStatistics.journeyMinutes(from.scheduledTime, to.scheduledTime) != null
        }
        launch().use { scenario ->
            scenario.onActivity { activity ->
                populate(activity, from.locationName to from.scheduledTime, to.locationName to "")
                activity.findViewById<AutoCompleteTextView>(R.id.editTextServiceType).setText(service.type, false)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity -> estimate(activity, 1) }
            waitForDialog()
            onView(withText(org.hamcrest.CoreMatchers.containsString(
                context.getString(R.string.editor_source_type, service.type)
            ))).check(androidx.test.espresso.assertion.ViewAssertions.matches(
                androidx.test.espresso.matcher.ViewMatchers.isDisplayed()
            ))
            onView(withText(android.R.string.cancel)).perform(click())
            scenario.onActivity { activity ->
                activity.findViewById<AutoCompleteTextView>(R.id.editTextServiceType).setText("TEST NEW BUS TYPE", false)
                estimate(activity, 1)
            }
            waitForDialog()
            onView(withText(org.hamcrest.CoreMatchers.containsString(
                context.getString(R.string.editor_source_all)
            ))).check(androidx.test.espresso.assertion.ViewAssertions.matches(
                androidx.test.espresso.matcher.ViewMatchers.isDisplayed()
            ))
            onView(withText(R.string.editor_use_time)).perform(click())
            scenario.onActivity { activity ->
                assertEquals("TEST NEW BUS TYPE", activity.findViewById<TextView>(R.id.editTextServiceType).text.toString())
                assertNotEquals("", adapter(activity).getStopsData()[1].scheduledTime)
            }
        }
    }

    @Test
    fun recreationKeepsEditIdentityAndDraftOwnershipInConfirmationPayload() {
        val draftPayload = JSONObject().apply {
            put("type", "edit_schedule")
            put("service", JSONObject().apply {
                put("serviceId", "editor-test-existing")
                put("name", "Editor test")
                put("type", "NEW TEST TYPE")
                put("isRunning", false)
                put("lastReportedTime", 123L)
            })
            put("stops", JSONArray().apply {
                put(JSONObject().put("stopId", 301).put("locationName", "Test origin")
                    .put("scheduledTime", "23:30").put("stopOrder", 1))
                put(JSONObject().put("stopId", "NEW").put("locationName", "Test destination")
                    .put("scheduledTime", "00:15").put("stopOrder", 2))
            })
            put("editNotes", "Test notes")
            put("timestamp", 1)
        }.toString()
        val filename = DraftManager.saveDraft(context, draftPayload)
        var captured: Intent? = null
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.component?.className == ConfirmationActivity::class.java.name) {
                    captured = intent
                    return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                }

                return null
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            launch(SuggestEditActivity.newIntentForNew(context).putExtra(
                SuggestEditActivity.EXTRA_LOAD_DRAFT_FILE, filename
            )).use { scenario ->
                scenario.onActivity { activity ->
                    activity.findViewById<android.view.View>(R.id.buttonReverseStops).performClick()
                }
                onView(withText(R.string.editor_append)).perform(click())
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertEquals("editor-test-existing", activity.getOriginalService()?.serviceId)
                    assertEquals(filename, activity.currentDraftFileName)
                    assertEquals(4, adapter(activity).itemCount)
                    activity.findViewById<android.view.View>(R.id.buttonApplyChanges).performClick()
                }
                val intent = requireNotNull(captured)
                assertEquals(filename, intent.getStringExtra("EXTRA_DRAFT_FILE_NAME"))
                val json = JSONObject(requireNotNull(intent.getStringExtra("EXTRA_JSON_PAYLOAD")))
                assertEquals("edit_schedule", json.getString("type"))
                assertEquals("editor-test-existing", json.getJSONObject("service").getString("serviceId"))
                assertFalse(json.getJSONObject("service").getBoolean("isRunning"))
                val stops = json.getJSONArray("stops")
                assertEquals(listOf(1, 2, 3, 4), (0 until 4).map { stops.getJSONObject(it).getInt("stopOrder") })
                assertEquals(301, stops.getJSONObject(0).getInt("stopId"))
                assertEquals("NEW", stops.getJSONObject(2).getString("stopId"))
                assertEquals(301, stops.getJSONObject(3).getInt("stopId"))
                assertEquals("23:30", stops.getJSONObject(0).getString("scheduledTime"))
                assertEquals("", stops.getJSONObject(3).getString("scheduledTime"))
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            DraftManager.deleteDraft(context, filename)
        }
    }

    @Test
    fun acceptedTimesAndBlankCopiedRowsRoundTripThroughTheSameNewServiceDraft() {
        val filename = DraftManager.saveDraft(context, JSONObject().apply {
            put("service", JSONObject().put("serviceId", "NEW-editor-test")
                .put("name", "Editor test").put("type", "TEST TYPE"))
            put("stops", JSONArray().apply {
                listOf("A" to "08:00", "B" to "08:30", "A" to "10:00", "B" to "").forEachIndexed { index, (name, time) ->
                    put(JSONObject().put("stopId", "NEW").put("locationName", name)
                        .put("scheduledTime", time).put("stopOrder", index + 1))
                }
            })
        }.toString())
        try {
            val intent = SuggestEditActivity.newIntentForNew(context)
                .putExtra(SuggestEditActivity.EXTRA_LOAD_DRAFT_FILE, filename)
            launch(intent).use { scenario ->
                scenario.onActivity { activity -> estimate(activity, 3) }
                waitForDialog()
                onView(withText(R.string.editor_use_time)).perform(click())
                scenario.onActivity { activity ->
                    activity.findViewById<android.view.View>(R.id.buttonReverseStops).performClick()
                }
                onView(withText(R.string.editor_append)).perform(click())
                scenario.onActivity { activity ->
                    activity.findViewById<android.view.View>(R.id.buttonSaveDraft).performClick()
                }
            }
            val saved = JSONObject(DraftManager.getDrafts(context).single { it.fileName == filename }.jsonPayload)
            assertEquals("new_schedule", saved.getString("type"))
            assertEquals("NEW-editor-test", saved.getJSONObject("service").getString("serviceId"))
            assertEquals("10:30", saved.getJSONArray("stops").getJSONObject(3).getString("scheduledTime"))
            assertEquals("", saved.getJSONArray("stops").getJSONObject(4).getString("scheduledTime"))
            launch(intent).use { scenario ->
                scenario.onActivity { activity ->
                    assertEquals(filename, activity.currentDraftFileName)
                    assertEquals(8, adapter(activity).itemCount)
                    assertEquals("10:30", adapter(activity).getStopsData()[3].scheduledTime)
                    assertTrue(adapter(activity).getStopsData().drop(4).all { it.scheduledTime.isEmpty() })
                }
            }
        } finally {
            DraftManager.deleteDraft(context, filename)
        }
    }

    private fun launch(intent: Intent = SuggestEditActivity.newIntentForNew(context)): ActivityScenario<SuggestEditActivity> {
        val scenario = ActivityScenario.launch<SuggestEditActivity>(intent)
        if (intent.getStringExtra(SuggestEditActivity.EXTRA_LOAD_DRAFT_FILE) == null) {
            onView(withText(R.string.editor_got_it)).perform(click())
        } else {
            onView(withText(R.string.editor_quick_tips)).check(doesNotExist())
            onView(withText(R.string.editor_instructions)).check(doesNotExist())
        }
        val deadline = System.currentTimeMillis() + 10_000
        var loaded = false
        while (!loaded && System.currentTimeMillis() < deadline) {
            scenario.onActivity {
                loaded = it.findViewById<AutoCompleteTextView>(R.id.editTextServiceType).adapter != null
            }
            if (!loaded) Thread.sleep(50)
        }
        assertTrue("Editor options did not load", loaded)
        instrumentation.waitForIdleSync()
        return scenario
    }

    private fun populate(activity: SuggestEditActivity, vararg values: Pair<String, String>) {
        val adapter = adapter(activity)
        val first = adapter.getStopsData().first()
        first.stopName = values.first().first
        first.scheduledTime = values.first().second
        adapter.appendCopies(values.drop(1).mapIndexed { index, (name, time) ->
            EditableStop(name, time, index + 2, index + 2)
        })
        adapter.routeReplaced()
    }

    private fun estimate(activity: SuggestEditActivity, position: Int) {
        val recycler = activity.findViewById<RecyclerView>(R.id.recyclerViewStopsEditor)
        // The editor's non-scrolling, wrap-content list lays out the full route.
        val row = requireNotNull(recycler.findViewHolderForAdapterPosition(position))
        row.itemView.findViewById<android.view.View>(R.id.buttonSuggestTime).performClick()
    }

    private fun adapter(activity: SuggestEditActivity) =
        activity.findViewById<RecyclerView>(R.id.recyclerViewStopsEditor).adapter as StopEditAdapter

    private fun waitForDialog(buttonText: Int = R.string.editor_use_time) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            val root = instrumentation.uiAutomation.rootInActiveWindow
            if (!root?.findAccessibilityNodeInfosByText(context.getString(buttonText)).isNullOrEmpty()) return
            Thread.sleep(50)
        }
        fail("Estimate dialog did not appear")
    }
}
