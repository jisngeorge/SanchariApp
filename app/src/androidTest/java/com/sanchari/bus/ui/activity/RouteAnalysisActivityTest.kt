package com.sanchari.bus.ui.activity

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.timepicker.MaterialTimePicker
import com.sanchari.bus.ui.helper.ScheduleEditorLogic
import android.os.SystemClock
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sanchari.bus.R
import com.sanchari.bus.data.local.DatabaseConstants
import com.sanchari.bus.data.local.DatabaseManager
import com.sanchari.bus.data.local.LocalVersionManager
import com.sanchari.bus.data.local.TimetableDatabaseHelper
import com.sanchari.bus.data.model.EditableStop
import com.sanchari.bus.databinding.ActivitySuggestEditBinding
import com.sanchari.bus.ui.adapter.StopEditAdapter
import com.sanchari.bus.ui.helper.DraftManager
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.not
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class RouteAnalysisActivityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val files = mutableListOf<String>()
    private val base = 1_900_000_000
    private val source = "analysis-test-source"
    private val names = listOf("Analysis origin", "Analysis middle one", "Analysis middle two", "Analysis destination")
    private val forwarded = CopyOnWriteArrayList<Intent>()
    private val monitor = object : Instrumentation.ActivityMonitor() {
        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
            if (intent.component?.className == ConfirmationActivity::class.java.name) {
                forwarded += intent
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
            return null
        }
    }

    @Before
    fun seed() {
        runBlocking { DatabaseManager.initializeDatabases(context) }
        val db = TimetableDatabaseHelper.getInstance(context).writableDatabase
        names.forEachIndexed { index, name ->
            db.execSQL("INSERT INTO Stop VALUES (?, ?, ?, 76.0)",
                arrayOf(base + index, name, 10.0 + (if (index == 2) 1.5 else index.toDouble()) * 0.01))
        }
        db.execSQL("INSERT INTO BusService VALUES (?, 'Analysis source', 'Analysis type', 1, 0)", arrayOf(source))
        names.indices.forEach { index ->
            db.execSQL("INSERT INTO RouteStop(serviceId,stopId,scheduledTime,stopOrder) VALUES (?, ?, '', ?)",
                arrayOf(source, base + index, index + 1))
        }
        instrumentation.addMonitor(monitor)
    }

    @After
    fun cleanup() {
        instrumentation.removeMonitor(monitor)
        runBlocking { DraftManager.deleteDraftAsync(context, DraftManager.newDraftFileName()).await() }
        files.forEach { DraftManager.deleteDraft(context, it) }
        val db = TimetableDatabaseHelper.getInstance(context).writableDatabase
        for (id in listOf(source, "$source-alternative")) {
            db.delete("RouteStop", "serviceId = ?", arrayOf(id))
            db.delete("BusService", "serviceId = ?", arrayOf(id))
        }
        names.indices.forEach { db.delete("Stop", "stopId = ?", arrayOf((base + it).toString())) }
    }

    @Test
    fun partialAcceptanceRequiresTimesBeforeContinuingAndPreservesTheDraft() {
        val file = fixture()
        launch(file).use { scenario ->
            submitForReview(scenario)
            onView(withText("1. Between Analysis origin (stop 1) [11:00 PM] and Analysis destination (stop 2) [12:30 AM]"))
                .check(matches(isDisplayed()))
            onView(withText(R.string.analysis_intro)).check(matches(isDisplayed()))
            onView(withContentDescription(choiceDescription(1, 1, R.string.analysis_reject)))
                .check(matches(isChecked()))
            onView(withContentDescription(choiceDescription(1, 2, R.string.analysis_reject)))
                .check(matches(isChecked()))
            onView(withText(R.string.analysis_edit_times)).check(matches(not(isEnabled())))
            acceptStop(1, 1)
            onView(withText(R.string.analysis_finish)).perform(click())
            assertMissingTimes(scenario)
            scenario.onActivity { adapter(it).updateTime(1, "23:30") }
            submitForReview(scenario)
            onView(withText(R.string.analysis_finish)).perform(click())
            await { forwarded.size == 1 }
            val payload = JSONObject(requireNotNull(forwarded.single().getStringExtra("EXTRA_JSON_PAYLOAD")))
            assertEquals(file, forwarded.single().getStringExtra(ConfirmationActivity.EXTRA_DRAFT_FILE_NAME))
            assertEquals("analysis-edited", payload.getJSONObject("service").getString("serviceId"))
            val rows = payload.getJSONArray("stops")
            assertEquals(listOf(base, base + 1, base + 3),
                (0 until rows.length()).map { rows.getJSONObject(it).getInt("stopId") })
            assertEquals(listOf("23:00", "23:30", "00:30"),
                (0 until rows.length()).map { rows.getJSONObject(it).getString("scheduledTime") })
            assertEquals((1..3).toList(),
                (0 until rows.length()).map { rows.getJSONObject(it).getInt("stopOrder") })
            scenario.recreate()
            scenario.onActivity {
                assertEquals(3, adapter(it).itemCount)
                assertTrue(binding(it).buttonApplyChanges.isEnabled)
            }
        }
        runBlocking { DraftManager.deleteDraftAsync(context, DraftManager.newDraftFileName()).await() }
        val saved = JSONObject(DraftManager.getDrafts(context).single { it.fileName == file }.jsonPayload)
        assertEquals(3, saved.getJSONArray("stops").length())
    }

    @Test
    fun eachSubmitChecksAgainEvenWhenPreviousSuggestionsWereSkipped() {
        launch().use { scenario ->
            repeat(2) { iteration ->
                submitForReview(scenario)
                assertEquals(iteration, forwarded.size)
                onView(withText(R.string.analysis_finish)).perform(click())
                await { forwarded.size == iteration + 1 }
            }
        }
    }

    @Test
    fun noMatchesContinuesDirectlyAndNextSubmitReadsTheUpdatedTimetable() {
        launch(fixture(source)).use { scenario ->
            scenario.onActivity { binding(it).buttonApplyChanges.performClick() }
            await { forwarded.size == 1 }
            val db = TimetableDatabaseHelper.getInstance(context).writableDatabase
            db.execSQL("INSERT INTO BusService VALUES (?, 'Alternative', 'Other type', 1, 0)",
                arrayOf("$source-alternative"))
            listOf(0, 1, 3).forEachIndexed { order, index ->
                db.execSQL("INSERT INTO RouteStop(serviceId,stopId,scheduledTime,stopOrder) VALUES (?, ?, '', ?)",
                    arrayOf("$source-alternative", base + index, order + 1))
            }
            submitForReview(scenario)
            assertEquals(1, forwarded.size)
            onView(withText(android.R.string.cancel)).perform(click())
        }
    }

    @Test
    fun cancellingFinalReviewOrRecreatingPendingReviewDoesNotInsertOrNavigate() {
        launch().use { scenario ->
            submitForReview(scenario)
            acceptStop(1, 1)
            onView(withText(android.R.string.cancel)).perform(click())
            scenario.onActivity {
                assertEquals(2, adapter(it).itemCount)
                assertTrue(binding(it).buttonApplyChanges.isEnabled)
            }
            submitForReview(scenario)
            scenario.recreate()
            scenario.onActivity {
                assertEquals(2, adapter(it).itemCount)
                assertTrue(binding(it).buttonApplyChanges.isEnabled)
            }
            assertTrue(forwarded.isEmpty())
        }
    }

    @Test
    fun structuralEditDismissesStaleRecommendationsWithoutNavigating() {
        launch().use { scenario ->
            submitForReview(scenario)
            scenario.onActivity { binding(it).editTextServiceType.setText("Changed type", false) }
            onView(withText(R.string.analysis_title)).check(doesNotExist())
            scenario.onActivity {
                assertEquals(2, adapter(it).itemCount)
                assertTrue(binding(it).buttonApplyChanges.isEnabled)
            }
            assertTrue(forwarded.isEmpty())
        }
    }

    @Test
    fun timetableVersionChangeCancelsPendingReview() {
        val version = LocalVersionManager.getTimetableDbVersion(context)
        launch().use { scenario ->
            submitForReview(scenario)
            try {
                context.getSharedPreferences("SanchariBusPrefs", 0).edit()
                    .putInt(DatabaseConstants.TIMETABLE_DB_VERSION_KEY, version + 1).commit()
                scenario.onActivity { assertFalse(it.hasReviewedRoute()) }
                onView(withText(R.string.analysis_title)).check(doesNotExist())
                assertTrue(forwarded.isEmpty())
            } finally {
                context.getSharedPreferences("SanchariBusPrefs", 0).edit()
                    .putInt(DatabaseConstants.TIMETABLE_DB_VERSION_KEY, version).commit()
            }
        }
    }

    @Test
    fun failedLookupShowsRetryAndNeverContinuesWithoutSuccessfulAnalysis() {
        launch(fixture(source)).use { scenario ->
            val db = TimetableDatabaseHelper.getInstance(context).writableDatabase
            db.execSQL("ALTER TABLE RouteStop RENAME TO AnalysisTestRouteStop")
            try {
                scenario.onActivity { binding(it).buttonApplyChanges.performClick() }
                await {
                    var failed = false
                    scenario.onActivity {
                        failed = binding(it).textRouteAnalysisStatus.text.toString() == context.getString(R.string.analysis_failed)
                        if (failed) assertTrue(binding(it).buttonApplyChanges.isEnabled)
                    }
                    failed
                }
                assertTrue(forwarded.isEmpty())
            } finally {
                db.execSQL("ALTER TABLE AnalysisTestRouteStop RENAME TO RouteStop")
            }
            scenario.onActivity { binding(it).buttonApplyChanges.performClick() }
            await { forwarded.size == 1 }
        }
    }

    @Test
    fun alternativeSelectionForNewServiceDoesNotMixSequences() {
        val db = TimetableDatabaseHelper.getInstance(context).writableDatabase
        db.execSQL("INSERT INTO BusService VALUES (?, 'Alternative', 'Other type', 1, 0)", arrayOf("$source-alternative"))
        listOf(0, 2, 1, 3).forEachIndexed { order, index ->
            db.execSQL("INSERT INTO RouteStop(serviceId,stopId,scheduledTime,stopOrder) VALUES (?, ?, '', ?)",
                arrayOf("$source-alternative", base + index, order + 1))
        }
        launch(fixture("NEW-analysis-test")).use { scenario ->
            submitForReview(scenario)
            acceptStop(1, 1)
            onView(withContentDescription(context.getString(R.string.analysis_route_choice, 1)))
                .perform(scrollTo(), click())
            onData(equalTo(context.getString(R.string.analysis_route_option, 2)))
                .inRoot(isPlatformPopup()).perform(click())
            onView(withContentDescription(choiceDescription(1, 1, R.string.analysis_reject, names[2])))
                .check(matches(isChecked()))
            onView(withText(R.string.analysis_edit_times)).check(matches(not(isEnabled())))
            acceptStop(1, 1, names[2])
            onView(withText(R.string.analysis_finish)).perform(click())
            assertMissingTimes(scenario)
            scenario.onActivity { adapter(it).updateTime(1, "23:30") }
            submitForReview(scenario)
            onView(withText(R.string.analysis_finish)).perform(click())
            await { forwarded.size == 1 }
            scenario.onActivity {
                assertEquals(listOf(base, base + 2, base + 3),
                    adapter(it).getStopsData().map { row -> row.originalStopId })
                assertEquals("NEW-analysis-test", it.getOriginalService()?.serviceId)
            }
        }
    }

    @Test
    fun eachGapExplainsItsOwnStopOrdersAndNamesBeforeContinuing() {
        launch().use { scenario ->
            scenario.onActivity {
                adapter(it).appendCopies(listOf(
                    EditableStop(names[0], "01:00", 3, base),
                    EditableStop(names[3], "02:00", 4, base + 3)))
            }
            submitForReview(scenario)
            onView(withText("1. Between Analysis origin (stop 1) [11:00 PM] and Analysis destination (stop 2) [12:30 AM]"))
                .perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("2. Between Analysis origin (stop 3) [1:00 AM] and Analysis destination (stop 4) [2:00 AM]"))
                .perform(scrollTo()).check(matches(isDisplayed()))
            assertTrue(forwarded.isEmpty())
            acceptStop(1, 1)
            acceptStop(2, 2)
            onView(withText(R.string.analysis_finish)).perform(click())
            assertMissingTimes(scenario)
            scenario.onActivity {
                adapter(it).updateTime(1, "23:30")
                adapter(it).updateTime(4, "01:30")
            }
            submitForReview(scenario)
            onView(withText(R.string.analysis_finish)).perform(click())
            await { forwarded.size == 1 }
            scenario.onActivity {
                assertEquals(listOf(base, base + 1, base + 3, base, base + 2, base + 3),
                    adapter(it).getStopsData().map { stop -> stop.originalStopId })
            }
        }
    }

    @Test
    fun timeOnlyEditsDuringReviewAreRetainedWithoutCancellingSubmission() {
        launch().use { scenario ->
            submitForReview(scenario)
            scenario.onActivity {
                adapter(it).updateTime(0, "22:45")
                binding(it).editTextServiceName.setText("Renamed during review")
                binding(it).editTextEditNotes.setText("Latest notes")
            }
            onView(withText(R.string.analysis_finish)).perform(click())
            await { forwarded.size == 1 }
            val payload = JSONObject(requireNotNull(forwarded.single().getStringExtra("EXTRA_JSON_PAYLOAD")))
            assertEquals("22:45", payload.getJSONArray("stops").getJSONObject(0).getString("scheduledTime"))
            assertEquals("Renamed during review", payload.getJSONObject("service").getString("name"))
            assertEquals("Latest notes", payload.getString("editNotes"))
        }
    }

    @Test
    fun detourAndUnverifiableSequencesAreNotOfferedDuringSubmission() {
        launch().use { scenario ->
            val db = TimetableDatabaseHelper.getInstance(context).writableDatabase
            for ((iteration, coordinates) in listOf(11.0 to 76.0, 0.0 to 0.0).withIndex()) {
                db.execSQL("UPDATE Stop SET latitude = ?, longitude = ? WHERE stopId = ?",
                    arrayOf(coordinates.first, coordinates.second, base + 1))
                scenario.onActivity { binding(it).buttonApplyChanges.performClick() }
                await { forwarded.size == iteration + 1 }
                scenario.onActivity { assertEquals(2, adapter(it).itemCount) }
            }
        }
    }

    @Test
    fun addAndEditTimesKeepsEditorOpenAndPreservesInsertedStopsInDraft() {
        val file = fixture()
        launch(file).use { scenario ->
            submitForReview(scenario)
            onView(withText(R.string.analysis_guidance)).perform(scrollTo()).check(matches(isDisplayed()))
            acceptStop(1, 1)
            onView(withText(R.string.analysis_edit_times)).perform(click())
            assertTrue(forwarded.isEmpty())
            scenario.onActivity {
                assertEquals(listOf(base, base + 1, base + 3),
                    adapter(it).getStopsData().map { stop -> stop.originalStopId })
                assertEquals("", adapter(it).getStopsData()[1].scheduledTime)
                assertTrue(binding(it).buttonApplyChanges.isEnabled)
                adapter(it).updateTime(1, "23:30")
            }
            scenario.recreate()
            scenario.onActivity {
                assertEquals(file, it.currentDraftFileName)
                assertEquals("23:30", adapter(it).getStopsData()[1].scheduledTime)
            }
        }
        runBlocking { DraftManager.deleteDraftAsync(context, DraftManager.newDraftFileName()).await() }
        val saved = JSONObject(DraftManager.getDrafts(context).single { it.fileName == file }.jsonPayload)
        assertEquals("23:30", saved.getJSONArray("stops").getJSONObject(1).getString("scheduledTime"))
    }

    @Test
    fun acceptedStopCanBeRejectedBeforeContinuingWithoutAddingAnUntimedStop() {
        launch().use { scenario ->
            submitForReview(scenario)
            acceptStop(1, 1)
            onView(withContentDescription(choiceDescription(1, 1, R.string.analysis_reject)))
                .perform(scrollTo(), click())
            onView(withText(R.string.analysis_edit_times)).check(matches(not(isEnabled())))
            onView(withText(R.string.analysis_finish)).perform(click())
            await { forwarded.size == 1 }
            scenario.onActivity { assertEquals(2, adapter(it).itemCount) }
        }
    }

    @Test
    fun missingTimesAreOutlinedBeforeAnyAnalysisAndClearWhenCorrected() {
        launch().use { scenario ->
            val db = TimetableDatabaseHelper.getInstance(context).writableDatabase
            db.execSQL("ALTER TABLE RouteStop RENAME TO AnalysisTestRouteStop")
            try {
                scenario.onActivity {
                    adapter(it).updateTime(0, "--:--")
                    adapter(it).updateTime(1, "")
                    binding(it).buttonApplyChanges.performClick()
                    assertTrue(binding(it).buttonApplyChanges.isEnabled)
                    assertEquals(android.view.View.GONE, binding(it).textRouteAnalysisStatus.visibility)
                }
                assertMissingTimes(scenario)
                scenario.recreate()
                assertMissingTimes(scenario)
                scenario.onActivity {
                    adapter(it).updateTime(0, "09:00")
                    binding(it).buttonApplyChanges.performClick()
                    assertTrue(binding(it).buttonApplyChanges.isEnabled)
                }
                assertMissingTimes(scenario)
                scenario.onActivity {
                    val first = binding(it).recyclerViewStopsEditor.findViewHolderForAdapterPosition(0)!!.itemView as MaterialCardView
                    assertNotEquals(MaterialColors.getColor(first, androidx.appcompat.R.attr.colorError), first.strokeColor)
                }
            } finally {
                db.execSQL("ALTER TABLE AnalysisTestRouteStop RENAME TO RouteStop")
            }
            scenario.onActivity { adapter(it).updateTime(1, "09:30") }
            submitForReview(scenario)
            onView(withText(R.string.analysis_finish)).perform(click())
            await { forwarded.size == 1 }
        }
    }

    @Test
    fun emptyStopNamesBlockAnalysisAndClearTheirRedOutlineWhenEntered() {
        launch().use { scenario ->
            val db = TimetableDatabaseHelper.getInstance(context).writableDatabase
            db.execSQL("ALTER TABLE RouteStop RENAME TO AnalysisTestRouteStop")
            try {
                scenario.onActivity {
                    val input = binding(it).recyclerViewStopsEditor.findViewHolderForAdapterPosition(0)!!
                        .itemView.findViewById<android.widget.AutoCompleteTextView>(R.id.editTextStopName)
                    input.setText("", false)
                    binding(it).buttonApplyChanges.performClick()
                    assertTrue(binding(it).buttonApplyChanges.isEnabled)
                    assertEquals(android.view.View.GONE, binding(it).textRouteAnalysisStatus.visibility)
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    val first = binding(it).recyclerViewStopsEditor.findViewHolderForAdapterPosition(0)!!.itemView as MaterialCardView
                    assertEquals(MaterialColors.getColor(first, androidx.appcompat.R.attr.colorError), first.strokeColor)
                    assertTrue(first.strokeWidth > 0)
                    val input = first.findViewById<android.widget.AutoCompleteTextView>(R.id.editTextStopName)
                    input.setText(names[0], false)
                    assertNotEquals(MaterialColors.getColor(first, androidx.appcompat.R.attr.colorError), first.strokeColor)
                    assertTrue(forwarded.isEmpty())
                }
            } finally {
                db.execSQL("ALTER TABLE AnalysisTestRouteStop RENAME TO RouteStop")
            }
            submitForReview(scenario)
            onView(withText(android.R.string.cancel)).perform(click())
        }
    }

    private fun assertMissingTimes(scenario: ActivityScenario<SuggestEditActivity>) {
        instrumentation.waitForIdleSync()
        assertTrue(forwarded.isEmpty())
        scenario.onActivity {
            val missing = ScheduleEditorLogic.missingTimePositions(adapter(it).getStopsData())
            assertTrue(missing.isNotEmpty())
            assertTrue(adapter(it).validationRequested)
            assertTrue(binding(it).buttonApplyChanges.isEnabled)
            assertTrue(it.supportFragmentManager.fragments.none { fragment -> fragment is MaterialTimePicker })
            missing.forEach { position ->
                val row = binding(it).recyclerViewStopsEditor.findViewHolderForAdapterPosition(position)!!.itemView as MaterialCardView
                assertEquals(MaterialColors.getColor(row, androidx.appcompat.R.attr.colorError), row.strokeColor)
                assertTrue(row.strokeWidth > 0)
                assertEquals(context.getString(R.string.editor_stop_time_required),
                    androidx.core.view.ViewCompat.getStateDescription(row))
            }
        }
        onView(withText(R.string.analysis_title)).check(doesNotExist())
    }

    @Test
    fun longReviewKeepsAllActionsFullyInsidePopupAboveItsBottomEdge() {
        launch().use { scenario ->
            scenario.onActivity {
                adapter(it).appendCopies((0 until 10).map { index ->
                    val endpoint = if (index % 2 == 0) 0 else 3
                    EditableStop(names[endpoint], if (endpoint == 0) "01:00" else "02:00", index + 3, base + endpoint)
                })
            }
            submitForReview(scenario)
            for (label in listOf(R.string.analysis_finish, R.string.analysis_edit_times, android.R.string.cancel)) {
                onView(withText(label)).check { view, missing ->
                    if (missing != null) throw missing
                    val button = requireNotNull(view)
                    val panel = requireNotNull(button.rootView.findViewById<android.view.View>(
                        androidx.appcompat.R.id.parentPanel))
                    val visible = Rect()
                    assertTrue("Action must be visible", button.getGlobalVisibleRect(visible))
                    assertEquals("Full action height must fit inside the popup", button.height, visible.height())
                    assertEquals("Full action width must fit inside the popup", button.width, visible.width())
                    val buttonPosition = IntArray(2)
                    val panelPosition = IntArray(2)
                    button.getLocationOnScreen(buttonPosition)
                    panel.getLocationOnScreen(panelPosition)
                    assertTrue("Action must not cross popup bottom",
                        buttonPosition[1] + button.height <= panelPosition[1] + panel.height)
                }
            }
            onView(withText(android.R.string.cancel)).perform(click())
            assertTrue(forwarded.isEmpty())
            scenario.onActivity { assertEquals(12, adapter(it).itemCount) }
        }
    }

    private fun submitForReview(scenario: ActivityScenario<SuggestEditActivity>) {
        scenario.onActivity {
            assertTrue(binding(it).buttonApplyChanges.isEnabled)
            binding(it).buttonApplyChanges.performClick()
            assertFalse(binding(it).buttonApplyChanges.isEnabled)
        }
        await {
            instrumentation.waitForIdleSync()
            !instrumentation.uiAutomation.rootInActiveWindow
                ?.findAccessibilityNodeInfosByText(context.getString(R.string.analysis_title)).isNullOrEmpty()
        }
        instrumentation.waitForIdleSync()
    }

    private fun choiceDescription(gap: Int, stop: Int, action: Int, name: String = names[stop]) =
        context.getString(R.string.analysis_choice_description, gap, stop, name, context.getString(action))

    private fun acceptStop(gap: Int, stop: Int, name: String = names[stop]) =
        onView(withContentDescription(choiceDescription(gap, stop, R.string.analysis_accept, name)))
            .perform(scrollTo(), click())

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(25)
        }
        fail("Analysis did not reach expected state")
    }

    private fun fixture(id: String = "analysis-edited") = DraftManager.saveDraft(context, JSONObject().apply {
        put("type", if (id.startsWith("NEW-")) "new_schedule" else "edit_schedule")
        put("service", JSONObject().put("serviceId", id).put("name", "Analysis edit")
            .put("type", "Analysis type").put("isRunning", true))
        put("stops", JSONArray().apply {
            for ((index, time) in listOf(0 to "23:00", 3 to "00:30")) {
                put(JSONObject().put("stopId", base + index).put("locationName", names[index]).put("scheduledTime", time))
            }
        })
        put("editNotes", "")
    }.toString()).also(files::add)

    private fun launch(file: String = fixture()): ActivityScenario<SuggestEditActivity> {
        val scenario = ActivityScenario.launch<SuggestEditActivity>(
            SuggestEditActivity.newIntentForNew(context).putExtra(SuggestEditActivity.EXTRA_LOAD_DRAFT_FILE, file))
        await {
            var ready = false
            scenario.onActivity { ready = binding(it).editTextServiceType.adapter != null }
            ready
        }
        return scenario
    }

    private fun binding(activity: SuggestEditActivity) = ActivitySuggestEditBinding.bind(
        activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0))

    private fun adapter(activity: SuggestEditActivity) =
        binding(activity).recyclerViewStopsEditor.adapter as StopEditAdapter
}
