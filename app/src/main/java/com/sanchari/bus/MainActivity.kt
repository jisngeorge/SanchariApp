package com.sanchari.bus

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.sanchari.bus.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var recentSearchAdapter: RecentSearchAdapter

    companion object {
        private const val TAG = "MainActivity"
    }

    // Launcher for UserInfoActivity
    private val userInfoLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Log.i(TAG, "UserInfoActivity finished. Loading app data.")
            loadAppData()
        } else {
            // --- FIXED: Break the loop ---
            // If user cancels the mandatory setup, show a message and close the app.
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
            checkForUpdates()
        }

        setupRecentSearchesRecyclerView()

        // --- Background Initialization ---
        lifecycleScope.launch(Dispatchers.IO) {
            Log.i(TAG, "Initializing databases...")
            DatabaseManager.initializeDatabases(applicationContext)

            Log.i(TAG, "Loading stop suggestions...")
            loadStopSuggestions()

            Log.i(TAG, "Loading app data...")
            withContext(Dispatchers.Main) {
                loadAppData()
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
                    withContext(Dispatchers.Main) { // Corrected Dispatchers.Main
                        val intent = Intent(this@MainActivity, UserInfoActivity::class.java)
                        userInfoLauncher.launch(intent)
                    }
                } else {
                    Log.i(TAG, "User info found for ${user.name}. Loading recent searches.")

                    // User info exists, load recent searches
                    val recentSearches = UserDataManager.getRecentViews(applicationContext)
                    withContext(Dispatchers.Main) {
                        recentSearchAdapter.updateData(recentSearches)
                        // TODO: Show/hide a "No recent searches" text view
                    }

                    // REMOVED: checkForUpdates() call to stop auto-update on launch
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
                val serverInfo = NetworkManager.fetchVersionInfo()
                if (serverInfo == null) {
                    Log.w(TAG, "Could not fetch server version info. Skipping update.")
                    return@launch
                }

                val localTimetableVersion = LocalVersionManager.getTimetableDbVersion(applicationContext)
                val localCommunityVersion = LocalVersionManager.getCommunityDbVersion(applicationContext)

                Log.i(TAG, "Server versions: Timetable=${serverInfo.timetable.version}, Community=${serverInfo.community.version}")
                Log.i(TAG, "Local versions: Timetable=$localTimetableVersion, Community=$localCommunityVersion")

                val isTimetableUpdateAvailable = serverInfo.timetable.version > localTimetableVersion
                val isCommunityUpdateAvailable = serverInfo.community.version > localCommunityVersion

                if (isTimetableUpdateAvailable || isCommunityUpdateAvailable) {
                    Log.i(TAG, "Updates available. Prompting user.")
                    withContext(Dispatchers.Main) {
                        promptForUpdate(
                            isTimetableUpdateAvailable,
                            isCommunityUpdateAvailable,
                            serverInfo
                        )
                    }
                } else {
                    Log.i(TAG, "No new updates.")
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
        serverInfo: ServerVersionInfo
    ) {
        val messages = mutableListOf<String>()
        if (timetable) messages.add("New Bus Timetable")
        if (community) messages.add("Updated Community Ratings")

        val message = "New updates are available:\n\n" +
                messages.joinToString("\n") { "• $it" } +
                "\n\nWould you like to download them now?"

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
}