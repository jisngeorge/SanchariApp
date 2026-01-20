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

class AppUpdateManager(private val activity: AppCompatActivity) {

    companion object {
        private const val TAG = "AppUpdateManager"
        private const val UPDATE_INTERVAL_MS = 604800000L // 7 days
    }

    fun checkForUpdates(
        forceCheck: Boolean,
        localTimetableVersion: Int,
        localCommunityVersion: Int,
        onUpdateAvailable: ((Boolean) -> Unit)? = null,
        onUpdateComplete: (() -> Unit)? = null // Restored this callback
    ) {
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
                    if (forceCheck) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(activity, "Update check failed. Please check your internet connection.", Toast.LENGTH_LONG).show()
                        }
                    }
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

                if (serverInfo.app != null && !serverInfo.app.url.isNullOrBlank()) {
                    LocalVersionManager.saveLatestAppUrl(activity, serverInfo.app.url)
                }

                // 4. Check App Version
                val currentAppVersion = BuildConfig.VERSION_CODE
                val serverAppInfo = serverInfo.app
                var appUpdateAvailable = false
                if (serverAppInfo != null && serverAppInfo.versionCode > currentAppVersion) {
                    appUpdateAvailable = true
                }

                // 5. Check DB Versions
                val isTimetableUpdateAvailable = serverInfo.timetable.version > localTimetableVersion
                val isCommunityUpdateAvailable = serverInfo.community.version > localCommunityVersion

                Log.i(TAG, "Server Versions - Timetable: ${serverInfo.timetable.version}, Community: ${serverInfo.community.version}")

                val updateAvailable = isTimetableUpdateAvailable || isCommunityUpdateAvailable || appUpdateAvailable

                LocalVersionManager.setUpdateAvailable(activity, updateAvailable)

                if (updateAvailable) {
                    Log.i(TAG, "Updates available. Prompting user.")
                    withContext(Dispatchers.Main) {
                        onUpdateAvailable?.invoke(true)
                        promptForUpdate(
                            isTimetableUpdateAvailable,
                            isCommunityUpdateAvailable,
                            appUpdateAvailable,
                            serverInfo,
                            onUpdateComplete
                        )
                    }
                } else {
                    Log.i(TAG, "No new updates.")
                    withContext(Dispatchers.Main) {
                        onUpdateAvailable?.invoke(false)
                        if (forceCheck) {
                            Toast.makeText(activity, "App is up to date.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in checkForUpdates", e)
                if (forceCheck) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(activity, "An error occurred while checking for updates.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun promptForUpdate(
        timetable: Boolean,
        community: Boolean,
        appUpdate: Boolean,
        serverInfo: ServerVersionInfo,
        onUpdateComplete: (() -> Unit)?
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
                    startDatabaseDownload(serverInfo, timetable, community, onUpdateComplete)
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
        downloadCommunity: Boolean,
        onUpdateComplete: (() -> Unit)?
    ) {
        Toast.makeText(activity, "Downloading database updates...", Toast.LENGTH_SHORT).show()
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
                // --- RESET FLAG AND NOTIFY ---
                LocalVersionManager.setUpdateAvailable(activity, false)

                withContext(Dispatchers.Main) {
                    onUpdateComplete?.invoke() // Notify MainActivity to reset UI

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
