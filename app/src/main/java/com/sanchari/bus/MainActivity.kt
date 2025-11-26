package com.sanchari.bus

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.sanchari.bus.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var recentSearchAdapter: RecentSearchAdapter
    // Temp variables to hold initial DB versions
    private var initialTimetableVersion = 0
    private var initialCommunityVersion = 0

    companion object {
        private const val TAG = "MainActivity"
        // 7 days in milliseconds: 7 * 24 * 60 * 60 * 1000
        private const val UPDATE_INTERVAL_MS = 604800000L
    }

    // Wrapper class to display BusService nicely in AutoCompleteTextView
    data class BusSearchItem(val service: BusService) {
        override fun toString(): String {
            return "${service.name} (${service.type})"
        }
    }

    // Launcher for UserInfoActivity
    private val userInfoLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Log.i(TAG, "UserInfoActivity finished. Loading app data.")
            loadAppData()
        } else {
            Log.w(TAG, "UserInfoActivity cancelled. User info is still incomplete.")
            Toast.makeText(this, "User information is required. Exiting app.", Toast.LENGTH_SHORT).show()
            finish() // Close MainActivity
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // --- Setup UI ---
        binding.searchButton.setOnClickListener {
            handleSearch()
        }

        // --- ADDED NEW CLICK LISTENER ---
        binding.buttonAddNewBus.setOnClickListener {
            // Launch SuggestEditActivity in "Add New" mode
            val intent = SuggestEditActivity.newIntentForNew(this)
            startActivity(intent)
        }

        binding.buttonCheckForUpdates.setOnClickListener {
            Toast.makeText(this, "Checking for updates...", Toast.LENGTH_SHORT).show()
            // Force update on manual click
            checkForUpdates(forceCheck = true, initialTimetableVersion, initialCommunityVersion)
        }

        binding.buttonShareApp.setOnClickListener {
            val appUrl = LocalVersionManager.getLatestAppUrl(this)
            if (!appUrl.isNullOrBlank()) {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Check out Sanchari Bus App")
                    putExtra(Intent.EXTRA_TEXT, "Download the Sanchari Bus App here: $appUrl")
                }
                startActivity(Intent.createChooser(shareIntent, "Share App via"))
            } else {
                Toast.makeText(this, "Update link not available yet. Please check for updates first.", Toast.LENGTH_LONG).show()
                // Optionally trigger an update check here automatically
                val currentT = LocalVersionManager.getTimetableDbVersion(applicationContext)
                val currentC = LocalVersionManager.getCommunityDbVersion(applicationContext)
                checkForUpdates(forceCheck = true, currentT, currentC)
            }
        }

        setupBusNameSearch()

        setupRecentSearchesRecyclerView()

        // --- Background Initialization ---
        lifecycleScope.launch(Dispatchers.IO) {
            Log.i(TAG, "Initializing databases...")
            DatabaseManager.initializeDatabases(applicationContext)

            retryPendingUploads()

            initialTimetableVersion = LocalVersionManager.getTimetableDbVersion(applicationContext)
            initialCommunityVersion = LocalVersionManager.getCommunityDbVersion(applicationContext)

            Log.i(TAG, "Loading stop suggestions...")
            loadStopSuggestions()

            Log.i(TAG, "Loading app data...")
            withContext(Dispatchers.Main) {
                loadAppData()
            }
        }
    }

    // --- Setup Bus Name AutoComplete ---
    private fun setupBusNameSearch() {
        val adapter = ArrayAdapter<BusSearchItem>(this, android.R.layout.simple_dropdown_item_1line)
        binding.busNameAutocomplete.setAdapter(adapter)

        binding.busNameAutocomplete.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s.toString().trim()
                if (query.length >= 1) { // Start searching after 1 character
                    lifecycleScope.launch(Dispatchers.IO) {
                        val results = SearchManager.searchBusServicesByName(applicationContext, query)
                        val searchItems = results.map { BusSearchItem(it) }

                        withContext(Dispatchers.Main) {
                            adapter.clear()
                            adapter.addAll(searchItems)
                            adapter.notifyDataSetChanged()
                        }
                    }
                }
            }

            override fun afterTextChanged(s: Editable?) {}
        })

        binding.busNameAutocomplete.setOnItemClickListener { parent, _, position, _ ->
            val item = parent.getItemAtPosition(position) as BusSearchItem
            val service = item.service

            // Save to recent views
            lifecycleScope.launch(Dispatchers.IO) {
                UserDataManager.addRecentView(applicationContext, service.serviceId, service.name)
            }

            // Launch Details Activity directly
            val intent = BusDetailsActivity.newIntent(this, service)
            startActivity(intent)

            // Clear the search bar
            binding.busNameAutocomplete.setText("")
        }
    }

    // --- Refresh recents every time the activity becomes visible ---
    override fun onResume() {
        super.onResume()
        refreshRecentSearches()
    }

    private fun refreshRecentSearches() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val recentSearches = UserDataManager.getRecentViews(applicationContext)
                withContext(Dispatchers.Main) {
                    // Ensure adapter is initialized before using it
                    if (::recentSearchAdapter.isInitialized) {
                        recentSearchAdapter.updateData(recentSearches)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing recent searches", e)
            }
        }
    }

    /**
     * Sets up the RecyclerView for recent searches with an empty adapter.
     */
    private fun setupRecentSearchesRecyclerView() {
        // Initialize adapter with an empty list
        recentSearchAdapter = RecentSearchAdapter(emptyList()) { recentSearch ->
            // Handle click on a recent search item
            Log.i(TAG, "Clicked recent search: ${recentSearch.serviceName}")

            // We need a full BusService object to launch BusDetailsActivity
            // We only have a RecentSearch object. We must fetch the full service.
            lifecycleScope.launch(Dispatchers.IO) {
                val service = SearchManager.getBusServiceById(applicationContext, recentSearch.serviceId)

                withContext(Dispatchers.Main) {
                    if (service != null) {
                        // Now we have the full service object, we can launch the activity
                        val intent = BusDetailsActivity.newIntent(this@MainActivity, service)
                        startActivity(intent)
                    } else {
                        // This should ideally not happen if DB is consistent
                        Toast.makeText(this@MainActivity, "Could not find details for this service.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        binding.recentSearchesRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.recentSearchesRecyclerView.adapter = recentSearchAdapter
    }

    /**
     * Loads stop suggestions into the autocomplete fields.
     */
    private suspend fun loadStopSuggestions() {
        val suggestions = SearchManager.getStopSuggestions(applicationContext)
        withContext(Dispatchers.Main) {
            val adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_dropdown_item_1line,
                suggestions
            )
            binding.fromAutocomplete.setAdapter(adapter)
            binding.toAutocomplete.setAdapter(adapter)
        }
    }

    /**
     * Handles the search button click.
     */
    private fun handleSearch() {
        val from = binding.fromAutocomplete.text.toString().trim()
        val to = binding.toAutocomplete.text.toString().trim()

        if (from.isEmpty() || to.isEmpty()) {
            Toast.makeText(this, "Please enter both 'From' and 'To' locations.", Toast.LENGTH_SHORT).show()
            return
        }

        Log.i(TAG, "Searching for routes from '$from' to '$to'")

        lifecycleScope.launch(Dispatchers.IO) {
            val results = SearchManager.findBusServices(applicationContext, from, to)

            withContext(Dispatchers.Main) {
                if (results.isEmpty()) {
                    Toast.makeText(this@MainActivity, "No routes found.", Toast.LENGTH_LONG).show()
                } else {
                    Log.i(TAG, "Found ${results.size} routes. Launching SearchResultsActivity.")
                    val intent = Intent(this@MainActivity, SearchResultsActivity::class.java).apply {
                        putParcelableArrayListExtra(SearchResultsActivity.EXTRA_SEARCH_RESULTS, ArrayList(results))
                    }
                    startActivity(intent)
                }
            }
        }
    }

    /**
     * Main data loading function. Checks for user info, then loads recent searches,
     * and finally checks for updates.
     */
    private fun loadAppData() {
        Log.i(TAG, "loadAppData: Checking for user info...")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val user = UserDataManager.getUser(applicationContext)

                if (user.name.isBlank() || user.email.isBlank() || user.phone.isBlank()) {
                    Log.i(TAG, "User info incomplete. Launching UserInfoActivity.")
                    withContext(Dispatchers.Main) {
                        val intent = Intent(this@MainActivity, UserInfoActivity::class.java)
                        userInfoLauncher.launch(intent)
                    }
                } else {
                    Log.i(TAG, "User info found for ${user.name}. Loading recent searches.")

                    // User info exists, load recent searches
                    val recentSearches = UserDataManager.getRecentViews(applicationContext)
                    withContext(Dispatchers.Main) {
                        recentSearchAdapter.updateData(recentSearches)
                    }

                    // Now check for DB updates (Automatic check = false)
                    checkForUpdates(forceCheck = false, initialTimetableVersion, initialCommunityVersion)
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
     * @param forceCheck If true, ignores the 7-day interval rule.
     */
    private fun checkForUpdates(forceCheck: Boolean, localTimetableVersion: Int, localCommunityVersion: Int) {
        lifecycleScope.launch(Dispatchers.IO) {

            if (!forceCheck) {
                val lastCheckTime = LocalVersionManager.getLastUpdateCheckTime(applicationContext)
                val currentTime = System.currentTimeMillis()
                val timeSinceLastCheck = currentTime - lastCheckTime

                if (timeSinceLastCheck < UPDATE_INTERVAL_MS) {
                    Log.i(TAG, "Skipping update check.")
                    return@launch
                }
            }

            try {
                Log.i(TAG, "Checking for updates from server...")
                val serverInfo = NetworkManager.fetchVersionInfo(applicationContext)

                if (serverInfo == null) return@launch

                LocalVersionManager.saveLastUpdateCheckTime(applicationContext, System.currentTimeMillis())

                if (!serverInfo.versions.isNullOrBlank()) {
                    LocalVersionManager.saveVersionsUrl(applicationContext, serverInfo.versions)
                }
                if (!serverInfo.communityData.isNullOrBlank()) {
                    LocalVersionManager.saveCommunityUrl(applicationContext, serverInfo.communityData)
                }

                if (serverInfo.app != null && !serverInfo.app.url.isNullOrBlank()) {
                    LocalVersionManager.saveLatestAppUrl(applicationContext, serverInfo.app.url)
                }

                // --- NEW: CHECK FOR APP UPDATE ---
                // Use BuildConfig.VERSION_CODE which is generated by Gradle
                val currentAppVersion = BuildConfig.VERSION_CODE
                val serverAppInfo = serverInfo.app

                var appUpdateAvailable = false
                if (serverAppInfo != null && serverAppInfo.versionCode > currentAppVersion) {
                    appUpdateAvailable = true
                }
                // ---------------------------------

                val localTimetableVersion = LocalVersionManager.getTimetableDbVersion(applicationContext)
                val localCommunityVersion = LocalVersionManager.getCommunityDbVersion(applicationContext)

                Log.i(TAG, "Server versions: Timetable=${serverInfo.timetable.version}, Community=${serverInfo.community.version}")
                Log.i(TAG, "Local versions: Timetable=$localTimetableVersion, Community=$localCommunityVersion")

                val isTimetableUpdateAvailable = serverInfo.timetable.version > localTimetableVersion
                val isCommunityUpdateAvailable = serverInfo.community.version > localCommunityVersion

                // Modified condition to include appUpdateAvailable
                if (isTimetableUpdateAvailable || isCommunityUpdateAvailable || appUpdateAvailable) {
                    Log.i(TAG, "Updates available. Prompting user.")
                    withContext(Dispatchers.Main) {
                        promptForUpdate(
                            isTimetableUpdateAvailable,
                            isCommunityUpdateAvailable,
                            appUpdateAvailable, // Pass the new flag
                            serverInfo
                        )
                    }
                } else {
                    Log.i(TAG, "No new updates.")
                    if (forceCheck) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "App is up to date.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in checkForUpdates", e)
            }
        }
    }


    /**
     * Shows an AlertDialog to the user asking for permission to download updates.
     */
    private fun promptForUpdate(
        timetable: Boolean,
        community: Boolean,
        appUpdate: Boolean, // Added parameter
        serverInfo: ServerVersionInfo
    ) {
        val messages = mutableListOf<String>()
        if (appUpdate) messages.add("New App Version") // Added message
        if (timetable) messages.add("New Bus Timetable")
        if (community) messages.add("Updated Community Ratings")

        val message = "New updates are available:\n\n" +
                messages.joinToString("\n") { "• $it" } +
                "\n\nDownload now?"

        AlertDialog.Builder(this@MainActivity)
            .setTitle("Updates Available")
            .setMessage(message)
            .setPositiveButton("Download") { dialog, _ ->
                // Added App Update Logic
                if (appUpdate && serverInfo.app != null) {
                    downloadAndInstallApp(serverInfo.app.url)
                }

                // Only download DBs if needed
                if (timetable || community) {
                    Log.i(TAG, "User accepted update. Starting download...")
                    startDatabaseDownload(serverInfo, timetable, community)
                }
                dialog.dismiss()
            }
            .setNegativeButton("Later") { dialog, _ ->
                Log.i(TAG, "User declined update.")
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Kicks off the database download process.
     */
    private fun startDatabaseDownload(
        serverInfo: ServerVersionInfo,
        downloadTimetable: Boolean,
        downloadCommunity: Boolean
    ) {
        Log.i(TAG, "Starting download coroutine...")

        lifecycleScope.launch(Dispatchers.IO) {
            var timetableSuccess = true
            var communitySuccess = true

            if (downloadTimetable) {
                Log.i(TAG, "Downloading Timetable DB...")
                timetableSuccess = DatabaseManager.downloadAndReplaceDatabase(
                    applicationContext,
                    DatabaseConstants.TIMETABLE_DATABASE_NAME, // Corrected constant
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
        }
    }

    private suspend fun retryPendingUploads() {
        val pendingFiles = SuggestionStorageManager.getPendingSuggestions(applicationContext)
        if (pendingFiles.isEmpty()) return

        Log.i(TAG, "Found ${pendingFiles.size} pending suggestions. Attempting to sync...")
        var failureCount = 0

        for (file in pendingFiles) {
            try {
                val jsonPayload = file.readText()
                val success = NetworkManager.uploadDataToGoogleSheet(applicationContext, jsonPayload)

                if (success) {
                    Log.i(TAG, "Successfully synced: ${file.name}")
                    SuggestionStorageManager.deleteSuggestion(file)
                } else {
                    Log.e(TAG, "Failed to sync: ${file.name}")
                    failureCount++
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing pending file: ${file.name}", e)
                failureCount++
            }
        }

        if (failureCount > 0) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Failed to sync $failureCount offline suggestion(s).", Toast.LENGTH_LONG).show()
            }
        } else {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Synced all offline suggestions.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun downloadAndInstallApp(apkUrl: String) {
        Toast.makeText(this, "Downloading new app version...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Save APK to the cache directory which we exposed via FileProvider
                val apkFile = File(cacheDir, "update.apk")
                if (apkFile.exists()) apkFile.delete()

                val success = NetworkManager.downloadFile(apkUrl, apkFile)

                if (success) {
                    withContext(Dispatchers.Main) {
                        installApk(apkFile)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Download failed.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading APK", e)
            }
        }
    }

    private fun installApk(file: File) {
        try {
            // Create URI using the authority defined in Manifest
            val uri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.provider",
                file
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error installing APK", e)
            Toast.makeText(this, "Error launching installer.", Toast.LENGTH_SHORT).show()
        }
    }
}