package com.sanchari.bus

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.BufferedSink
import okio.buffer
import okio.sink
import java.io.File
import java.io.IOException

/**
 * Manages all network operations, such as fetching the
 * version.json file and downloading new database files.
 */
object NetworkManager {

    private const val TAG = "NetworkManager"

    // A single, reusable OkHttp client instance
    private val client = OkHttpClient()

    // A single, reusable JSON parser instance
    private val jsonParser = Json { ignoreUnknownKeys = true }

    // TODO: Move this to a central configuration or build config
    private const val VERSION_JSON_URL = "https://your-static-host.com/path/to/version.json"

    /**
     * Fetches and parses the version.json file from the server.
     * This is a suspending function and must be called from a coroutine.
     *
     * @return A ServerVersionInfo object if successful, or null if an error occurs.
     */
    suspend fun fetchVersionInfo(): ServerVersionInfo? {
        // Ensure this runs on the IO dispatcher for network operations
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(VERSION_JSON_URL)
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        handleUnsuccessfulResponse(response)
                        return@withContext null
                    }

                    val responseBody = response.body
                    if (responseBody == null) {
                        Log.e(TAG, "Response body was null for version.json")
                        return@withContext null
                    }

                    // Parse the JSON string into our data class
                    return@withContext parseJson(responseBody.string())
                }
            } catch (e: IOException) {
                // This catches network failures (no connection, DNS issues)
                Log.e(TAG, "Network failure when fetching version.json", e)
                return@withContext null
            } catch (e: Exception) {
                // Catches any other unexpected errors
                Log.e(TAG, "Unexpected error fetching version.json", e)
                return@withContext null
            }
        }
    }

    /**
     * Downloads a file from the given URL and saves it to the target file.
     * This is a suspending function and must be called from a coroutine.
     *
     * @param url The URL to download from.
     * @param targetFile The file to save the download to.
     * @return True if download was successful, false otherwise.
     */
    suspend fun downloadFile(url: String, targetFile: File): Boolean {
        return withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        handleUnsuccessfulResponse(response)
                        return@withContext false
                    }

                    val body = response.body
                    if (body == null) {
                        Log.e(TAG, "Response body was null for file download: $url")
                        return@withContext false
                    }

                    // Write the file to disk
                    try {
                        targetFile.sink().buffer().use { sink: BufferedSink ->
                            sink.writeAll(body.source())
                        }
                        Log.i(TAG, "File downloaded successfully: ${targetFile.name}")
                        return@withContext true
                    } catch (e: IOException) {
                        Log.e(TAG, "Failed to write downloaded file to disk: ${targetFile.name}", e)
                        // Clean up the potentially corrupted temp file
                        if (targetFile.exists()) {
                            targetFile.delete()
                        }
                        return@withContext false
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Network failure during file download: $url", e)
                return@withContext false
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error during file download: $url", e)
                return@withContext false
            }
        }
    }

    /**
     * Parses the JSON response string into a ServerVersionInfo object.
     */
    private fun parseJson(jsonString: String): ServerVersionInfo? {
        return try {
            jsonParser.decodeFromString<ServerVersionInfo>(jsonString)
        } catch (e: kotlinx.serialization.SerializationException) {
            Log.e(TAG, "Failed to parse version.json. Check JSON format.", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Illegal argument in JSON parsing.", e)
            null
        }
    }

    /**
     * Logs detailed information about an unsuccessful HTTP response.
     */
    private fun handleUnsuccessfulResponse(response: Response) {
        Log.e(
            TAG,
            "Failed to fetch version.json. " +
                    "Code: ${response.code}, " +
                    "Message: ${response.message}"
        )
        // You could also log response.body?.string() here,
        // but be careful as it might be large.
    }
}

