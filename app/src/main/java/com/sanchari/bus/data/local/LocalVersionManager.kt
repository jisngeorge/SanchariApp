package com.sanchari.bus.data.local

import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.core.content.edit

object LocalVersionManager {

    private const val PREFS_NAME = "SanchariBusPrefs"
    private const val TAG = "LocalVersionManager"

    private const val KEY_DYNAMIC_VERSIONS_URL = "dynamic_versions_url"
    private const val KEY_DYNAMIC_COMMUNITY_URL = "dynamic_community_url"
    private const val KEY_LAST_UPDATE_CHECK = "last_update_check_timestamp"
    private const val KEY_LATEST_APP_URL = "latest_app_url"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Syncs the versions stored in the database files (DbVersion table)
     * into SharedPreferences. Call this after DB initialization or download.
     */
    fun syncVersionsFromDbFiles(context: Context) {
        val tVersion = readVersionFromDbFile(context, DatabaseConstants.TIMETABLE_DATABASE_NAME)
        val cVersion = readVersionFromDbFile(context, DatabaseConstants.COMMUNITY_DATABASE_NAME)

        if (tVersion > 0) saveTimetableVersion(context, tVersion)
        if (cVersion > 0) saveCommunityVersion(context, cVersion)
    }

    /**
     * Queries the 'DbVersion' table in the given database file to find the content version.
     */
    private fun readVersionFromDbFile(context: Context, dbName: String): Int {
        val dbPath = context.getDatabasePath(dbName)
        if (!dbPath.exists()) return 0

        var version = 0
        try {
            SQLiteDatabase.openDatabase(dbPath.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                // Check if table exists first to avoid crash on old/empty DBs
                val cursor = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='${DatabaseConstants.VersionTable.TABLE_NAME}'", null)
                if (cursor.count > 0) {
                    cursor.close()
                    val vCursor = db.rawQuery("SELECT ${DatabaseConstants.VersionTable.COLUMN_VERSION} FROM ${DatabaseConstants.VersionTable.TABLE_NAME} LIMIT 1", null)
                    if (vCursor.moveToFirst()) {
                        version = vCursor.getInt(0)
                        Log.i(TAG, "Read content version $version from $dbName")
                    }
                    vCursor.close()
                } else {
                    cursor.close()
                    Log.w(TAG, "Version table not found in $dbName")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read version from table in $dbName", e)
        }
        return version
    }

    // --- Getters (Read from SharedPreferences) ---

    fun getTimetableDbVersion(context: Context): Int {
        return getPrefs(context).getInt(DatabaseConstants.TIMETABLE_DB_VERSION_KEY, 0)
    }

    fun getCommunityDbVersion(context: Context): Int {
        return getPrefs(context).getInt(DatabaseConstants.COMMUNITY_DB_VERSION_KEY, 0)
    }

    // --- Setters (Write to SharedPreferences) ---

    private fun saveTimetableVersion(context: Context, version: Int) {
        getPrefs(context).edit { putInt(DatabaseConstants.TIMETABLE_DB_VERSION_KEY, version) }
    }

    private fun saveCommunityVersion(context: Context, version: Int) {
        getPrefs(context).edit { putInt(DatabaseConstants.COMMUNITY_DB_VERSION_KEY, version) }
    }

    // --- Dynamic URL Management ---

    fun getVersionsUrl(context: Context): String? {
        return getPrefs(context).getString(KEY_DYNAMIC_VERSIONS_URL, null)
    }

    fun saveVersionsUrl(context: Context, url: String) {
        getPrefs(context).edit { putString(KEY_DYNAMIC_VERSIONS_URL, url) }
    }

    fun getCommunityUrl(context: Context): String? {
        return getPrefs(context).getString(KEY_DYNAMIC_COMMUNITY_URL, null)
    }

    fun saveCommunityUrl(context: Context, url: String) {
        getPrefs(context).edit { putString(KEY_DYNAMIC_COMMUNITY_URL, url) }
    }

    // --- Update Check Timestamp Management ---

    fun getLastUpdateCheckTime(context: Context): Long {
        return getPrefs(context).getLong(KEY_LAST_UPDATE_CHECK, 0L)
    }

    fun saveLastUpdateCheckTime(context: Context, time: Long) {
        getPrefs(context).edit { putLong(KEY_LAST_UPDATE_CHECK, time) }
    }

    fun getLatestAppUrl(context: Context): String? {
        return getPrefs(context).getString(KEY_LATEST_APP_URL, null)
    }

    fun saveLatestAppUrl(context: Context, url: String) {
        getPrefs(context).edit { putString(KEY_LATEST_APP_URL, url) }
    }
}