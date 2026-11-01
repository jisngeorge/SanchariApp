package com.sanchari.bus.ui.helper

import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.sanchari.bus.BuildConfig
import com.sanchari.bus.R
import com.sanchari.bus.data.remote.NetworkManager
import com.sanchari.bus.data.local.DatabaseConstants
import com.sanchari.bus.data.local.DatabaseManager
import com.sanchari.bus.data.local.LocalVersionManager
import com.sanchari.bus.data.model.ServerVersionInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import androidx.lifecycle.Lifecycle
import java.io.File
import java.util.Date

class AppUpdateManager(
    private val activity: AppCompatActivity,
    private val fetchVersions: suspend () -> ServerVersionInfo? = {
        NetworkManager.fetchVersionInfo(activity)
    },
    private val installDatabase: suspend (String, Int, String) -> Boolean = { name, version, url ->
        DatabaseManager.downloadAndReplaceDatabase(activity, name, version, url)
    },
    private val now: () -> Long = System::currentTimeMillis
) {
    private var updateJob: Job? = null
    @Volatile private var automaticOperation = false
    private var updateDialog: AlertDialog? = null

    companion object {
        private const val TAG = "AppUpdateManager"
        private const val UPDATE_INTERVAL_MS = 604800000L // 7 days
        fun isCheckDue(lastCheck: Long, currentTime: Long): Boolean =
            lastCheck == 0L || currentTime - lastCheck >= UPDATE_INTERVAL_MS
    }

    fun checkForUpdates(
        forceCheck: Boolean,
        localTimetableVersion: Int,
        localCommunityVersion: Int,
        onUpdateAvailable: ((Boolean) -> Unit)? = null,
        onUpdateComplete: (() -> Unit)? = null
    ) {
        activity.runOnUiThread {
            if (updateJob?.isCompleted == false) {
                if (forceCheck) Toast.makeText(activity, R.string.update_in_progress, Toast.LENGTH_SHORT).show()
                return@runOnUiThread
            }
            if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@runOnUiThread
            automaticOperation = !forceCheck
            updateJob = activity.lifecycleScope.launch(Dispatchers.IO) {

                if (!forceCheck) {
                    val lastCheckTime = LocalVersionManager.getLastUpdateCheckTime(activity)
                    if (!isCheckDue(lastCheckTime, now())) {
                        Log.i(TAG, "Skipping update check. Last check was ${Date(lastCheckTime)}.")
                        return@launch
                    }
                }

                var pendingAutomaticDownload = false
                try {
                    val serverInfo = fetchVersions()
                    currentCoroutineContext().ensureActive()

                    if (serverInfo == null) {
                        Log.w(TAG, "Could not fetch server version info.")
                        if (forceCheck) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(activity, "Update check failed. Please check your internet connection.", Toast.LENGTH_LONG).show()
                            }
                        }
                        return@launch
                    }

                    LocalVersionManager.saveLastUpdateCheckTime(activity, now())
                    if (!serverInfo.versions.isNullOrBlank()) {
                        LocalVersionManager.saveVersionsUrl(activity, serverInfo.versions)
                    }
                    if (!serverInfo.communityData.isNullOrBlank()) {
                        LocalVersionManager.saveCommunityUrl(activity, serverInfo.communityData)
                    }
                    serverInfo.app?.url?.takeIf { it.isNotBlank() }?.let {
                        LocalVersionManager.saveLatestAppUrl(activity, it)
                    }

                    val appUpdateAvailable = (serverInfo.app?.versionCode ?: 0) > BuildConfig.VERSION_CODE
                    val timetableUpdate = serverInfo.timetable.version > localTimetableVersion
                    val communityUpdate = serverInfo.community.version > localCommunityVersion
                    val updateAvailable = timetableUpdate || communityUpdate || appUpdateAvailable

                    LocalVersionManager.setUpdateAvailable(activity, updateAvailable)
                    withContext(Dispatchers.Main) {
                        onUpdateAvailable?.invoke(updateAvailable)
                    }

                    if (!updateAvailable) {
                        if (forceCheck) withContext(Dispatchers.Main) {
                            Toast.makeText(activity, "App is up to date.", Toast.LENGTH_SHORT).show()
                        }
                        return@launch
                    }
                    val automatic = LocalVersionManager.autoDatabaseUpdatesEnabled(activity)
                    if (automatic && (timetableUpdate || communityUpdate)) {
                        pendingAutomaticDownload = true
                        automaticOperation = true
                        downloadDatabases(
                            serverInfo, timetableUpdate, communityUpdate, automatic = true,
                            onUpdateAvailable = onUpdateAvailable, onUpdateComplete = onUpdateComplete
                        )
                        pendingAutomaticDownload = false
                    }
                    withContext(Dispatchers.Main) {
                        if (appUpdateAvailable || !automatic) promptForUpdate(
                            if (automatic) false else timetableUpdate,
                            if (automatic) false else communityUpdate,
                            appUpdateAvailable, serverInfo, onUpdateAvailable, onUpdateComplete
                        )
                    }
                } catch (e: CancellationException) {
                    if (pendingAutomaticDownload) LocalVersionManager.saveLastUpdateCheckTime(activity, 0L)
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Error in checkForUpdates", e)
                    if (pendingAutomaticDownload) LocalVersionManager.saveLastUpdateCheckTime(activity, 0L)
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
        onUpdateAvailable: ((Boolean) -> Unit)?,
        onUpdateComplete: (() -> Unit)?
    ) {
        val messages = mutableListOf<String>()
        if (appUpdate) messages.add("New App Version")
        if (timetable) messages.add("New Bus Timetable")
        if (community) messages.add("Updated Community Ratings")

        val message = "New updates are available:\n\n" +
                messages.joinToString("\n") { "• $it" } +
                "\n\nDownload now?"

        updateDialog?.dismiss()
        updateDialog = AlertDialog.Builder(activity)
            .setTitle("Updates Available")
            .setMessage(message)
            .setPositiveButton("Download") { dialog, _ ->
                if (appUpdate && serverInfo.app != null) {
                    downloadAndInstallApp(serverInfo.app.url)
                }
                if (timetable || community) {
                    automaticOperation = false
                    updateJob = activity.lifecycleScope.launch(Dispatchers.IO) {
                        downloadDatabases(serverInfo, timetable, community, false, onUpdateAvailable, onUpdateComplete)
                    }
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
                    installApk(apkFile) // No context switch needed, handled internally
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

    // Now a suspend function to offload FileProvider logic to IO thread
    private suspend fun installApk(file: File) = withContext(Dispatchers.IO) {
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

            withContext(Dispatchers.Main) {
                activity.startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error installing APK", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(activity, "Error launching installer.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun downloadDatabases(
        serverInfo: ServerVersionInfo,
        downloadTimetable: Boolean,
        downloadCommunity: Boolean,
        automatic: Boolean,
        onUpdateAvailable: ((Boolean) -> Unit)?,
        onUpdateComplete: (() -> Unit)?
    ) {
        withContext(Dispatchers.Main) {
            Toast.makeText(activity, R.string.database_updates_downloading, Toast.LENGTH_SHORT).show()
        }
        try {
            var timetableSuccess = true
            var communitySuccess = true

            if (downloadTimetable) {
                Log.i(TAG, "Downloading Timetable DB...")
                timetableSuccess = installDatabase(
                    DatabaseConstants.TIMETABLE_DATABASE_NAME,
                    serverInfo.timetable.version,
                    serverInfo.timetable.url
                )
            }

            if (downloadCommunity) {
                Log.i(TAG, "Downloading Community DB...")
                communitySuccess = installDatabase(
                    DatabaseConstants.COMMUNITY_DATABASE_NAME,
                    serverInfo.community.version,
                    serverInfo.community.url
                )
            }

            currentCoroutineContext().ensureActive()
            val appRemaining = (serverInfo.app?.versionCode ?: 0) > BuildConfig.VERSION_CODE
            val remaining = !timetableSuccess || !communitySuccess || appRemaining
            LocalVersionManager.setUpdateAvailable(activity, remaining)
            withContext(Dispatchers.Main) {
                if ((downloadTimetable && timetableSuccess) || (downloadCommunity && communitySuccess)) onUpdateComplete?.invoke()
                onUpdateAvailable?.invoke(remaining)
            }
            if (timetableSuccess && communitySuccess) {
                Log.i(TAG, "All databases updated successfully.")

                withContext(Dispatchers.Main) {
                    if (automatic) {
                        Toast.makeText(activity, R.string.database_updates_complete, Toast.LENGTH_SHORT).show()
                    } else updateDialog = AlertDialog.Builder(activity)
                        .setTitle("Update Complete")
                        .setMessage("Data updated successfully.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            } else {
                LocalVersionManager.saveLastUpdateCheckTime(activity, 0L)
                Log.e(TAG, "One or more database downloads failed.")
                withContext(Dispatchers.Main) {
                    if (automatic) {
                        Toast.makeText(activity, R.string.database_updates_failed, Toast.LENGTH_LONG).show()
                    } else updateDialog = AlertDialog.Builder(activity)
                        .setTitle("Update Failed")
                        .setMessage("Could not download all updates. Please try again later.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        } catch (e: CancellationException) {
            LocalVersionManager.saveLastUpdateCheckTime(activity, 0L)
            throw e
        }
    }

    fun cancelAutomaticUpdates() {
        if (automaticOperation) updateJob?.cancel()
    }

    fun onPause() {
        updateJob?.cancel()
        updateDialog?.dismiss()
        updateDialog = null
    }
}