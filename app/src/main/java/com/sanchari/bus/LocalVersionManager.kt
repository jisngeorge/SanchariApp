package com.sanchari.bus

import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File

object LocalVersionManager {

    private const val PREFS_NAME = "SanchariBusPrefs"
    private const val TAG = "LocalVersionManager"

    // Keys for Dynamic URLs (Still stored in Prefs)
    private const val KEY_DYNAMIC_VERSIONS_URL = "dynamic_versions_url"
    private const val KEY_DYNAMIC_COMMUNITY_URL = "dynamic_community_url"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // --- DB HEADER VERSION LOGIC (Replaces Prefs logic) ---

    /**
     * reads the internal version number from the SQLite file header.
     * Returns 0 if file doesn't exist or can't be read.
     */
    private fun getDbHeaderVersion(context: Context, dbName: String): Int {
        val dbPath = context.getDatabasePath(dbName)
        if (!dbPath.exists()) {
            Log.d(TAG, "Database $dbName does not exist. Returning version 0.")
            return 0
        }

        return try {
            // Open DB just to read the version header
            SQLiteDatabase.openDatabase(
                dbPath.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            ).use { db ->
                Log.v(TAG, "Read $dbName header version: ${db.version}")
                db.version
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read version from $dbName header", e)
            0
        }
    }

    /**
     * Writes the version number into the SQLite file header.
     */
    private fun setDbHeaderVersion(context: Context, dbName: String, version: Int) {
        val dbPath = context.getDatabasePath(dbName)
        if (!dbPath.exists()) {
            Log.e(TAG, "Cannot set version for $dbName: File not found.")
            return
        }

        try {
            SQLiteDatabase.openDatabase(
                dbPath.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE
            ).use { db ->
                db.version = version // This executes 'PRAGMA user_version = X'
                Log.i(TAG, "Updated $dbName header to version $version")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set version in $dbName header", e)
        }
    }

    // --- Specific Getters (Now use DB Header) ---

    fun getTimetableDbVersion(context: Context): Int {
        return getDbHeaderVersion(context, DatabaseConstants.TIMETABLE_DATABASE_NAME)
    }

    fun getCommunityDbVersion(context: Context): Int {
        return getDbHeaderVersion(context, DatabaseConstants.COMMUNITY_DATABASE_NAME)
    }

    // --- Specific Setters (Now use DB Header) ---

    fun saveTimetableVersion(context: Context, version: Int) {
        setDbHeaderVersion(context, DatabaseConstants.TIMETABLE_DATABASE_NAME, version)
    }

    fun saveCommunityVersion(context: Context, version: Int) {
        setDbHeaderVersion(context, DatabaseConstants.COMMUNITY_DATABASE_NAME, version)
    }

    // --- Dynamic URL Management (Remains in SharedPreferences) ---

    fun getVersionsUrl(context: Context): String? {
        return getPrefs(context).getString(KEY_DYNAMIC_VERSIONS_URL, null)
    }

    fun saveVersionsUrl(context: Context, url: String) {
        getPrefs(context).edit().putString(KEY_DYNAMIC_VERSIONS_URL, url).apply()
    }

    fun getCommunityUrl(context: Context): String? {
        return getPrefs(context).getString(KEY_DYNAMIC_COMMUNITY_URL, null)
    }

    fun saveCommunityUrl(context: Context, url: String) {
        getPrefs(context).edit().putString(KEY_DYNAMIC_COMMUNITY_URL, url).apply()
    }
}