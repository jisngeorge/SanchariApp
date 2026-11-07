package com.sanchari.bus.data.local

import android.content.Context
import android.util.Log
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.util.UUID
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SuggestionStorageManager {

    private const val TAG = "SuggestionStorageManager"
    private const val SUGGESTIONS_DIR = "suggestions"

    /**
     * Saves the given JSON string to a new file in the app's internal storage.
     */
    @Synchronized
    fun saveSuggestion(context: Context, jsonPayload: String): Boolean {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "suggestion_${timestamp}_${UUID.randomUUID()}.json"

        return try {
            val suggestionsDir = File(context.filesDir, SUGGESTIONS_DIR)
            if (!suggestionsDir.isDirectory && !suggestionsDir.mkdirs()) throw IOException("Pending submission directory unavailable")

            val file = AtomicFile(File(suggestionsDir, fileName))
            val output = file.startWrite()
            try {
                output.write(jsonPayload.toByteArray(Charsets.UTF_8))
                file.finishWrite(output)
            } catch (e: IOException) {
                file.failWrite(output)
                throw e
            }

            Log.i(TAG, "Suggestion saved for retry")
            true
        } catch (e: IOException) {
            Log.e(TAG, "Error saving suggestion to internal storage", e)
            false
        }
    }

    /**
     * Returns a list of all pending suggestion JSON files.
     */
    @Synchronized
    fun getPendingSuggestions(context: Context): List<File> {
        val suggestionsDir = File(context.filesDir, SUGGESTIONS_DIR)
        if (!suggestionsDir.exists()) {
            return emptyList()
        }
        val files = suggestionsDir.listFiles() ?: throw IOException("Could not list pending submissions")
        return files.filter { it.isFile && it.name.endsWith(".json") }.sortedByDescending { it.lastModified() }
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