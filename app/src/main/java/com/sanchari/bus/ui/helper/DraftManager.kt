package com.sanchari.bus.ui.helper

import android.content.Context
import com.sanchari.bus.data.model.SavedDraft
import org.json.JSONObject
import java.io.File

object DraftManager {
    private const val DRAFTS_DIR = "suggest_edits_drafts"

    /**
     * Saves the JSON payload to internal storage.
     */
    fun saveDraft(context: Context, jsonPayload: String, existingFileName: String? = null): String {
        val dir = context.getDir(DRAFTS_DIR, Context.MODE_PRIVATE)
        val fileName = existingFileName ?: "draft_${System.currentTimeMillis()}.json"
        val file = File(dir, fileName)
        file.writeText(jsonPayload)
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

                // Format: KSRTC Fast Passenger (Trivandrum - ...)
                val displayName = "$name ($type) - Starts: $firstStop"

                SavedDraft(file.name, json, displayName)
            } catch (e: Exception) {
                null // Skip corrupted files
            }
        }.sortedByDescending { it.fileName } // Sort newest first based on timestamp
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