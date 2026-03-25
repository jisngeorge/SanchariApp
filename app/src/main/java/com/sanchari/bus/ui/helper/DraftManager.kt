package com.sanchari.bus.ui.helper

import android.content.Context
import com.sanchari.bus.data.model.SavedDraft
import org.json.JSONObject
import java.io.File
import java.util.UUID

object DraftManager {
    private const val DRAFTS_DIR = "suggest_edits_drafts"

    /**
     * Saves the JSON payload to internal storage. Uses a UUID to ensure
     * edits to the bus name or type do not affect the draft's unique identity.
     */
    fun saveDraft(context: Context, jsonPayload: String, existingFileName: String? = null): String {
        val dir = context.getDir(DRAFTS_DIR, Context.MODE_PRIVATE)

        // Fallback to UUID if this is a brand new draft
        val fileName = existingFileName ?: "draft_${UUID.randomUUID()}.json"
        val file = File(dir, fileName)

        // Inject the draftId directly into the JSON root to act as a permanent unique identifier
        val root = JSONObject(jsonPayload)
        val draftId = fileName.removeSuffix(".json")
        root.put("draftId", draftId)

        file.writeText(root.toString(2))
        return fileName
    }

    /**
     * Reads all drafts, parses the JSON to extract the Name, Type, and First Stop,
     * and returns a list formatted for the UI.
     */
    fun getDrafts(context: Context): List<SavedDraft> {
        val dir = context.getDir(DRAFTS_DIR, Context.MODE_PRIVATE)
        val files = dir.listFiles() ?: return emptyList()

        return files.mapNotNull { file ->
            try {
                val json = file.readText()
                val root = JSONObject(json)
                val service = root.getJSONObject("service")

                val name = service.optString("name", "Unnamed Bus").takeIf { it.isNotBlank() } ?: "Unnamed Bus"
                val type = service.optString("type", "Unknown Type").takeIf { it.isNotBlank() } ?: "Unknown Type"

                val stopsArray = root.optJSONArray("stops")
                val firstStop = if (stopsArray != null && stopsArray.length() > 0) {
                    stopsArray.getJSONObject(0).optString("locationName", "Unknown Stop").takeIf { it.isNotBlank() } ?: "Unknown Stop"
                } else {
                    "No stops"
                }

                // Display name sticks to the current approach
                val displayName = "$name ($type) - Starts: $firstStop"
                val draftId = root.optString("draftId", file.nameWithoutExtension)

                SavedDraft(
                    draftId = draftId,
                    fileName = file.name,
                    jsonPayload = json,
                    displayName = displayName,
                    lastModifiedTime = file.lastModified()
                )
            } catch (e: Exception) {
                null // Skip corrupted files
            }
        }.sortedByDescending { it.lastModifiedTime } // Sort by actual modification time since filenames are now UUIDs
    }

    /**
     * Deletes the draft when the user discards or submits it.
     */
    fun deleteDraft(context: Context, fileName: String) {
        val dir = context.getDir(DRAFTS_DIR, Context.MODE_PRIVATE)
        val file = File(dir, fileName)
        if (file.exists()) {
            file.delete()
        }
    }
}