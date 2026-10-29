package com.sanchari.bus.ui.activity

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sanchari.bus.R
import com.sanchari.bus.data.local.DatabaseManager
import com.sanchari.bus.databinding.ActivitySuggestEditBinding
import com.sanchari.bus.ui.helper.DraftManager
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ScheduleEditorAutosaveTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var initialFiles = emptySet<String>()

    @Before
    fun initialize() {
        runBlocking { DatabaseManager.initializeDatabases(context) }
        flushWrites()
        initialFiles = DraftManager.getDrafts(context).map { it.fileName }.toSet()
    }

    @After
    fun cleanUpTestDrafts() {
        flushWrites()
        DraftManager.getDrafts(context).filter { it.fileName !in initialFiles }
            .forEach { DraftManager.deleteDraft(context, it.fileName) }
    }

    @Test
    fun systemBackAndToolbarExitSaveLatestFocusedInputToTheSameDraftWithoutPrompting() {
        for (toolbarExit in listOf(false, true)) {
            val file = fixture("Existing")
            launch(file).use { scenario ->
                scenario.onActivity { activity ->
                    val binding = binding(activity)
                    binding.editTextServiceName.setText("Renamed")
                    binding.editTextEditNotes.setText("Latest notes")
                    val row = requireNotNull(binding.recyclerViewStopsEditor.findViewHolderForAdapterPosition(0))
                    val input = row.itemView.findViewById<android.widget.AutoCompleteTextView>(R.id.editTextStopName)
                    input.requestFocus()
                    input.setText("A newly entered stop", false)
                }
                if (toolbarExit) {
                    onView(withContentDescription(androidx.appcompat.R.string.abc_action_bar_up_description)).perform(click())
                } else {
                    scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                }
                awaitExit(scenario)
            }
            val saved = draft(file)
            assertEquals("Renamed", saved.getJSONObject("service").getString("name"))
            assertEquals("autosave-existing", saved.getJSONObject("service").getString("serviceId"))
            assertEquals("Latest notes", saved.getString("editNotes"))
            assertEquals("A newly entered stop", saved.getJSONArray("stops").getJSONObject(0).getString("locationName"))
            assertEquals("NEW", saved.getJSONArray("stops").getJSONObject(0).getString("stopId"))
            assertEquals("23:30", saved.getJSONArray("stops").getJSONObject(0).getString("scheduledTime"))
            assertEquals(file.removeSuffix(".json"), saved.getString("draftId"))
        }
    }

    @Test
    fun backgroundAndRecreationPreserveLatestChangesAndOneNewServiceIdentity() {
        var file = ""
        launch().use { scenario ->
            scenario.onActivity { activity ->
                binding(activity).editTextEditNotes.setText("Notes alone are useful progress")
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            flushWrites()
            scenario.onActivity { file = requireNotNull(it.currentDraftFileName) }
            val id = draft(file).getJSONObject("service").getString("serviceId")
            assertTrue(id.startsWith("NEW-"))
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { activity ->
                binding(activity).editTextServiceName.setText("New bus")
                binding(activity).editTextEditNotes.setText("After background")
            }
            scenario.recreate()
            scenario.onActivity {
                assertEquals(file, it.currentDraftFileName)
                assertEquals(id, it.getOriginalService()?.serviceId)
                binding(it).editTextEditNotes.setText("Newest revision")
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            flushWrites()
            assertEquals("Newest revision", draft(file).getString("editNotes"))
            assertEquals(id, draft(file).getJSONObject("service").getString("serviceId"))
        }
        assertEquals(1, DraftManager.getDrafts(context).count { it.fileName !in initialFiles })
        launch(file).use { scenario ->
            scenario.onActivity { assertEquals("Newest revision", binding(it).editTextEditNotes.text.toString()) }
        }
    }

    @Test
    fun cancellingDiscardKeepsChangesButConfirmedDiscardCannotBeUndoneByALateSave() {
        val file = fixture("Discard test")
        launch(file).use { scenario ->
            scenario.onActivity {
                binding(it).editTextEditNotes.setText("Keep this change")
                binding(it).buttonDiscardDraft.performClick()
            }
            onView(withText("Cancel")).perform(click())
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            awaitExit(scenario)
        }
        assertEquals("Keep this change", draft(file).getString("editNotes"))
        launch(file).use { scenario ->
            scenario.onActivity { binding(it).editTextEditNotes.setText("Discard this change") }
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { binding(it).buttonDiscardDraft.performClick() }
            onView(withText("Discard")).perform(click())
            awaitExit(scenario)
        }
        flushWrites()
        assertFalse(DraftManager.getDrafts(context).any { it.fileName == file })
    }

    @Test
    fun switchingHistoryDraftsPersistsEachEditorsLatestChangesSeparately() {
        val first = fixture("First")
        val second = fixture("Second")
        launch(first).use { scenario ->
            scenario.onActivity {
                binding(it).editTextEditNotes.setText("First latest change")
                binding(it).btnHistory.performClick()
            }
            await {
                !instrumentation.uiAutomation.rootInActiveWindow
                    ?.findAccessibilityNodeInfosByText("Saved Drafts").isNullOrEmpty()
            }
            onView(withText("Second (AUTOSAVE TEST TYPE) - Starts: Start")).perform(click())
            scenario.onActivity {
                assertEquals(second, it.currentDraftFileName)
                binding(it).editTextEditNotes.setText("Second latest change")
                it.onBackPressedDispatcher.onBackPressed()
            }
            awaitExit(scenario)
        }
        assertEquals("First latest change", draft(first).getString("editNotes"))
        assertEquals("Second latest change", draft(second).getString("editNotes"))
    }

    @Test
    fun confirmationReceivesThePersistedDraftAndCleanupIsNotResurrectedByRecreationOrExit() {
        val file = fixture("Confirmation")
        var forwarded: Intent? = null
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.component?.className == ConfirmationActivity::class.java.name) {
                    forwarded = intent
                    return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                }
                return null
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            launch(file).use { scenario ->
                scenario.onActivity {
                    binding(it).editTextEditNotes.setText("Ready for confirmation")
                    binding(it).buttonApplyChanges.performClick()
                }
                await { forwarded != null }
                val intent = requireNotNull(forwarded)
                assertEquals(file, intent.getStringExtra(ConfirmationActivity.EXTRA_DRAFT_FILE_NAME))
                val suggestion = JSONObject(requireNotNull(intent.getStringExtra("EXTRA_JSON_PAYLOAD")))
                assertEquals("Ready for confirmation", suggestion.getString("editNotes"))
                assertEquals(suggestion.getJSONObject("service").getString("serviceId"),
                    draft(file).getJSONObject("service").getString("serviceId"))
                assertEquals("Ready for confirmation", draft(file).getString("editNotes"))
                runBlocking { DraftManager.deleteDraftAsync(context, file).await() }
                scenario.recreate()
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                awaitExit(scenario)
            }
            flushWrites()
            assertFalse(DraftManager.getDrafts(context).any { it.fileName == file })
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    fun unchangedEmptyNewEditorDoesNotCreateADraft() {
        launch().use { scenario ->
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            awaitExit(scenario)
        }
        flushWrites()
        assertEquals(initialFiles, DraftManager.getDrafts(context).map { it.fileName }.toSet())
    }

    @Test
    fun changesMadeDuringAPendingSaveAreRevalidatedBeforeConfirmation() {
        val file = fixture("Pending confirmation")
        val monitor = Instrumentation.ActivityMonitor(ConfirmationActivity::class.java.name, null, true)
        instrumentation.addMonitor(monitor)
        try {
            launch(file).use { scenario ->
                val locked = CountDownLatch(1)
                val release = CountDownLatch(1)
                val blocker = Thread {
                    synchronized(DraftManager) {
                        locked.countDown()
                        release.await(10, TimeUnit.SECONDS)
                    }
                }
                blocker.start()
                try {
                    assertTrue(locked.await(5, TimeUnit.SECONDS))
                    scenario.onActivity {
                        binding(it).editTextEditNotes.setText("Start a save")
                        binding(it).buttonApplyChanges.performClick()
                        binding(it).editTextServiceName.setText("")
                    }
                } finally {
                    release.countDown()
                    blocker.join(5_000)
                }
                flushWrites()
                instrumentation.waitForIdleSync()
                assertEquals(0, monitor.hits)
                scenario.onActivity {
                    assertFalse(it.isFinishing)
                    assertEquals("", binding(it).editTextServiceName.text.toString())
                }
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
        assertEquals("", draft(file).getJSONObject("service").getString("name"))
    }

    @Test
    fun failedExitSaveLeavesTheEditorAndPreviousDraftIntact() {
        val file = fixture("Save failure")
        val blocker = File(context.getDir("suggest_edits_drafts", 0), "blocked_${DraftManager.newDraftFileName().removeSuffix(".json")}")
        blocker.writeText("Block parent directory creation")
        try {
            launch(file).use { scenario ->
                scenario.onActivity {
                    it.currentDraftFileName = "${blocker.name}/child.json"
                    binding(it).editTextEditNotes.setText("Must not be lost")
                    it.onBackPressedDispatcher.onBackPressed()
                }
                flushWrites()
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    assertFalse(it.isFinishing)
                    assertEquals("Must not be lost", binding(it).editTextEditNotes.text.toString())
                    it.currentDraftFileName = file
                }
                assertEquals("", draft(file).getString("editNotes"))
            }
        } finally {
            assertTrue(blocker.delete())
        }
    }

    private fun fixture(name: String): String = DraftManager.saveDraft(context, JSONObject().apply {
        put("type", "edit_schedule")
        put("service", JSONObject().put("serviceId", "autosave-existing").put("name", name)
            .put("type", "AUTOSAVE TEST TYPE").put("isRunning", false).put("lastReportedTime", 123L))
        put("stops", JSONArray()
            .put(JSONObject().put("stopId", 301).put("locationName", "Start").put("scheduledTime", "23:30").put("stopOrder", 1))
            .put(JSONObject().put("stopId", "NEW").put("locationName", "End").put("scheduledTime", "00:15").put("stopOrder", 2)))
        put("editNotes", "")
        put("timestamp", 1)
    }.toString())

    private fun draft(file: String): JSONObject {
        flushWrites()
        return JSONObject(DraftManager.getDrafts(context).single { it.fileName == file }.jsonPayload)
    }

    private fun flushWrites() = runBlocking {
        DraftManager.deleteDraftAsync(context, DraftManager.newDraftFileName()).await()
    }

    private fun launch(file: String? = null): ActivityScenario<SuggestEditActivity> {
        val intent = SuggestEditActivity.newIntentForNew(context).apply {
            if (file != null) putExtra(SuggestEditActivity.EXTRA_LOAD_DRAFT_FILE, file)
        }
        val scenario = ActivityScenario.launch<SuggestEditActivity>(intent)
        if (file == null) onView(withText(R.string.editor_got_it)).perform(click())
        await {
            var ready = false
            scenario.onActivity { ready = binding(it).editTextServiceType.adapter != null }
            ready
        }
        instrumentation.waitForIdleSync()
        return scenario
    }

    private fun binding(activity: SuggestEditActivity): ActivitySuggestEditBinding =
        ActivitySuggestEditBinding.bind(activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0))

    private fun awaitExit(scenario: ActivityScenario<SuggestEditActivity>) {
        await {
            if (scenario.state == Lifecycle.State.DESTROYED) true
            else {
                var finishing = false
                scenario.onActivity { finishing = it.isFinishing }
                finishing
            }
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(25)
        }
        fail("Editor did not reach the expected persisted state")
    }
}
