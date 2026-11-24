package com.sanchari.bus.data.remote

import android.content.Context
import android.util.Log
import com.sanchari.bus.data.local.LocalVersionManager
import com.sanchari.bus.data.model.ServerVersionInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.buffer
import okio.sink
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

object NetworkManager {

    private const val TAG = "NetworkManager"

    // By lazy ensures that OkHttpClient and Json serializers are ONLY initialized
    // when first needed, on the background thread, avoiding UI freezing on first access.
    private val client by lazy { OkHttpClient() }
    private val downloadClient by lazy {
        client.newBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
    private val jsonParser by lazy { Json { ignoreUnknownKeys = true } }

    /**
     * Uploads a JSON payload to the Google Script URL found in configuration.
     * Returns TRUE if successful (HTTP 200), FALSE otherwise.
     */
    suspend fun uploadDataToGoogleSheet(context: Context, jsonPayload: String): Boolean {
        val url = LocalVersionManager.getCommunityUrl(context)
        if (url.isNullOrBlank()) {
            Log.e(TAG, "No community upload URL found in configuration.")
            return false
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = jsonPayload.toRequestBody(mediaType)
        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        return coroutineScope {
            val call = client.newCall(request)
            val upload = async(Dispatchers.IO) {
                try {
                    call.execute().use { response ->
                        if (response.isSuccessful) {
                            Log.i(TAG, "Upload successful: ${response.code}")
                            true
                        } else {
                            Log.e(TAG, "Upload failed: ${response.code} - ${response.message}")
                            false
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Network error during upload", e)
                    false
                }
            }
            try {
                upload.await()
            } finally {
                if (!isActive) call.cancel()
            }
        }
    }

    suspend fun fetchVersionInfo(context: Context): ServerVersionInfo? {
        val targetUrl = LocalVersionManager.getVersionsUrl(context)
        if (targetUrl.isNullOrBlank()) {
            Log.e(TAG, "No versions URL found in configuration.")
            return null
        }

        Log.d(TAG, "Fetching version info from: $targetUrl")
        val request = Request.Builder()
            .url(targetUrl)
            .build()

        return coroutineScope {
            val call = client.newCall(request)
            val fetch = async(Dispatchers.IO) {
                try {
                    call.execute().use { response ->
                        if (!response.isSuccessful) {
                            handleUnsuccessfulResponse(response)
                            return@async null
                        }

                        val responseBody = response.body
                        if (responseBody == null) {
                            Log.e(TAG, "Response body was null for version.json")
                            return@async null
                        }

                        parseJson(responseBody.string())
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Network failure when fetching version.json", e)
                    null
                } catch (e: Exception) {
                    Log.e(TAG, "Unexpected error fetching version.json", e)
                    null
                }
            }
            try {
                fetch.await()
            } finally {
                if (!isActive) call.cancel()
            }
        }
    }

    suspend fun downloadFile(url: String, targetFile: File): Boolean {
        return coroutineScope {
            val call = downloadClient.newCall(Request.Builder().url(url).build())
            val download = async(Dispatchers.IO) {
                try {
                    call.execute().use { response ->
                        if (!response.isSuccessful) {
                            handleUnsuccessfulResponse(response)
                            return@async false
                        }

                        val body = response.body
                        if (body == null) {
                            Log.e(TAG, "Response body was null for file download: $url")
                            return@async false
                        }

                        try {
                            targetFile.sink().buffer().use { sink: BufferedSink ->
                                sink.writeAll(body.source())
                            }
                            Log.i(TAG, "File downloaded successfully: ${targetFile.name}")
                            return@async true
                        } catch (e: IOException) {
                            Log.e(TAG, "Failed to write downloaded file to disk: ${targetFile.name}", e)
                            if (targetFile.exists()) {
                                targetFile.delete()
                            }
                            return@async false
                        }
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Network failure during file download: $url", e)
                    return@async false
                } catch (e: Exception) {
                    Log.e(TAG, "Unexpected error during file download: $url", e)
                    return@async false
                }
            }
            try {
                download.await()
            } finally {
                // Release the socket before waiting for the blocking IO worker to finish.
                if (!isActive) call.cancel()
            }
        }
    }

    private fun parseJson(jsonString: String): ServerVersionInfo? {
        return try {
            jsonParser.decodeFromString<ServerVersionInfo>(jsonString)
        } catch (e: SerializationException) {
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