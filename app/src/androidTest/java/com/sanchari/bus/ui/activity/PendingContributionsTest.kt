package com.sanchari.bus.ui.activity

import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sanchari.bus.R
import com.sanchari.bus.data.local.DatabaseManager
import com.sanchari.bus.data.local.SuggestionStorageManager
import com.sanchari.bus.data.local.UserDatabaseHelper
import com.sanchari.bus.data.model.BusService
import com.sanchari.bus.data.model.RouteSearchResult
import com.sanchari.bus.data.model.User
import com.sanchari.bus.ui.helper.ContributionPayload
import com.sanchari.bus.ui.helper.SavedContributionsDialog
import com.sanchari.bus.ui.helper.UploadManager
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PendingContributionsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun distinctPendingFilesNeverOverwriteAndOnlySuccessfulUploadsDisappear() {
        val directory = File(context.cacheDir, "pending-history-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = directory
        }
        val payloads = listOf(
            ContributionPayload.wrap("""{"type":"comment","commentText":"First","showUsername":false,"timestamp":123}""",
                User("fixture", "", "", "", "")),
            ContributionPayload.wrap("""{"type":"admin_message","messageText":"Second","timestamp":124}""",
                User("fixture", "", "", "", ""))
        )
        try {
            payloads.forEach { assertTrue(SuggestionStorageManager.saveSuggestion(isolated, it)) }
            val files = SuggestionStorageManager.getPendingSuggestions(isolated)
            assertEquals(2, files.size)
            assertEquals(payloads.toSet(), files.map { it.readText() }.toSet())
            assertEquals(2, SavedContributionsDialog.pendingEntries(isolated).size)
            runBlocking { UploadManager(isolated) { false }.retryPendingUploads() }
            assertEquals(2, SavedContributionsDialog.pendingEntries(isolated).size)
            runBlocking { UploadManager(isolated) { it == payloads.first() }.retryPendingUploads() }
            assertEquals(payloads.last(), SuggestionStorageManager.getPendingSuggestions(isolated).single().readText())
            assertEquals(1, SavedContributionsDialog.pendingEntries(isolated).size)
            runBlocking { UploadManager(isolated) { true }.retryPendingUploads() }
            assertTrue(SavedContributionsDialog.pendingEntries(isolated).isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun pendingScheduleIsVisibleReadOnlyWithPreviewAndRetryExplanation() {
        assertTrue("Do not retry production pending submissions",
            SuggestionStorageManager.getPendingSuggestions(context).isEmpty())
        runBlocking { DatabaseManager.initializeDatabases(context) }
        val name = "Pending fixture ${UUID.randomUUID()}"
        val suggestion = """{"type":"new_schedule","service":{"serviceId":"NEW-fixture","name":"$name","type":"ORDINARY"},"stops":[{"stopId":"NEW","locationName":"Start","scheduledTime":"08:00","stopOrder":1}],"timestamp":123}"""
        val payload = ContributionPayload.wrap(suggestion, User("fixture", "", "", "", ""))
        assertTrue(SuggestionStorageManager.saveSuggestion(context, payload))
        val file = SuggestionStorageManager.getPendingSuggestions(context).single()
        val intent = Intent(context, SearchResultsActivity::class.java).apply {
            putParcelableArrayListExtra(SearchResultsActivity.EXTRA_ROUTE_RESULTS, arrayListOf(RouteSearchResult(
                BusService("fixture", "Fixture", "ORDINARY", true, 0L, "08:00", "09:00"),
                "Start", "End", 1, 2)))
            putExtra(SearchResultsActivity.EXTRA_FROM_STOP, "Start")
            putExtra(SearchResultsActivity.EXTRA_TO_STOP, "End")
        }
        try {
            ActivityScenario.launch<SearchResultsActivity>(intent).use { scenario ->
                scenario.onActivity { SavedContributionsDialog.show(it) { fail("Pending entry must not open editor") } }
                onData(equalTo(context.getString(R.string.pending_upload_label, name))).inRoot(isDialog()).perform(click())
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val deadline = System.currentTimeMillis() + 5_000
                while (System.currentTimeMillis() < deadline) {
                    instrumentation.waitForIdleSync()
                    if (!instrumentation.uiAutomation.rootInActiveWindow
                            ?.findAccessibilityNodeInfosByText(context.getString(R.string.pending_upload_help)).isNullOrEmpty()) break
                    Thread.sleep(20)
                }
                onView(withId(android.R.id.message)).inRoot(isDialog())
                    .check(matches(withText(containsString(context.getString(R.string.pending_upload_help)))))
                    .check(matches(withText(containsString("Start"))))
                assertEquals(payload, file.readText())
                onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
                runBlocking { UploadManager(context) { false }.retryPendingUploads() }
                assertEquals(payload, file.readText())
                assertTrue(SavedContributionsDialog.pendingEntries(context).any { it.file == file })
                runBlocking { UploadManager(context) { true }.retryPendingUploads() }
                assertFalse(file.exists())
                assertTrue(SavedContributionsDialog.pendingEntries(context).isEmpty())
            }
        } finally {
            if (file.exists()) SuggestionStorageManager.deleteSuggestion(file)
            UserDatabaseHelper(context).use { it.writableDatabase.delete("SubmissionLog", "busName = ?", arrayOf(name)) }
        }
    }
}
