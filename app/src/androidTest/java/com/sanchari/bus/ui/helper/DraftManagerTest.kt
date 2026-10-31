package com.sanchari.bus.ui.helper

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DraftManagerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val files = mutableSetOf<String>()

    @After
    fun cleanup() = runBlocking {
        files.forEach { DraftManager.deleteDraftAsync(context, it).await() }
    }

    @Test
    fun savingTheSameServiceReusesItsDraftDespiteNameTypeAndFilenameChanges() {
        val id = UUID.randomUUID().toString()
        val first = DraftManager.saveDraft(context, payload(id, "Original", "Type")).also(files::add)
        val updated = DraftManager.saveDraft(context, payload(id, "Renamed", "New type"),
            DraftManager.newDraftFileName()).also(files::add)
        assertEquals(first, updated)
        val draft = requireNotNull(DraftManager.getDraftForService(context, id))
        assertEquals(first.removeSuffix(".json"), draft.draftId)
        assertEquals("Renamed", JSONObject(draft.jsonPayload).getJSONObject("service").getString("name"))
        assertEquals(1, DraftManager.getDrafts(context).count {
            JSONObject(it.jsonPayload).getJSONObject("service").getString("serviceId") == id
        })
    }

    @Test
    fun distinctNewServicesWithTheSameNameAndTypeKeepSeparateDrafts() {
        val firstId = "NEW-${UUID.randomUUID()}"
        val secondId = "NEW-${UUID.randomUUID()}"
        val first = DraftManager.saveDraft(context, payload(firstId)).also(files::add)
        val second = DraftManager.saveDraft(context, payload(secondId)).also(files::add)
        assertNotEquals(first, second)
        assertEquals(first, DraftManager.getDraftForService(context, firstId)?.fileName)
        assertEquals(second, DraftManager.getDraftForService(context, secondId)?.fileName)
    }

    @Test
    fun queuedSavesForOneServiceResolveToOneFileWithTheLatestChanges() = runBlocking {
        val id = UUID.randomUUID().toString()
        val first = DraftManager.saveDraftAsync(context, payload(id, "First"), DraftManager.newDraftFileName())
        val second = DraftManager.saveDraftAsync(context, payload(id, "Latest"), DraftManager.newDraftFileName())
        val firstFile = first.await().also(files::add)
        assertEquals(firstFile, second.await().also(files::add))
        val draft = requireNotNull(DraftManager.getDraftForService(context, id))
        assertEquals("Latest", JSONObject(draft.jsonPayload).getJSONObject("service").getString("name"))
    }

    private fun payload(id: String, name: String = "Same bus", type: String = "Same type") =
        JSONObject().put("service", JSONObject().put("serviceId", id).put("name", name).put("type", type))
            .put("stops", JSONArray()).toString()
}
