package com.sanchari.bus

import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sanchari.bus.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    // private var userDataManager: UserDataManager? = null // REMOVED - UserDataManager is a singleton object

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize managers
        // userDataManager = UserDataManager(applicationContext) // REMOVED - No instantiation needed

        lifecycleScope.launch {
            // 1. Initialize databases (copies from /assets on first launch)
            DatabaseManager.initializeDatabases(applicationContext)

            // 2. Check for updates
            checkForUpdates()
        }
    }

    /**
     * This is the final step, called after databases are ready and versions are checked.
     * This function should load the main app data (recent views, etc.)
     * and check if user info needs to be collected.
     */
    private fun loadAppData() {
        Log.i(TAG, "Loading app data...")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Get user data. Call UserDataManager statically, passing the context.
                val user = UserDataManager.getUser(applicationContext)

                // The getUser() method should always return a valid user (either existing or new),
                // so the null check is not required.

                // Check if essential user info is missing
                if (user.name.isBlank() || user.email.isBlank() || user.phone.isBlank()) {
                    // User info is incomplete, launch UserInfoActivity
                    Log.i(TAG, "User info incomplete. Launching UserInfoActivity.")
                    withContext(Dispatchers.Main) {
                        // Use this@MainActivity to ensure we get the Activity context
                        val intent = Intent(this@MainActivity, UserInfoActivity::class.java)
                        startActivity(intent)
                    }
                } else {
                    // User info exists, load main screen data
                    Log.i(TAG, "User info found. Loading main app data for ${user.name}.")
                    // TODO: Load recent searches from UserDatabase and populate a RecyclerView
                    withContext(Dispatchers.Main) {
                        // We can update the UI here, e.g., show recent searches
                        // For now, just a toast to confirm
                        Toast.makeText(this@MainActivity, "Welcome back, ${user.name}!", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading app data", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error loading app data", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Fetches version info from the server and compares it with local versions.
     */
    private fun checkForUpdates() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.i(TAG, "Checking for updates...")
                // 1. Fetch server version info
                val serverInfo = NetworkManager.fetchVersionInfo()
                if (serverInfo == null) {
                    Log.w(TAG, "Could not fetch server version info. Skipping update.")
                    // Even if update check fails, load the app
                    loadAppData()
                    return@launch
                }

                // 2. Get local versions
                val localTimetableVersion = LocalVersionManager.getTimetableDbVersion(applicationContext)
                val localCommunityVersion = LocalVersionManager.getCommunityDbVersion(applicationContext)

                Log.i(TAG, "Server versions: Timetable=${serverInfo.timetable.version}, Community=${serverInfo.community.version}")
                Log.i(TAG, "Local versions: Timetable=$localTimetableVersion, Community=$localCommunityVersion")

                // 3. Compare versions
                val isTimetableUpdateAvailable = serverInfo.timetable.version > localTimetableVersion
                val isCommunityUpdateAvailable = serverInfo.community.version > localCommunityVersion

                if (isTimetableUpdateAvailable || isCommunityUpdateAvailable) {
                    // 4. Prompt user for update
                    Log.i(TAG, "Updates available. Prompting user.")
                    promptForUpdate(
                        isTimetableUpdateAvailable,
                        isCommunityUpdateAvailable,
                        serverInfo
                    )
                } else {
                    // 5. No updates, just load the app
                    Log.i(TAG, "No new updates. Loading app data.")
                    loadAppData()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in checkForUpdates", e)
                // If update check fails for any reason (e.g., no internet),
                // just log it and load the app with existing data.
                loadAppData()
            }
        }
    }

    /**
     * Shows an AlertDialog to the user asking for permission to download updates.
     */
    private fun promptForUpdate(
        timetable: Boolean,
        community: Boolean,
        serverInfo: ServerVersionInfo
    ) {
        val messages = mutableListOf<String>()
        if (timetable) messages.add("New Bus Timetable")
        if (community) messages.add("Updated Community Ratings")

        val message = "New updates are available:\n\n" +
                messages.joinToString("\n") { "• $it" } +
                "\n\nWould you like to download them now?"

        // We must show the dialog on the Main thread
        lifecycleScope.launch(Dispatchers.Main) {
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Updates Available")
                .setMessage(message)
                .setPositiveButton("Download") { dialog, _ ->
                    Log.i(TAG, "User accepted update. Starting download...")
                    startDatabaseDownload(serverInfo, timetable, community)
                    dialog.dismiss()
                }
                .setNegativeButton("Later") { dialog, _ ->
                    Log.i(TAG, "User declined update.")
                    dialog.dismiss()
                    // Even if user declines, load the app with existing data
                    loadAppData()
                }
                .setCancelable(false) // Don't let user dismiss by tapping outside
                .show()
        }
    }

    /**
     * Kicks off the database download process.
     */
    private fun startDatabaseDownload(
        serverInfo: ServerVersionInfo,
        downloadTimetable: Boolean,
        downloadCommunity: Boolean
    ) {
        // We need to pass these URLs and expected versions to the DatabaseManager
        // to handle the download, atomic swap, and update SharedPreferences.

        // TODO: Show a loading indicator (ProgressBar)
        // binding.textView.text = "Downloading updates..." // Simple progress
        Log.i(TAG, "Starting download coroutine...")

        lifecycleScope.launch {
            var timetableSuccess = true
            var communitySuccess = true

            if (downloadTimetable) {
                Log.i(TAG, "Downloading Timetable DB...")
                timetableSuccess = DatabaseManager.downloadAndReplaceDatabase(
                    applicationContext,
                    DatabaseConstants.TIMETABLE_DATABASE_NAME,
                    serverInfo.timetable.version,
                    serverInfo.timetable.url
                )
            }

            if (downloadCommunity) {
                Log.i(TAG, "Downloading Community DB...")
                communitySuccess = DatabaseManager.downloadAndReplaceDatabase(
                    applicationContext,
                    DatabaseConstants.COMMUNITY_DATABASE_NAME,
                    serverInfo.community.version,
                    serverInfo.community.url
                )
            }

            // TODO: Hide loading indicator
            // binding.textView.text = "Sanchari Bus App" // Reset text

            if (timetableSuccess && communitySuccess) {
                Log.i(TAG, "All databases updated successfully.")
                withContext(Dispatchers.Main) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Update Complete")
                        .setMessage("Your bus timetables and community data are now up to date.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            } else {
                Log.e(TAG, "One or more database downloads failed.")
                withContext(Dispatchers.Main) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Update Failed")
                        .setMessage("Could not download all updates. The app will use the existing data. Please try again later.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }

            // Whether download succeeded or failed, load the app
            loadAppData()
        }
    }
}

