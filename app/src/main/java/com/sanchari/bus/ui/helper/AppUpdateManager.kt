package com.sanchari.bus.ui.helper

import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.sanchari.bus.BuildConfig
import com.sanchari.bus.data.remote.NetworkManager
import com.sanchari.bus.data.local.DatabaseConstants
import com.sanchari.bus.data.local.DatabaseManager
import com.sanchari.bus.data.local.LocalVersionManager
import com.sanchari.bus.data.model.ServerVersionInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date

/**
 * Handles all logic related to checking for updates, prompting the user,
 * downloading database updates, and self-updating the APK.
 */
class AppUpdateManager(private val activity: AppCompatActivity) {

    companion object {
        private const val TAG = "AppUpdateManager"
        private const val UPDATE_INTERVAL_MS = 604800000L // 7 days
    }

    /**
     * Fetches version info from the server and compares it with local versions.
     * @param forceCheck If true, ignores the 7-day interval rule.
     * @param localTimetableVersion Current local DB version.
     * @param localCommunityVersion Current local DB version.
     */
    fun checkForUpdates(forceCheck: Boolean, localTimetableVersion: Int, localCommunityVersion: Int) {
        activity.lifecycleScope.launch(Dispatchers.IO) {

            // 1. Frequency Check
            if (!forceCheck) {
                val lastCheckTime = LocalVersionManager.getLastUpdateCheckTime(activity)
                val currentTime = System.currentTimeMillis()
                val timeSinceLastCheck = currentTime - lastCheckTime

                if (timeSinceLastCheck < UPDATE_INTERVAL_MS) {
                    Log.i(TAG, "Skipping update check. Last check was ${Date(lastCheckTime)}.")
                    return@launch
                }
            }

            Log.i(TAG, "Comparing against Local Versions - Timetable: $localTimetableVersion, Community: $localCommunityVersion")

            try {
                Log.i(TAG, "Checking for updates from server...")
                val serverInfo = NetworkManager.fetchVersionInfo(activity)

                if (serverInfo == null) {
                    Log.w(TAG, "Could not fetch server version info. Skipping update.")
                    return@launch
                }

                // 2. Save timestamp ONLY after successful fetch
                LocalVersionManager.saveLastUpdateCheckTime(activity, System.currentTimeMillis())

                // 3. Dynamic configuration updates
                if (!serverInfo.versions.isNullOrBlank()) {
                    LocalVersionManager.saveVersionsUrl(activity, serverInfo.versions)
                }
                if (!serverInfo.communityData.isNullOrBlank()) {
                    LocalVersionManager.saveCommunityUrl(activity, serverInfo.communityData)
                }

                // 4. Check App Version
                val currentAppVersion = BuildConfig.VERSION_CODE
                val serverAppInfo = serverInfo.app
                var appUpdateAvailable = false
                if (serverAppInfo != null && serverAppInfo.versionCode > currentAppVersion) {
                    appUpdateAvailable = true
                }

                // 5. Check DB Versions
                // Re-fetch local versions here to ensure we have the latest state (though passed params are usually fine)
                // We use the passed params to be consistent with the calling context
                val isTimetableUpdateAvailable = serverInfo.timetable.version > localTimetableVersion
                val isCommunityUpdateAvailable = serverInfo.community.version > localCommunityVersion

                Log.i(TAG, "Server Versions - Timetable: ${serverInfo.timetable.version}, Community: ${serverInfo.community.version}")

                if (isTimetableUpdateAvailable || isCommunityUpdateAvailable || appUpdateAvailable) {
                    Log.i(TAG, "Updates available. Prompting user.")
                    withContext(Dispatchers.Main) {
                        promptForUpdate(
                            isTimetableUpdateAvailable,
                            isCommunityUpdateAvailable,
                            appUpdateAvailable,
                            serverInfo
                        )
                    }
                } else {
                    Log.i(TAG, "No new updates.")
                    if (forceCheck) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(activity, "App is up to date.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in checkForUpdates", e)
            }
        }
    }

    private fun promptForUpdate(
        timetable: Boolean,
        community: Boolean,
        appUpdate: Boolean,
        serverInfo: ServerVersionInfo
    ) {
        val messages = mutableListOf<String>()
        if (appUpdate) messages.add("New App Version")
        if (timetable) messages.add("New Bus Timetable")
        if (community) messages.add("Updated Community Ratings")

        val message = "New updates are available:\n\n" +
                messages.joinToString("\n") { "• $it" } +
                "\n\nDownload now?"

        AlertDialog.Builder(activity)
            .setTitle("Updates Available")
            .setMessage(message)
            .setPositiveButton("Download") { dialog, _ ->
                if (appUpdate && serverInfo.app != null) {
                    downloadAndInstallApp(serverInfo.app.url)
                }
                if (timetable || community) {
                    startDatabaseDownload(serverInfo, timetable, community)
                }
                dialog.dismiss()
            }
            .setNegativeButton("Later") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    private fun downloadAndInstallApp(apkUrl: String) {
        Toast.makeText(activity, "Downloading new app version...", Toast.LENGTH_SHORT).show()
        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val apkFile = File(activity.cacheDir, "update.apk")
                if (apkFile.exists()) apkFile.delete()

                val success = NetworkManager.downloadFile(apkUrl, apkFile)

                if (success) {
                    withContext(Dispatchers.Main) {
                        installApk(apkFile)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(activity, "Download failed.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading APK", e)
            }
        }
    }

    private fun installApk(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.provider",
                file
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error installing APK", e)
            Toast.makeText(activity, "Error launching installer.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startDatabaseDownload(
        serverInfo: ServerVersionInfo,
        downloadTimetable: Boolean,
        downloadCommunity: Boolean
    ) {
        Toast.makeText(activity, "Downloading database updates...", Toast.LENGTH_SHORT).show()
        Log.i(TAG, "Starting download coroutine...")

        activity.lifecycleScope.launch(Dispatchers.IO) {
            var timetableSuccess = true
            var communitySuccess = true

            if (downloadTimetable) {
                Log.i(TAG, "Downloading Timetable DB...")
                timetableSuccess = DatabaseManager.downloadAndReplaceDatabase(
                    activity,
                    DatabaseConstants.TIMETABLE_DATABASE_NAME,
                    serverInfo.timetable.version,
                    serverInfo.timetable.url
                )
            }

            if (downloadCommunity) {
                Log.i(TAG, "Downloading Community DB...")
                communitySuccess = DatabaseManager.downloadAndReplaceDatabase(
                    activity,
                    DatabaseConstants.COMMUNITY_DATABASE_NAME,
                    serverInfo.community.version,
                    serverInfo.community.url
                )
            }

            if (timetableSuccess && communitySuccess) {
                Log.i(TAG, "All databases updated successfully.")
                withContext(Dispatchers.Main) {
                    AlertDialog.Builder(activity)
                        .setTitle("Update Complete")
                        .setMessage("Data updated successfully.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            } else {
                Log.e(TAG, "One or more database downloads failed.")
                withContext(Dispatchers.Main) {
                    AlertDialog.Builder(activity)
                        .setTitle("Update Failed")
                        .setMessage("Could not download all updates. Please try again later.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }
}