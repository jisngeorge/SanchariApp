package com.sanchari.bus

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

object LocalVersionManager {

    private const val PREFS_NAME = "SanchariBusPrefs"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Saves the version for a specific database.
     */
    fun setDbVersion(context: Context, dbKey: String, version: Int) {
        Log.i("LocalVersionManager", "Setting $dbKey to version $version")
        getPrefs(context).edit().putInt(dbKey, version).apply()
    }

    /**
     * Gets the version for a specific database.
     */
    private fun getDbVersion(context: Context, dbKey: String): Int {
        val version = getPrefs(context).getInt(dbKey, DatabaseConstants.DEFAULT_DB_VERSION)
        Log.i("LocalVersionManager", "Getting $dbKey, version is $version")
        return version
    }

    // --- Specific Getters for MainActivity ---

    /**
     * Gets the currently stored version of the Timetable database.
     */
    fun getTimetableDbVersion(context: Context): Int {
        return getDbVersion(context, DatabaseConstants.TIMETABLE_DB_VERSION_KEY)
    }

    /**
     * Gets the currently stored version of the Community database.
     */
    fun getCommunityDbVersion(context: Context): Int {
        return getDbVersion(context, DatabaseConstants.COMMUNITY_DB_VERSION_KEY)
    }

    // --- ADDED MISSING FUNCTIONS for DatabaseManager ---

    /**
     * Saves the new version of the Timetable database.
     */
    fun saveTimetableVersion(context: Context, version: Int) {
        setDbVersion(context, DatabaseConstants.TIMETABLE_DB_VERSION_KEY, version)
    }

    /**
     * Saves the new version of the Community database.
     */
    fun saveCommunityVersion(context: Context, version: Int) {
        setDbVersion(context, DatabaseConstants.COMMUNITY_DB_VERSION_KEY, version)
    }
}

