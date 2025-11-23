package com.sanchari.bus

import android.content.Context // Added import
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
 * Manages all network operations.
 */
object NetworkManager {

    private const val TAG = "NetworkManager"
    private val client = OkHttpClient()
    private val jsonParser = Json { ignoreUnknownKeys = true }

    // --- DEFAULT HARDCODED URL (Fallback) ---
    // TODO: Replace this with your GitHub Pages URL
    private const val DEFAULT_VERSION_JSON_URL = "https://your-github-username.github.io/your-repo/version.json"

    /**
     * Fetches version.json.
     * Checks LocalVersionManager for a dynamic URL first.
     */
    suspend fun fetchVersionInfo(context: Context): ServerVersionInfo? {
        return withContext(Dispatchers.IO) {

            // 1. Determine which URL to use
            val dynamicUrl = LocalVersionManager.getVersionsUrl(context)
            val targetUrl = if (!dynamicUrl.isNullOrBlank()) {
                Log.d(TAG, "Using dynamic versions URL: $dynamicUrl")
                dynamicUrl
            } else {
                Log.d(TAG, "Using default versions URL: $DEFAULT_VERSION_JSON_URL")
                DEFAULT_VERSION_JSON_URL
            }

            val request = Request.Builder()
                .url(targetUrl)
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

                    return@withContext parseJson(responseBody.string())
                }
            } catch (e: IOException) {
                Log.e(TAG, "Network failure when fetching version.json", e)
                return@withContext null
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error fetching version.json", e)
                return@withContext null
            }
        }
    }

    /**
     * Downloads a file from the given URL and saves it to the target file.
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

                    try {
                        targetFile.sink().buffer().use { sink: BufferedSink ->
                            sink.writeAll(body.source())
                        }
                        Log.i(TAG, "File downloaded successfully: ${targetFile.name}")
                        return@withContext true
                    } catch (e: IOException) {
                        Log.e(TAG, "Failed to write downloaded file to disk: ${targetFile.name}", e)
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

    private fun handleUnsuccessfulResponse(response: Response) {
        Log.e(
            TAG,
            "Failed to fetch. Code: ${response.code}, Message: ${response.message}"
        )
    }
}