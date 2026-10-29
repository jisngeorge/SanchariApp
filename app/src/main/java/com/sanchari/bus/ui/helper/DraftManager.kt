package com.sanchari.bus.ui.helper

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.sanchari.bus.data.model.SavedDraft
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

object DraftManager {
    private const val DRAFTS_DIR = "suggest_edits_drafts"
    private const val TAG = "DraftManager"
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val persistenceMutex = Mutex()

    fun newDraftFileName(): String = "draft_${UUID.randomUUID()}.json"

    // Queue on the calling thread so lifecycle saves and deletions retain their order.
    // The application-owned scope lets a bounded write finish after its activity exits.
    fun saveDraftAsync(context: Context, jsonPayload: String, fileName: String): Deferred<String> {
        val appContext = context.applicationContext
        return persistenceScope.async(start = CoroutineStart.UNDISPATCHED) {
            persistenceMutex.withLock {
                withContext(Dispatchers.IO) {
                    try {
                        saveDraft(appContext, jsonPayload, fileName)
                    } catch (e: IOException) {
                        Log.e(TAG, "Failed to save draft", e)
                        throw e
                    } catch (e: JSONException) {
                        Log.e(TAG, "Failed to encode draft", e)
                        throw e
                    }
                }
            }
        }
    }

    fun deleteDraftAsync(context: Context, fileName: String): Deferred<Unit> {
        val appContext = context.applicationContext
        return persistenceScope.async(start = CoroutineStart.UNDISPATCHED) {
            persistenceMutex.withLock {
                withContext(Dispatchers.IO) {
                    try {
                        deleteDraft(appContext, fileName)
                    } catch (e: IOException) {
                        Log.e(TAG, "Failed to delete draft", e)
                        throw e
                    }
                }
            }
        }
    }

    /**
     * Saves the JSON payload to internal storage. Uses a UUID to ensure
     * edits to the bus name or type do not affect the draft's unique identity.
     */
    @Synchronized
    fun saveDraft(context: Context, jsonPayload: String, existingFileName: String? = null): String {
        val dir = context.getDir(DRAFTS_DIR, Context.MODE_PRIVATE)

        // Fallback to UUID if this is a brand new draft
        val fileName = existingFileName ?: newDraftFileName()
        val file = File(dir, fileName)

        // Inject the draftId directly into the JSON root to act as a permanent unique identifier
        val root = JSONObject(jsonPayload)
        val draftId = fileName.removeSuffix(".json")
        root.put("draftId", draftId)

        val atomicFile = AtomicFile(file)
        val output = atomicFile.startWrite()
        try {
            output.write(root.toString(2).toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(output)
        } catch (e: IOException) {
            atomicFile.failWrite(output)
            throw e
        }
        return fileName
    }

    /**
     * Reads all drafts, parses the JSON to extract the Name, Type, and First Stop,
     * and returns a list formatted for the UI.
     */
    @Synchronized
    fun getDrafts(context: Context): List<SavedDraft> {
        val dir = context.getDir(DRAFTS_DIR, Context.MODE_PRIVATE)
        val files = dir.listFiles()?.mapNotNull {
            when {
                it.extension == "json" -> it
                it.name.endsWith(".json.bak") -> File(dir, it.name.removeSuffix(".bak"))
                else -> null
            }
        }?.distinctBy { it.name } ?: return emptyList()

        return files.mapNotNull { file ->
            try {
                val json = AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
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
            } catch (e: IOException) {
                Log.e(TAG, "Failed to read draft", e)
                null
            } catch (e: JSONException) {
                Log.e(TAG, "Failed to parse draft", e)
                null
            }
        }.sortedByDescending { it.lastModifiedTime } // Sort by actual modification time since filenames are now UUIDs
    }

    /**
     * Deletes the draft when the user discards or submits it.
     */
    @Synchronized
    fun deleteDraft(context: Context, fileName: String) {
        val dir = context.getDir(DRAFTS_DIR, Context.MODE_PRIVATE)
        val file = File(dir, fileName)
        for (draftFile in listOf(file, File(dir, "$fileName.bak"), File(dir, "$fileName.new"))) {
            if (draftFile.exists() && !draftFile.delete()) {
                throw IOException("Could not delete draft file")
            }
        }
    }
}