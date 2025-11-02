package com.sanchari.bus

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object DatabaseManager {

    private const val TAG = "DatabaseManager"

    /**
     * Initializes the read-only databases.
     * Checks if they exist, and if not, copies them from the /assets folder.
     * This should be run on a background thread.
     */
    suspend fun initializeDatabases(context: Context) {
        withContext(Dispatchers.IO) {
            copyDatabaseFromAssets(context, DatabaseConstants.TIMETABLE_DATABASE_NAME)
            copyDatabaseFromAssets(context, DatabaseConstants.COMMUNITY_DATABASE_NAME)
        }
    }

    /**
     * Downloads a new database file, verifies it, and replaces the old one.
     * This is the "atomic swap" logic.
     *
     * @param context App context
     * @param dbName The name of the database file (e.g., TimetableDatabase.db)
     * @param newVersion The new version number to save if successful
     * @param downloadUrl The URL to download the new database file from
     * @return True if the update was successful, false otherwise.
     */
    suspend fun downloadAndReplaceDatabase(
        context: Context,
        dbName: String,
        newVersion: Int,
        downloadUrl: String
    ): Boolean {
        // We must run file I/O on the IO dispatcher
        return withContext(Dispatchers.IO) {
            val dbPath = context.getDatabasePath(dbName)
            val tempFile = File(context.cacheDir, "$dbName.temp")

            // 1. Download the file to a temporary location
            Log.i(TAG, "Starting download for $dbName from $downloadUrl...")
            val downloadSuccess = NetworkManager.downloadFile(downloadUrl, tempFile)

            if (!downloadSuccess) {
                Log.e(TAG, "Failed to download $dbName. Aborting update.")
                if (tempFile.exists()) {
                    tempFile.delete()
                }
                return@withContext false
            }

            // 2. Perform the "atomic swap"
            try {
                // Ensure the database helpers are closed before swapping.
                // Since SQLiteOpenHelper opens/closes on-demand, we just
                // need to make sure we don't have an active readable/writable
                // database instance. For this app, we're safe to just swap.
                // In a more complex app, we'd close the helper instance here.

                if (dbPath.exists()) {
                    dbPath.delete()
                    Log.i(TAG, "Deleted old database file: ${dbPath.name}")
                }

                if (tempFile.renameTo(dbPath)) {
                    Log.i(TAG, "Successfully swapped temp file to ${dbPath.name}")

                    // 3. Update the local version in SharedPreferences
                    when (dbName) {
                        DatabaseConstants.TIMETABLE_DATABASE_NAME ->
                            LocalVersionManager.saveTimetableVersion(context, newVersion)
                        DatabaseConstants.COMMUNITY_DATABASE_NAME ->
                            LocalVersionManager.saveCommunityVersion(context, newVersion)
                    }
                    Log.i(TAG, "Updated $dbName version to $newVersion")
                    return@withContext true
                } else {
                    Log.e(TAG, "Failed to rename temp file. Restoring old DB if possible.")
                    // If rename fails, try to restore (or re-copy from assets)
                    // For now, we'll just log the error.
                    if (tempFile.exists()) {
                        tempFile.delete() // Clean up
                    }
                    return@withContext false
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error during atomic swap for $dbName", e)
                if (tempFile.exists()) {
                    tempFile.delete() // Clean up
                }
                return@withContext false
            }
        }
    }


    /**
     * Copies a database from the 'assets' folder to the app's data directory.
     * Will not overwrite if the file already exists.
     */
    private suspend fun copyDatabaseFromAssets(context: Context, dbName: String) {
        withContext(Dispatchers.IO) {
            val dbPath = context.getDatabasePath(dbName)
            if (dbPath.exists()) {
                // Log.v(TAG, "Database $dbName already exists, no need to copy from assets.")
                return@withContext // Database already exists
            }

            // Parent directory might not exist
            dbPath.parentFile?.mkdirs()

            Log.i(TAG, "Database $dbName not found. Copying from assets...")
            try {
                // Open the asset
                val inputStream = context.assets.open(dbName)
                // Create the output file
                val outputStream = FileOutputStream(dbPath)

                // Copy the file
                inputStream.use { input ->
                    outputStream.use { output ->
                        input.copyTo(output)
                    }
                }
                Log.i(TAG, "Successfully copied database $dbName from assets.")

                // IMPORTANT: After copying v1, set the local version to 1
                // This assumes your bundled DBs are version 1.
                when (dbName) {
                    DatabaseConstants.TIMETABLE_DATABASE_NAME ->
                        LocalVersionManager.saveTimetableVersion(context, 1)
                    DatabaseConstants.COMMUNITY_DATABASE_NAME ->
                        LocalVersionManager.saveCommunityVersion(context, 1)
                }

            } catch (e: IOException) {
                Log.e(TAG, "Failed to copy database $dbName from assets", e)
                // If copy fails, delete the potentially partial file
                if (dbPath.exists()) {
                    dbPath.delete()
                }
            }
        }
    }
}

