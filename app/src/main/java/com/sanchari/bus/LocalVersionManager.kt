package com.sanchari.bus

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

object LocalVersionManager {

    private const val PREFS_NAME = "SanchariBusPrefs"

    // --- NEW KEYS ---
    private const val KEY_DYNAMIC_VERSIONS_URL = "dynamic_versions_url"
    private const val KEY_DYNAMIC_COMMUNITY_URL = "dynamic_community_url"

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

    fun getTimetableDbVersion(context: Context): Int {
        return getDbVersion(context, DatabaseConstants.TIMETABLE_DB_VERSION_KEY)
    }

    fun getCommunityDbVersion(context: Context): Int {
        return getDbVersion(context, DatabaseConstants.COMMUNITY_DB_VERSION_KEY)
    }

    fun saveTimetableVersion(context: Context, version: Int) {
        setDbVersion(context, DatabaseConstants.TIMETABLE_DB_VERSION_KEY, version)
    }

    fun saveCommunityVersion(context: Context, version: Int) {
        setDbVersion(context, DatabaseConstants.COMMUNITY_DB_VERSION_KEY, version)
    }

    // --- NEW: Dynamic URL Management ---

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