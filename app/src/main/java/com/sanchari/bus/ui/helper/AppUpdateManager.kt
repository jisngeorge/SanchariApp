package com.sanchari.bus.ui.helper

import android.content.Intent
import android.content.ActivityNotFoundException
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sanchari.bus.BuildConfig
import com.sanchari.bus.R
import com.sanchari.bus.data.remote.NetworkManager
import com.sanchari.bus.data.local.DatabaseConstants
import com.sanchari.bus.data.local.DatabaseManager
import com.sanchari.bus.data.local.LocalVersionManager
import com.sanchari.bus.data.model.ServerVersionInfo
import com.sanchari.bus.data.model.AppVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import androidx.lifecycle.Lifecycle
import java.util.Date

class AppUpdateManager(
    private val activity: AppCompatActivity,
    private val downloadApp: (AppVersion) -> Unit,
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

    enum class UpdateScope { ALL, DATABASES, APP }

    companion object {
        private const val TAG = "AppUpdateManager"
        private const val UPDATE_INTERVAL_MS = 604800000L // 7 days
        fun isCheckDue(lastCheck: Long, currentTime: Long): Boolean =
            lastCheck == 0L || currentTime - lastCheck >= UPDATE_INTERVAL_MS
    }

    fun showUpdateOptions(
        localTimetableVersion: Int,
        localCommunityVersion: Int,
        onUpdateAvailable: (Boolean) -> Unit,
        onUpdateComplete: () -> Unit
    ) {
        updateDialog?.dismiss()
        updateDialog = AlertDialog.Builder(activity)
            .setTitle(R.string.btn_check_updates)
            .setMessage(R.string.update_choose_type)
            .setPositiveButton(R.string.update_check_databases) { _, _ ->
                checkForUpdates(true, localTimetableVersion, localCommunityVersion,
                    onUpdateAvailable, onUpdateComplete, UpdateScope.DATABASES)
            }
            .setNeutralButton(R.string.update_app) { _, _ ->
                showAppUpdateOptions(localTimetableVersion, localCommunityVersion,
                    onUpdateAvailable, onUpdateComplete)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAppUpdateOptions(
        localTimetableVersion: Int,
        localCommunityVersion: Int,
        onUpdateAvailable: (Boolean) -> Unit,
        onUpdateComplete: () -> Unit
    ) {
        updateDialog?.dismiss()
        updateDialog = AlertDialog.Builder(activity)
            .setTitle(R.string.update_app)
            .setMessage(R.string.update_options_message)
            .setPositiveButton(R.string.update_google_play) { _, _ -> openGooglePlay() }
            .setNeutralButton(R.string.apk_download) { _, _ ->
                checkForUpdates(true, localTimetableVersion, localCommunityVersion,
                    onUpdateAvailable, onUpdateComplete, UpdateScope.APP)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openGooglePlay() {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("market://details?id=${activity.packageName}")).setPackage("com.android.vending"))
        } catch (_: ActivityNotFoundException) {
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=${activity.packageName}")))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(activity, R.string.update_play_unavailable, Toast.LENGTH_LONG).show()
            }
        }
    }

    fun checkForUpdates(
        forceCheck: Boolean,
        localTimetableVersion: Int,
        localCommunityVersion: Int,
        onUpdateAvailable: ((Boolean) -> Unit)? = null,
        onUpdateComplete: (() -> Unit)? = null,
        scope: UpdateScope = UpdateScope.ALL
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

                    if (scope == UpdateScope.ALL) LocalVersionManager.saveLastUpdateCheckTime(activity, now())
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

                    val checkApp = scope != UpdateScope.DATABASES && appUpdateAvailable
                    val checkTimetable = scope != UpdateScope.APP && timetableUpdate
                    val checkCommunity = scope != UpdateScope.APP && communityUpdate
                    if (!checkApp && !checkTimetable && !checkCommunity) {
                        if (forceCheck) withContext(Dispatchers.Main) {
                            val message = if (scope == UpdateScope.DATABASES)
                                activity.getString(R.string.database_updates_complete) else "App is up to date."
                            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
                        }
                        return@launch
                    }
                    val automatic = LocalVersionManager.autoDatabaseUpdatesEnabled(activity)
                    if (automatic && (checkTimetable || checkCommunity)) {
                        pendingAutomaticDownload = true
                        automaticOperation = true
                        downloadDatabases(
                            serverInfo, checkTimetable, checkCommunity, automatic = true,
                            onUpdateAvailable = onUpdateAvailable, onUpdateComplete = onUpdateComplete
                        )
                        pendingAutomaticDownload = false
                    }
                    withContext(Dispatchers.Main) {
                        if (checkApp || !automatic) promptForUpdate(
                            if (automatic) false else checkTimetable,
                            if (automatic) false else checkCommunity,
                            checkApp, serverInfo, onUpdateAvailable, onUpdateComplete
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
        val builder = AlertDialog.Builder(activity)
            .setTitle("Updates Available")
            .setMessage(message)
            .setPositiveButton(if (appUpdate) activity.getString(R.string.apk_download) else "Download") { dialog, _ ->
                automaticOperation = false
                updateJob = activity.lifecycleScope.launch(Dispatchers.IO) {
                    if (timetable || community) {
                        downloadDatabases(serverInfo, timetable, community, appUpdate, onUpdateAvailable, onUpdateComplete)
                    }
                    withContext(Dispatchers.Main) {
                        if (appUpdate && serverInfo.app != null) downloadApp(serverInfo.app)
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton("Later") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(false)
        if (appUpdate) {
            builder.setNeutralButton(R.string.update_google_play) { _, _ -> openGooglePlay() }
        }
        updateDialog = builder.show()
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