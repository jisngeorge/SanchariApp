package com.sanchari.bus

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object DatabaseManager {

    private const val TAG = "DatabaseManager"
    private const val APP_CONFIG_FILE = "app_config.json"

    /**
     * Initializes databases and configuration.
     */
    suspend fun initializeDatabases(context: Context) {
        withContext(Dispatchers.IO) {
            // 1. Initialize Config (URLs)
            initializeAppConfig(context)

            // 2. Initialize DBs
            copyDatabaseFromAssets(context, DatabaseConstants.TIMETABLE_DATABASE_NAME)
            copyDatabaseFromAssets(context, DatabaseConstants.COMMUNITY_DATABASE_NAME)

            // 3. Sync versions from the files to SharedPrefs
            LocalVersionManager.syncVersionsFromDbFiles(context)
        }
    }

    /**
     * Reads app_config.json from assets and saves the URLs to SharedPreferences
     * if they are not already set.
     */
    private fun initializeAppConfig(context: Context) {
        val currentVersionsUrl = LocalVersionManager.getVersionsUrl(context)
        val currentCommunityUrl = LocalVersionManager.getCommunityUrl(context)
        val currentAppUrl = LocalVersionManager.getLatestAppUrl(context) // --- CHECK EXISTING ---

        // If all are set, skip loading assets
        if (!currentVersionsUrl.isNullOrBlank() &&
            !currentCommunityUrl.isNullOrBlank() &&
            !currentAppUrl.isNullOrBlank()) {
            return
        }

        try {
            val jsonString = context.assets.open(APP_CONFIG_FILE).bufferedReader().use { it.readText() }
            val config = Json.decodeFromString<AppConfig>(jsonString)

            if (currentVersionsUrl.isNullOrBlank()) {
                LocalVersionManager.saveVersionsUrl(context, config.remoteVersionsUrl)
            }

            if (currentCommunityUrl.isNullOrBlank()) {
                LocalVersionManager.saveCommunityUrl(context, config.communityDataUrl)
            }

            // --- NEW: SAVE DEFAULT APP URL ---
            if (currentAppUrl.isNullOrBlank() && !config.latestAppUrl.isNullOrBlank()) {
                LocalVersionManager.saveLatestAppUrl(context, config.latestAppUrl)
                Log.i(TAG, "Initialized Default App URL: ${config.latestAppUrl}")
            }
            // -------------------------------

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load app_config.json", e)
        }
    }

    /**
     * Downloads a new database file, verifies it, and replaces the old one.
     */
    suspend fun downloadAndReplaceDatabase(
        context: Context,
        dbName: String,
        newVersion: Int,
        downloadUrl: String
    ): Boolean {
        return withContext(Dispatchers.IO) {
            val dbPath = context.getDatabasePath(dbName)
            val tempFile = File(context.cacheDir, "$dbName.temp")

            Log.i(TAG, "Starting download for $dbName from $downloadUrl...")
            val downloadSuccess = NetworkManager.downloadFile(downloadUrl, tempFile)

            if (!downloadSuccess) {
                if (tempFile.exists()) tempFile.delete()
                return@withContext false
            }

            try {
                if (dbPath.exists()) {
                    dbPath.delete()
                }

                if (tempFile.renameTo(dbPath)) {
                    Log.i(TAG, "Successfully swapped temp file to ${dbPath.name}")

                    // Sync the new version from the new file to Prefs
                    LocalVersionManager.syncVersionsFromDbFiles(context)

                    return@withContext true
                } else {
                    if (tempFile.exists()) tempFile.delete()
                    return@withContext false
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error during atomic swap for $dbName", e)
                if (tempFile.exists()) tempFile.delete()
                return@withContext false
            }
        }
    }

    private suspend fun copyDatabaseFromAssets(context: Context, dbName: String) {
        withContext(Dispatchers.IO) {
            val dbPath = context.getDatabasePath(dbName)

            if (dbPath.exists()) {
                return@withContext
            }

            dbPath.parentFile?.mkdirs()

            try {
                val inputStream = context.assets.open(dbName)
                val outputStream = FileOutputStream(dbPath)

                inputStream.use { input ->
                    outputStream.use { output ->
                        input.copyTo(output)
                    }
                }
                Log.i(TAG, "Successfully copied database $dbName from assets.")

            } catch (e: IOException) {
                Log.e(TAG, "Failed to copy database $dbName from assets", e)
                if (dbPath.exists()) dbPath.delete()
            }
        }
    }
}