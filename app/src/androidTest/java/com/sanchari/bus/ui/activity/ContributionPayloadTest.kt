package com.sanchari.bus.ui.activity

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sanchari.bus.data.local.SuggestionStorageManager
import com.sanchari.bus.data.model.User
import com.sanchari.bus.ui.helper.ContributionPayload
import com.sanchari.bus.ui.helper.UploadManager
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ContributionPayloadTest {
    private val profile = User("persisted-id", " Name ", "test@example.invalid", "1234567890", " Place ")

    @Test
    fun wrappedPayloadUsesTwoSpaceIndentationThroughoutNestedObjectsAndStops() {
        val suggestion = """{"type":"new_schedule","service":{"serviceId":"NEW-fixture"},"stops":[{"stopId":"NEW","locationName":"Start","scheduledTime":"05:45","stopOrder":1}],"timestamp":123}"""
        val payload = ContributionPayload.wrap(suggestion, profile)
        assertTrue(payload.startsWith("{\n  \"submittedBy\": {"))
        assertTrue(payload.contains("\n  \"suggestion\": {"))
        assertTrue(payload.contains("\n    \"service\": {"))
        assertTrue(payload.contains("\n      \"serviceId\": \"NEW-fixture\""))
        assertTrue(payload.contains("\n    \"stops\": [\n      {"))
        assertTrue(payload.contains("\n        \"scheduledTime\": \"05:45\""))
        assertTrue(payload.endsWith("\n}"))
        assertEquals(JSONObject(suggestion).toString(), JSONObject(payload).getJSONObject("suggestion").toString())
    }

    @Test
    fun everyContributionCanOmitAllPersonalFieldsWhileKeepingItsOriginalSuggestion() {
        for (type in listOf("rating", "comment", "new_schedule", "edit_schedule",
            "running_status_suggestion", "admin_message")) {
            val original = JSONObject().put("type", type).put("timestamp", 1234567890L)
                .put("serviceId", "unchanged-id")
            if (type == "comment") original.put("showUsername", false)
            val root = JSONObject(ContributionPayload.wrap(
                original.toString(), profile.copy(name = "", email = "", phone = "", place = "")))
            val contributor = root.getJSONObject("submittedBy")
            assertEquals(1, contributor.length())
            assertEquals(profile.uuid, contributor.getString("uuid"))
            val suggestion = root.getJSONObject("suggestion")
            assertEquals(original.toString(), suggestion.toString())
        }
    }

    @Test
    fun providedDetailsIncludeOnlyNonblankStringValues() {
        val root = JSONObject(ContributionPayload.wrap(
            """{"type":"rating","timestamp":123}""", profile.copy(email = "", place = " ")))
        val by = root.getJSONObject("submittedBy")
        assertEquals("Name", by.get("name"))
        assertEquals("1234567890", by.get("phone"))
        assertFalse(by.has("email"))
        assertFalse(by.has("place"))
        assertTrue(by.get("phone") is String)
    }

    @Test
    fun commentNameChoiceIsRetainedAndNamedCommentsRequireAName() {
        for (requested in listOf(false, true)) {
            val suggestion = JSONObject().put("type", "comment").put("showUsername", requested)
            val withName = JSONObject(ContributionPayload.wrap(suggestion.toString(), profile))
            assertEquals(requested, withName.getJSONObject("suggestion").getBoolean("showUsername"))
            if (requested) {
                try {
                    ContributionPayload.wrap(suggestion.toString(), profile.copy(name = " "))
                    fail("Named comments must not silently become anonymous")
                } catch (_: IllegalArgumentException) { }
            } else {
                val noName = JSONObject(ContributionPayload.wrap(suggestion.toString(), profile.copy(name = "")))
                assertFalse(noName.getJSONObject("suggestion").getBoolean("showUsername"))
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyUuidCannotBeUploaded() {
        ContributionPayload.wrap("""{"type":"rating"}""", profile.copy(uuid = ""))
    }

    @Test
    fun offlinePersistenceRetainsTheExactWrappedPayloadDespiteLaterProfileChanges() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "pending-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = directory
        }
        val payload = ContributionPayload.wrap(
            """{"type":"comment","serviceId":"original","timestamp":123,"showUsername":true}""", profile)
        try {
            assertTrue(SuggestionStorageManager.saveSuggestion(context, payload))
            val file = SuggestionStorageManager.getPendingSuggestions(context).single()
            assertEquals(payload, file.readText())
            assertEquals("persisted-id", JSONObject(file.readText()).getJSONObject("submittedBy").getString("uuid"))
            assertEquals(123L, JSONObject(file.readText()).getJSONObject("suggestion").getLong("timestamp"))
            val sent = mutableListOf<String>()
            runBlocking {
                UploadManager(context) { sent.add(it); false }.retryPendingUploads()
                assertEquals(payload, file.readText())
                UploadManager(context) { sent.add(it); true }.retryPendingUploads()
            }
            assertEquals(listOf(payload, payload), sent)
            assertFalse(file.exists())
        } finally {
            SuggestionStorageManager.getPendingSuggestions(context).forEach {
                SuggestionStorageManager.deleteSuggestion(it)
            }
            File(directory, "suggestions").delete()
            directory.delete()
        }
    }
}
