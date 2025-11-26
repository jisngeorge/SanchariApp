package com.sanchari.bus

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SuggestionStorageManager {

    private const val TAG = "SuggestionStorageManager"
    private const val SUGGESTIONS_DIR = "suggestions"

    /**
     * Saves the given JSON string to a new file in the app's internal storage.
     */
    fun saveSuggestion(context: Context, jsonPayload: String): Boolean {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "suggestion_$timestamp.json"

        return try {
            val suggestionsDir = File(context.filesDir, SUGGESTIONS_DIR)
            if (!suggestionsDir.exists()) {
                suggestionsDir.mkdirs()
            }

            val file = File(suggestionsDir, fileName)
            file.writeText(jsonPayload)

            Log.i(TAG, "Suggestion saved successfully to: ${file.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error saving suggestion to internal storage", e)
            false
        }
    }

    /**
     * Returns a list of all pending suggestion JSON files.
     */
    fun getPendingSuggestions(context: Context): List<File> {
        val suggestionsDir = File(context.filesDir, SUGGESTIONS_DIR)
        if (!suggestionsDir.exists()) {
            return emptyList()
        }
        return suggestionsDir.listFiles()?.filter { it.isFile && it.name.endsWith(".json") } ?: emptyList()
    }

    /**
     * Deletes a suggestion file (e.g., after successful upload).
     */
    fun deleteSuggestion(file: File): Boolean {
        return try {
            if (file.exists()) {
                file.delete()
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting suggestion file", e)
            false
        }
    }
}