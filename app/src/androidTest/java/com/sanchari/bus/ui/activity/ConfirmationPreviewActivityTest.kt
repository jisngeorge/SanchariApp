package com.sanchari.bus.ui.activity

import android.app.Instrumentation
import android.os.SystemClock
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.RatingBar
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sanchari.bus.R
import com.sanchari.bus.data.local.DatabaseManager
import com.sanchari.bus.data.manager.UserDataManager
import com.sanchari.bus.data.model.BusService
import com.sanchari.bus.databinding.ActivityBusDetailsBinding
import com.sanchari.bus.databinding.ActivityConfirmBinding
import com.sanchari.bus.databinding.ActivitySuggestEditBinding
import com.sanchari.bus.ui.helper.DraftManager
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.hamcrest.Matcher

@RunWith(AndroidJUnit4::class)
class ConfirmationPreviewActivityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Before
    fun initialize() = runBlocking { DatabaseManager.initializeDatabases(context) }

    @Test
    fun optionalContactFieldsAndEditsSurviveRecreation() {
        val json = JSONObject().put("type", "comment").put("serviceId", "preview-fixture")
            .put("commentText", "Please keep this comment").put("showUsername", false).toString()
        ActivityScenario.launch<ConfirmationActivity>(ConfirmationActivity.newIntent(context, json)).use { scenario ->
            awaitReady(scenario)
            scenario.onActivity { activity ->
                val binding = binding(activity)
                assertTrue(binding.textContributionPreview.text.contains("Please keep this comment"))
                assertEquals(activity.getString(R.string.contribution_review_notice), binding.textContributionReview.text)
                val scale = activity.resources.displayMetrics.scaledDensity
                assertEquals(18f, binding.textPreviewTitle.textSize / scale, 0.1f)
                for (text in listOf(binding.textContributionPreview, binding.textContributionReview,
                    binding.textContributorHelp, binding.textCommentPrivacy)) {
                    assertEquals(16f, text.textSize / scale, 0.1f)
                }
                assertEquals("Name", binding.inputName.hint.toString())
                assertEquals("Place", binding.editTextPlace.hint.toString())
                for (input in listOf(binding.inputName, binding.inputEmail, binding.inputPhone)) {
                    assertFalse(input.hint.toString().contains("*"))
                }
                assertTrue(binding.editTextName.isEnabled)
                assertTrue(binding.editTextName.text.isNullOrBlank())
                assertEquals(activity.getString(R.string.contributor_anonymous), binding.textCommentPrivacy.text)
                binding.editTextName.setText("Edited name")
                binding.editTextEmail.setText("edited@example.invalid")
                binding.editTextPhone.setText("1234567890")
                binding.editTextPlace.setText("Edited place")
            }
            scenario.recreate()
            awaitReady(scenario)
            scenario.onActivity { activity ->
                val binding = binding(activity)
                assertEquals("Edited name", binding.editTextName.text.toString())
                assertEquals("edited@example.invalid", binding.editTextEmail.text.toString())
                assertEquals("1234567890", binding.editTextPhone.text.toString())
                assertEquals("Edited place", binding.editTextPlace.text.toString())
                assertTrue(binding.editTextName.isEnabled)
                assertTrue(binding.editTextEmail.isEnabled)
                assertEquals(activity.getString(R.string.contributor_anonymous), binding.textCommentPrivacy.text)
                assertTrue(binding.textContributionPreview.text.contains("Please keep this comment"))
            }

        }
    }

    @Test
    fun blankPersonalFieldsCanSubmitAndOnlyEnteredDetailsAreSent() {
        val originalProfile = UserDataManager.getUser(context)
        try {
            for (provided in listOf(false, true)) {
                val json = JSONObject().put("type", "comment").put("serviceId", "preview-fixture")
                    .put("commentText", "A valid comment").put("showUsername", true).put("timestamp", 123L).toString()
                val captured = java.util.concurrent.atomic.AtomicReference<String>()
                ActivityScenario.launch<ConfirmationActivity>(ConfirmationActivity.newIntent(context, json)).use { scenario ->
                    awaitReady(scenario)
                    scenario.onActivity { activity ->
                        activity.uploadContribution = { captured.set(it); false }
                        activity.persistContribution = { false }
                        val binding = binding(activity)
                        binding.editTextName.setText(if (provided) "Entered name" else "")
                        binding.editTextEmail.setText(if (provided) "entered@example.invalid" else "")
                        binding.editTextPhone.setText("")
                        binding.editTextPlace.setText("")
                        binding.buttonApplySuggestion.performClick()
                    }
                    await { captured.get() != null }
                    val root = JSONObject(captured.get())
                    val by = root.getJSONObject("submittedBy")
                    assertEquals(if (provided) 3 else 1, by.length())
                    if (provided) {
                        assertEquals("Entered name", by.getString("name"))
                        assertEquals("entered@example.invalid", by.getString("email"))
                    }
                    assertEquals(originalProfile.uuid, root.getJSONObject("submittedBy").getString("uuid"))
                    assertEquals(provided, root.getJSONObject("suggestion").getBoolean("showUsername"))
                    assertEquals(123L, root.getJSONObject("suggestion").getLong("timestamp"))
                }
            }
        } finally {
            assertTrue(UserDataManager.saveUser(context, originalProfile))
        }
    }

    @Test
    fun commentsWithoutEnteredNamesShowAnonymousWithoutIdentifierWording() {
        val json = JSONObject().put("type", "comment").put("serviceId", "preview-fixture")
            .put("commentText", "No contact details needed").put("showUsername", true).toString()
        ActivityScenario.launch<ConfirmationActivity>(ConfirmationActivity.newIntent(context, json)).use { scenario ->
            awaitReady(scenario)
            scenario.onActivity { activity ->
                val binding = binding(activity)
                assertEquals(activity.getString(R.string.contributor_anonymous), binding.textCommentPrivacy.text)
                binding.editTextName.setText("Public name")
                assertEquals(activity.getString(R.string.contributor_named), binding.textCommentPrivacy.text)
                binding.editTextName.setText("")
                assertEquals(activity.getString(R.string.contributor_anonymous), binding.textCommentPrivacy.text)
            }
        }
    }

    @Test
    fun savedContactsAreNotPrefilledOrSentAndBlankFieldsDoNotEraseTheProfile() {
        val original = UserDataManager.getUser(context)
        val stored = original.copy(name = "Saved name", email = "saved@example.invalid", phone = "1234567890", place = "Saved place")
        assertTrue(UserDataManager.saveUser(context, stored))
        val captured = java.util.concurrent.atomic.AtomicReference<String>()
        try {
            ActivityScenario.launch<ConfirmationActivity>(ConfirmationActivity.newIntent(
                context, """{"type":"admin_message","messageText":"Valid message","timestamp":123}"""
            )).use { scenario ->
                awaitReady(scenario)
                scenario.onActivity { activity ->
                    val binding = binding(activity)
                    assertTrue(binding.editTextName.text.isNullOrBlank())
                    assertTrue(binding.editTextEmail.text.isNullOrBlank())
                    assertTrue(binding.editTextPhone.text.isNullOrBlank())
                    assertTrue(binding.editTextPlace.text.isNullOrBlank())
                    activity.uploadContribution = { captured.set(it); false }
                    activity.persistContribution = { false }
                    binding.buttonApplySuggestion.performClick()
                }
                await { captured.get() != null }
                assertEquals(1, JSONObject(captured.get()).getJSONObject("submittedBy").length())
                assertEquals(stored, UserDataManager.getUser(context))
            }
        } finally {
            assertTrue(UserDataManager.saveUser(context, original))
        }
    }

    @Test
    fun draftIsRemovedOnlyAfterUploadOrPendingPayloadPersistenceSucceeds() {
        for ((uploaded, persisted) in listOf(false to false, false to true, true to false)) {
            val json = schedule().put("type", "new_schedule")
            val busName = "Submission fixture ${java.util.UUID.randomUUID()}"
            json.getJSONObject("service").put("name", busName)
            json.getJSONObject("service").put("serviceId", "NEW-${java.util.UUID.randomUUID()}")
            val draft = DraftManager.saveDraft(context, json.toString())
            val uploadedPayload = java.util.concurrent.atomic.AtomicReference<String>()
            val pendingPayload = java.util.concurrent.atomic.AtomicReference<String>()
            try {
                ActivityScenario.launch<ConfirmationActivity>(
                    ConfirmationActivity.newIntent(context, json.toString())
                        .putExtra(ConfirmationActivity.EXTRA_DRAFT_FILE_NAME, draft)
                ).use { scenario ->
                    awaitReady(scenario)
                    scenario.onActivity { activity ->
                        activity.uploadContribution = { uploadedPayload.set(it); uploaded }
                        activity.persistContribution = { pendingPayload.set(it); persisted }
                        binding(activity).buttonApplySuggestion.performClick()
                    }
                    await { uploadedPayload.get() != null }
                    if (uploaded || persisted) {
                        await { DraftManager.getDrafts(context).none { it.fileName == draft } }
                    } else {
                        await {
                            var ready = false
                            scenario.onActivity { ready = binding(it).buttonApplySuggestion.isEnabled }
                            ready
                        }
                        assertTrue(DraftManager.getDrafts(context).any { it.fileName == draft })
                    }
                    if (uploaded) assertNull(pendingPayload.get())
                    else assertEquals(uploadedPayload.get(), pendingPayload.get())
                    assertEquals(1, JSONObject(uploadedPayload.get()).getJSONObject("submittedBy").length())
                }
            } finally {
                runBlocking { DraftManager.deleteDraftAsync(context, draft).await() }
                com.sanchari.bus.data.local.UserDatabaseHelper(context).use {
                    it.writableDatabase.delete("SubmissionLog", "busName = ?", arrayOf(busName))
                }
            }
        }
    }

    @Test
    fun newSchedulePreviewUsesGreenAdditionSpans() {
        ActivityScenario.launch<ConfirmationActivity>(
            ConfirmationActivity.newIntent(context, schedule().put("type", "new_schedule").toString())
        ).use { scenario ->
            awaitReady(scenario)
            scenario.onActivity { activity ->
                val text = binding(activity).textContributionPreview.text as Spanned
                assertTrue(text.contains("+ Start - 11:30 PM"))
                assertTrue(text.contains("+ End - 12:15 AM"))
                fun colorAt(index: Int) = text.getSpans(index, index + 1, ForegroundColorSpan::class.java)
                    .last().foregroundColor
                val green = ContextCompat.getColor(activity, R.color.preview_added)
                assertEquals(green, colorAt(0))
                assertEquals(ContextCompat.getColor(activity, R.color.brand_primary),
                    colorAt(text.indexOf("Preview bus")))
                assertEquals(ContextCompat.getColor(activity, R.color.preview_bus_type),
                    colorAt(text.indexOf("Ordinary")))
                assertEquals(green, colorAt(text.indexOf("Start")))
            }
        }
    }

    @Test
    fun backFromRealConfirmationKeepsTheEditorAndDraftForBothBackActions() {
        for (toolbar in listOf(false, true)) {
            val file = DraftManager.saveDraft(context, schedule().toString())
            val monitor = Instrumentation.ActivityMonitor(ConfirmationActivity::class.java.name, null, false)
            instrumentation.addMonitor(monitor)
            try {
                ActivityScenario.launch<SuggestEditActivity>(
                    SuggestEditActivity.newIntentForNew(context).putExtra(SuggestEditActivity.EXTRA_LOAD_DRAFT_FILE, file)
                ).use { scenario ->
                    var ready = false
                    await {
                        scenario.onActivity { ready = editorBinding(it).editTextServiceType.adapter != null }
                        ready
                    }
                    scenario.onActivity {
                        editorBinding(it).editTextServiceName.setText("Latest bus name")
                        editorBinding(it).editTextEditNotes.setText("Latest notes")
                        editorBinding(it).buttonApplyChanges.performClick()
                    }
                    val confirmation = instrumentation.waitForMonitorWithTimeout(monitor, 5_000) as ConfirmationActivity?
                    assertNotNull(confirmation)
                    if (toolbar) {
                        onView(withContentDescription(androidx.appcompat.R.string.abc_action_bar_up_description)).perform(click())
                    } else instrumentation.runOnMainSync {
                        requireNotNull(confirmation).onBackPressedDispatcher.onBackPressed()
                    }
                    await {
                        var resumed = false
                        scenario.onActivity {
                            resumed = it.hasWindowFocus()
                            assertFalse(it.isFinishing)
                            assertEquals(file, it.currentDraftFileName)
                            assertEquals("Latest bus name", editorBinding(it).editTextServiceName.text.toString())
                            assertEquals("Latest notes", editorBinding(it).editTextEditNotes.text.toString())
                            val stops = editorBinding(it).recyclerViewStopsEditor.adapter
                            assertEquals(2, stops?.itemCount)
                        }
                        resumed
                    }
                    scenario.recreate()
                    scenario.onActivity {
                        assertEquals("Latest bus name", editorBinding(it).editTextServiceName.text.toString())
                        assertEquals("Latest notes", editorBinding(it).editTextEditNotes.text.toString())
                    }
                }
                runBlocking { DraftManager.deleteDraftAsync(context, DraftManager.newDraftFileName()).await() }
                val saved = JSONObject(DraftManager.getDrafts(context).single { it.fileName == file }.jsonPayload)
                assertEquals("Latest bus name", saved.getJSONObject("service").getString("name"))
                assertEquals("Latest notes", saved.getString("editNotes"))
            } finally {
                instrumentation.removeMonitor(monitor)
                runBlocking { DraftManager.deleteDraftAsync(context, file).await() }
            }
        }
    }

    @Test
    fun backFromRatingAndCommentConfirmationRestoresInputDialogs() {
        val service = BusService("preview-fixture", "Preview bus", "Ordinary", true, 0, "", "")
        val monitor = Instrumentation.ActivityMonitor(ConfirmationActivity::class.java.name, null, false)
        instrumentation.addMonitor(monitor)
        try {
            ActivityScenario.launch<BusDetailsActivity>(BusDetailsActivity.newIntent(context, service)).use { scenario ->
                scenario.onActivity {
                    ActivityBusDetailsBinding.bind(it.findViewById<ViewGroup>(android.R.id.content).getChildAt(0))
                        .buttonAddRating.performClick()
                }
                onView(withId(R.id.ratingBarPunctualitySubmit)).perform(setRating(4.5f))
                onView(withId(R.id.ratingBarDriveSubmit)).perform(setRating(3f))
                onView(withId(R.id.ratingBarBehaviourSubmit)).perform(setRating(5f))
                onView(withText("Submit")).perform(click())
                val ratingConfirmation = instrumentation.waitForMonitorWithTimeout(monitor, 5_000) as ConfirmationActivity?
                assertNotNull(ratingConfirmation)
                instrumentation.runOnMainSync { requireNotNull(ratingConfirmation).onBackPressedDispatcher.onBackPressed() }
                onView(withId(R.id.ratingBarPunctualitySubmit)).check { view, error ->
                    if (error != null) throw error
                    assertEquals(4.5f, (view as RatingBar).rating, 0f)
                }
                onView(withId(R.id.ratingBarDriveSubmit)).check { view, error ->
                    if (error != null) throw error
                    assertEquals(3f, (view as RatingBar).rating, 0f)
                }
                onView(withId(R.id.ratingBarBehaviourSubmit)).check { view, error ->
                    if (error != null) throw error
                    assertEquals(5f, (view as RatingBar).rating, 0f)
                }
                onView(withText("Cancel")).perform(click())
                scenario.onActivity {
                    ActivityBusDetailsBinding.bind(it.findViewById<ViewGroup>(android.R.id.content).getChildAt(0))
                        .buttonAddComment.performClick()
                }
                onView(withId(R.id.editTextComment)).perform(replaceText("My edited comment"))
                onView(withId(R.id.checkboxAnonymous)).perform(click())
                instrumentation.addMonitor(monitor)
                onView(withText("Submit")).perform(click())
                val commentConfirmation = instrumentation.waitForMonitorWithTimeout(monitor, 5_000) as ConfirmationActivity?
                assertNotNull(commentConfirmation)
                instrumentation.runOnMainSync { requireNotNull(commentConfirmation).onBackPressedDispatcher.onBackPressed() }
                onView(withId(R.id.editTextComment)).check(matches(withText("My edited comment")))
                onView(withId(R.id.checkboxAnonymous)).check(matches(isChecked()))
                onView(withText("Cancel")).perform(click())
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun setRating(value: Float) = object : ViewAction {
        override fun getConstraints(): Matcher<View> = isAssignableFrom(RatingBar::class.java)
        override fun getDescription() = "Set rating to $value"
        override fun perform(uiController: UiController, view: View) {
            (view as RatingBar).rating = value
            uiController.loopMainThreadUntilIdle()
        }
    }

    private fun schedule() = JSONObject().apply {
        put("type", "edit_schedule")
        put("service", JSONObject().put("serviceId", "preview-fixture").put("name", "Preview bus")
            .put("type", "Ordinary").put("isRunning", true))
        put("stops", JSONArray()
            .put(JSONObject().put("stopId", "NEW").put("locationName", "Start").put("scheduledTime", "23:30"))
            .put(JSONObject().put("stopId", "NEW").put("locationName", "End").put("scheduledTime", "00:15")))
        put("editNotes", "")
    }

    private fun binding(activity: ConfirmationActivity) =
        ActivityConfirmBinding.bind(activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0))

    private fun editorBinding(activity: SuggestEditActivity) =
        ActivitySuggestEditBinding.bind(activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0))

    private fun awaitReady(scenario: ActivityScenario<ConfirmationActivity>) = await {
        var ready = false
        scenario.onActivity { ready = binding(it).buttonApplySuggestion.isEnabled }
        ready
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(25)
        }
        fail("Confirmation did not reach the expected state")
    }
}
