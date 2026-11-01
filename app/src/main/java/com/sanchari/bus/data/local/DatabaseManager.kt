package com.sanchari.bus.data.local

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import com.sanchari.bus.data.remote.NetworkManager
import com.sanchari.bus.data.model.AppConfig
import com.sanchari.bus.data.manager.SearchManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object DatabaseManager {

    private const val TAG = "DatabaseManager"
    private const val APP_CONFIG_FILE = "app_config.json"
    private val replacementMutex = Mutex()

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
        downloadUrl: String,
        download: suspend (String, File) -> Boolean = NetworkManager::downloadFile
    ): Boolean {
        return withContext(Dispatchers.IO) {
            require(dbName in listOf(DatabaseConstants.TIMETABLE_DATABASE_NAME, DatabaseConstants.COMMUNITY_DATABASE_NAME))
            require(newVersion > 0)
            replacementMutex.withLock {
                val dbPath = context.getDatabasePath(dbName)
                var tempFile: File? = null
                try {
                    val parent = requireNotNull(dbPath.parentFile)
                    if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Database directory unavailable")
                    val staging = File.createTempFile("$dbName-", ".download", parent)
                    tempFile = staging
                    if (!download(downloadUrl, staging)) {
                        Log.e(TAG, "Download failed for $dbName; installed snapshot preserved")
                        return@withLock false
                    }
                    currentCoroutineContext().ensureActive()
                    validateSnapshot(staging, dbName, newVersion)
                    FileOutputStream(staging, true).use { it.fd.sync() }
                    currentCoroutineContext().ensureActive()
                    if (dbName == DatabaseConstants.TIMETABLE_DATABASE_NAME) TimetableDatabaseHelper.resetInstance()
                    // Same-filesystem rename atomically publishes the validated snapshot.
                    Os.rename(staging.absolutePath, dbPath.absolutePath)
                    if (dbName == DatabaseConstants.TIMETABLE_DATABASE_NAME) {
                        TimetableDatabaseHelper.resetInstance()
                        SearchManager.invalidateStopSuggestionsCache()
                    }
                    LocalVersionManager.saveInstalledDatabaseVersion(context, dbName, newVersion)
                    true
                } catch (e: IOException) {
                    Log.e(TAG, "Database replacement failed; installed snapshot preserved", e)
                    false
                } catch (e: SQLiteException) {
                    Log.e(TAG, "Downloaded snapshot failed validation; installed snapshot preserved", e)
                    false
                } catch (e: ErrnoException) {
                    Log.e(TAG, "Atomic database replacement failed; installed snapshot preserved", e)
                    false
                } finally {
                    tempFile?.let {
                        for (file in listOf(it, File("${it.absolutePath}-journal"), File("${it.absolutePath}-wal"), File("${it.absolutePath}-shm"))) {
                            if (file.exists() && !file.delete()) Log.w(TAG, "Could not remove database staging file")
                        }
                    }
                }
            }
        }
    }

    private fun validateSnapshot(file: File, dbName: String, expectedVersion: Int) {
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            database.rawQuery("PRAGMA integrity_check", null).use {
                if (!it.moveToFirst() || it.getString(0) != "ok" || it.moveToNext()) {
                    throw SQLiteException("Downloaded SQLite database failed integrity check")
                }
            }
            val bus = DatabaseConstants.BusServiceTable
            val stop = DatabaseConstants.StopTable
            val route = DatabaseConstants.RouteStopTable
            val rating = DatabaseConstants.BusRatingTable
            val comment = DatabaseConstants.UserCommentTable
            val required = (if (dbName == DatabaseConstants.TIMETABLE_DATABASE_NAME) {
                mapOf(
                    bus.TABLE_NAME to listOf(bus.COLUMN_SERVICE_ID, bus.COLUMN_NAME, bus.COLUMN_TYPE, bus.COLUMN_IS_RUNNING, bus.COLUMN_LAST_REPORTED_TIME),
                    stop.TABLE_NAME to listOf(stop.COLUMN_STOP_ID, stop.COLUMN_LOCATION_NAME, stop.COLUMN_LATITUDE, stop.COLUMN_LONGITUDE),
                    route.TABLE_NAME to listOf(route.COLUMN_ROUTE_STOP_ID, route.COLUMN_SERVICE_ID, route.COLUMN_STOP_ID, route.COLUMN_SCHEDULED_TIME, route.COLUMN_STOP_ORDER)
                )
            } else {
                mapOf(
                    rating.TABLE_NAME to listOf(rating.COLUMN_SERVICE_ID, rating.COLUMN_AVG_PUNCTUALITY, rating.COLUMN_AVG_DRIVE, rating.COLUMN_AVG_BEHAVIOUR, rating.COLUMN_RATING_COUNT),
                    comment.TABLE_NAME to listOf(comment.COLUMN_COMMENT_ID, comment.COLUMN_SERVICE_ID, comment.COLUMN_USERNAME, comment.COLUMN_COMMENT_TEXT, comment.COLUMN_COMMENT_DATE)
                )
            }) + (DatabaseConstants.VersionTable.TABLE_NAME to listOf(DatabaseConstants.VersionTable.COLUMN_VERSION))
            for ((table, columns) in required) {
                database.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table)).use {
                    if (!it.moveToFirst()) throw SQLiteException("Downloaded database is missing table $table")
                }
                database.rawQuery("SELECT ${columns.joinToString(",")} FROM $table LIMIT 0", null).use { }
            }
            database.rawQuery("SELECT ${DatabaseConstants.VersionTable.COLUMN_VERSION} FROM ${DatabaseConstants.VersionTable.TABLE_NAME} LIMIT 1", null).use {
                if (!it.moveToFirst() || it.getType(0) != Cursor.FIELD_TYPE_INTEGER || it.getLong(0) != expectedVersion.toLong()) {
                    throw SQLiteException("Downloaded database content version does not match the manifest")
                }
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