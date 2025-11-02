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
import com.sanchari.bus.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // Register for the UserInfoActivity result
    private val userInfoLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // User saved info, now we can load app data.
            // This will re-check user info, find it, and then trigger checkForUpdates().
            loadAppData()
        } else {
            // User backed out. We must ask them again as user info is required.
            Toast.makeText(this, "User information is required to proceed.", Toast.LENGTH_LONG).show()
            loadAppData() // This will re-trigger the check and launch UserInfoActivity again
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 1. Initialize DBs from assets on first run.
        //    This is the start of our sequential loading chain.
        lifecycleScope.launch(Dispatchers.IO) {
            DatabaseManager.initializeDatabases(applicationContext)

            // After init, proceed to check for user info on the main thread
            withContext(Dispatchers.Main) {
                // 2. Check for user info. This will, in turn, trigger checkForUpdates()
                //    if the user is found.
                loadAppData()
            }
        }

        // 4. Set up listeners
        // FIX: The ID from activity_main.xml is "search_button", so binding is "searchButton"
        binding.searchButton.setOnClickListener {
            handleSearch()
        }

        // 5. Load suggestions (auto-complete for 'from'/'to')
        // We will implement this later
        loadStopSuggestions()
    }

    /**
     * Checks if user info exists.
     * If YES: Proceeds to check for updates.
     * If NO: Launches UserInfoActivity to get it.
     */
    private fun loadAppData() {
        lifecycleScope.launch(Dispatchers.IO) {
            val user = UserDataManager.getUser(applicationContext)
            if (user == null || user.name.isBlank() || user.email.isBlank()) {
                // No user data. Launch UserInfoActivity to get it.
                withContext(Dispatchers.Main) {
                    val intent = Intent(this@MainActivity, UserInfoActivity::class.java)
                    userInfoLauncher.launch(intent)
                }
            } else {
                // User exists. Now we can proceed to check for updates.
                withContext(Dispatchers.Main) {
                    Log.d(TAG, "User info found for ${user.name}. Checking for updates.")
                    // 3. Check for updates (Now called sequentially after user is confirmed)
                    checkForUpdates()
                }
                // TODO: Load recent searches and populate RecyclerView
                // val recents = UserDataManager.getRecentSearches(applicationContext)
                // withContext(Dispatchers.Main) {
                //    binding.recyclerViewRecents.adapter = RecentSearchAdapter(recents)
                // }
            }
        }
    }

    /**
     * Fetches version.json, compares with local, and prompts user if needed.
     */
    private fun checkForUpdates() {
        // Show a simple loading indicator (we'll make this better later)
        // binding.textView.text = "Checking for updates..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val serverInfo = NetworkManager.fetchVersionInfo()
                if (serverInfo == null) {
                    withContext(Dispatchers.Main) {
                        Log.e(TAG, "Failed to fetch version info.")
                        // binding.textView.text = "" // Hide loading
                    }
                    return@launch
                }

                // 2. Get local versions
                val localTimetableVersion = LocalVersionManager.getTimetableDbVersion(applicationContext)
                val localCommunityVersion = LocalVersionManager.getCommunityDbVersion(applicationContext)

                val isTimetableUpdateAvailable = serverInfo.timetable.version > localTimetableVersion
                val isCommunityUpdateAvailable = serverInfo.community.version > localCommunityVersion

                if (isTimetableUpdateAvailable || isCommunityUpdateAvailable) {
                    // 3. Prompt user
                    withContext(Dispatchers.Main) {
                        promptForUpdate(
                            isTimetableUpdateAvailable,
                            isCommunityUpdateAvailable,
                            serverInfo
                        )
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Log.d(TAG, "App is up to date.")
                        // binding.textView.text = "" // Hide loading
                    }
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e(TAG, "Error checking for updates", e)
                    // binding.textView.text = "Update check failed."
                }
            }
        }
    }

    private fun promptForUpdate(
        isTimetableUpdateAvailable: Boolean,
        isCommunityUpdateAvailable: Boolean,
        serverInfo: ServerVersionInfo
    ) {
        val message = buildString {
            append("New updates are available:\n")
            if (isTimetableUpdateAvailable) {
                append("- Bus Timetables\n")
            }
            if (isCommunityUpdateAvailable) {
                append("- Community Ratings & Comments\n")
            }
            append("\nDownload now?")
        }

        AlertDialog.Builder(this)
            .setTitle("Update Available")
            .setMessage(message)
            .setPositiveButton("Download") { dialog, _ ->
                startDatabaseDownload(
                    isTimetableUpdateAvailable,
                    isCommunityUpdateAvailable,
                    serverInfo
                )
                dialog.dismiss()
            }
            .setNegativeButton("Later") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun startDatabaseDownload(
        isTimetableUpdateAvailable: Boolean,
        isCommunityUpdateAvailable: Boolean,
        serverInfo: ServerVersionInfo
    ) {
        // Show loading state
        // binding.textView.text = "Downloading updates..." // Simple progress
        Toast.makeText(this, "Downloading updates...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            var timetableSuccess = true
            var communitySuccess = true

            if (isTimetableUpdateAvailable) {
                // FIX: Correct function name
                timetableSuccess = DatabaseManager.downloadAndReplaceDatabase(
                    applicationContext,
                    DatabaseConstants.TIMETABLE_DATABASE_NAME,
                    serverInfo.timetable.version,
                    serverInfo.timetable.url
                )
            }

            if (isCommunityUpdateAvailable) {
                // FIX: Correct function name
                communitySuccess = DatabaseManager.downloadAndReplaceDatabase(
                    applicationContext,
                    DatabaseConstants.COMMUNITY_DATABASE_NAME,
                    serverInfo.community.version,
                    serverInfo.community.url
                )
            }

            // Report result on main thread
            withContext(Dispatchers.Main) {
                // binding.textView.text = "" // Hide progress
                if (timetableSuccess && communitySuccess) {
                    Toast.makeText(this@MainActivity, "App updated successfully!", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@MainActivity, "Error updating one or more files.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun handleSearch() {
        // Use the correct IDs: fromAutocomplete and toAutocomplete
        val from = binding.fromAutocomplete.text.toString().trim()
        val to = binding.toAutocomplete.text.toString().trim()

        if (from.isBlank() || to.isBlank()) {
            Toast.makeText(this, "Please enter 'From' and 'To' locations.", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            // Corrected function name
            val results = SearchManager.findBusServices(applicationContext, from, to)

            withContext(Dispatchers.Main) {
                if (results.isEmpty()) {
                    Toast.makeText(this@MainActivity, "No routes found.", Toast.LENGTH_SHORT).show()
                } else {
                    // Launch SearchResultsActivity
                    val intent = Intent(this@MainActivity, SearchResultsActivity::class.java).apply {
                        // FIX: Use the correct constant from your SearchResultsActivity
                        putParcelableArrayListExtra(SearchResultsActivity.EXTRA_SEARCH_RESULTS, ArrayList(results))
                    }
                    startActivity(intent)
                }
            }
        }
    }

    private fun loadStopSuggestions() {
        lifecycleScope.launch(Dispatchers.IO) {
            // This function is not implemented in SearchManager yet
            // val suggestions = SearchManager.getStopSuggestions(applicationContext)
            val suggestions = listOf<String>() // Placeholder

            withContext(Dispatchers.Main) {
                val adapter = ArrayAdapter(
                    this@MainActivity,
                    android.R.layout.simple_dropdown_item_1line,
                    suggestions
                )
                // Use the correct IDs: fromAutocomplete and toAutocomplete
                binding.fromAutocomplete.setAdapter(adapter)
                binding.toAutocomplete.setAdapter(adapter)
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
