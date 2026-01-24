package com.sanchari.bus.ui.activity

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.color.MaterialColors
import com.sanchari.bus.databinding.ActivityMainBinding
import com.sanchari.bus.ui.helper.AppUpdateManager
import com.sanchari.bus.ui.helper.BusSearchHandler
import com.sanchari.bus.data.local.DatabaseManager
import com.sanchari.bus.data.local.LocalVersionManager
import com.sanchari.bus.ui.adapter.RecentSearchAdapter
import com.sanchari.bus.data.manager.SearchManager
import com.sanchari.bus.ui.helper.UploadManager
import com.sanchari.bus.data.manager.UserDataManager
import com.sanchari.bus.ui.helper.MainSubmissionHandler
import com.sanchari.bus.data.model.AppConfig
import com.sanchari.bus.BuildConfig
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var recentSearchAdapter: RecentSearchAdapter

    // Refactored Managers
    private lateinit var appUpdateManager: AppUpdateManager
    private lateinit var uploadManager: UploadManager
    private lateinit var busSearchHandler: BusSearchHandler
    private lateinit var mainSubmissionHandler: MainSubmissionHandler

    // Temp variables to hold initial DB versions
    private var initialTimetableVersion = 0
    private var initialCommunityVersion = 0

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize Managers
        appUpdateManager = AppUpdateManager(this)
        uploadManager = UploadManager(this)
        busSearchHandler = BusSearchHandler(this, binding)
        mainSubmissionHandler = MainSubmissionHandler(this)

        // --- Setup UI ---
        busSearchHandler.setup() // Sets up search bars and buttons

        // --- NEW: Check persisted update state immediately ---
        if (LocalVersionManager.isUpdateAvailable(this)) {
            updateUpdateButtonUI(true)
        }
        // ----------------------------------------------------

        // --- NEW: Check First Run & Show Intro Activity ---
        if (LocalVersionManager.isFirstRun(this)) {
            // Launch IntroductionActivity in "Intro Mode" (no data entry required)
            val intent = Intent(this, IntroductionActivity::class.java).apply {
                putExtra("EXTRA_IS_INTRO", true)
            }
            startActivity(intent)
            LocalVersionManager.setFirstRunCompleted(this)
        }
        // ----------------------------------------

        binding.buttonSwapLocations.setOnClickListener {
            val fromText = binding.fromAutocomplete.text.toString()
            val toText = binding.toAutocomplete.text.toString()

            // Swap values
            binding.fromAutocomplete.setText(toText)
            binding.toAutocomplete.setText(fromText)

            // Prevent dropdowns from showing immediately after swap
            binding.fromAutocomplete.dismissDropDown()
            binding.toAutocomplete.dismissDropDown()
        }

        // --- ADDED NEW CLICK LISTENER ---
        binding.buttonAddNewBus.setOnClickListener {
            val intent = SuggestEditActivity.newIntentForNew(this)
            startActivity(intent)
        }

        binding.buttonCheckForUpdates.setOnClickListener {
            Toast.makeText(this, "Checking for updates...", Toast.LENGTH_SHORT).show()
            // Force update on manual click
            val currentT = LocalVersionManager.getTimetableDbVersion(applicationContext)
            val currentC = LocalVersionManager.getCommunityDbVersion(applicationContext)
            // Fix: Use named argument for the lambda to resolve ambiguity
            appUpdateManager.checkForUpdates(
                forceCheck = true,
                localTimetableVersion = currentT,
                localCommunityVersion = currentC,
                onUpdateAvailable = { isAvailable ->
                    updateUpdateButtonUI(isAvailable)
                },
                onUpdateComplete = {
                    updateUpdateButtonUI(false) // Reset UI on success
                }
            )
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
                // Fallback: Try reading from assets if SharedPreferences is empty
                lifecycleScope.launch(Dispatchers.IO) {
                    val assetUrl = getAppUrlFromAssets()
                    withContext(Dispatchers.Main) {
                        if (!assetUrl.isNullOrBlank()) {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Check out Sanchari Bus App")
                                putExtra(Intent.EXTRA_TEXT, "Download the Sanchari Bus App here: $assetUrl")
                            }
                            startActivity(Intent.createChooser(shareIntent, "Share App via"))
                            // Save it for next time to avoid file IO
                            LocalVersionManager.saveLatestAppUrl(this@MainActivity, assetUrl)
                        } else {
                            Toast.makeText(this@MainActivity, "Update link not available yet. Please check for updates first.", Toast.LENGTH_LONG).show()
                            val currentT = LocalVersionManager.getTimetableDbVersion(applicationContext)
                            val currentC = LocalVersionManager.getCommunityDbVersion(applicationContext)
                            // Fix: Use named argument
                            appUpdateManager.checkForUpdates(
                                forceCheck = true,
                                localTimetableVersion = currentT,
                                localCommunityVersion = currentC,
                                onUpdateAvailable = { isAvailable ->
                                    updateUpdateButtonUI(isAvailable)
                                },
                                onUpdateComplete = {
                                    updateUpdateButtonUI(false)
                                }
                            )
                        }
                    }
                }
            }
        }

        // --- NEW: Long Click to show Version Info ---
        binding.buttonShareApp.setOnLongClickListener {
            showVersionInfoDialog()
            true // Consume the click
        }
        // --------------------------------------------

        binding.buttonMessageAdmin.setOnClickListener {
            mainSubmissionHandler.showMessageAdminDialog()
        }

        setupRecentSearchesRecyclerView()

        // --- Background Initialization ---
        lifecycleScope.launch(Dispatchers.IO) {
            Log.i(TAG, "Initializing databases...")
            DatabaseManager.initializeDatabases(applicationContext)

            // 1. Sync Offline Data
            uploadManager.retryPendingUploads()

            // 2. Capture Initial Versions
            initialTimetableVersion = LocalVersionManager.getTimetableDbVersion(applicationContext)
            initialCommunityVersion = LocalVersionManager.getCommunityDbVersion(applicationContext)

            // 3. Load Suggestions
            Log.i(TAG, "Loading stop suggestions...")
            busSearchHandler.loadStopSuggestions()

            // 4. Load App Data (Recents & Auto-Update Check)
            Log.i(TAG, "Loading app data...")
            withContext(Dispatchers.Main) {
                loadAppData()
            }
        }
    }

    private fun updateUpdateButtonUI(isUpdateAvailable: Boolean) {
        if (isUpdateAvailable) {
            binding.buttonCheckForUpdates.setTextColor(Color.WHITE)
            binding.buttonCheckForUpdates.backgroundTintList = ColorStateList.valueOf("#4CAF50".toColorInt()) // Green
        } else {
            // Fix: Explicitly use com.google.android.material.R.attr.colorPrimary to resolve unresolved reference
            val colorPrimary = MaterialColors.getColor(binding.root, androidx.constraintlayout.widget.R.attr.colorPrimary)
            binding.buttonCheckForUpdates.setTextColor(colorPrimary)
            // Reset background tint to null to restore default OutlinedButton behavior (transparent background)
            binding.buttonCheckForUpdates.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        }
    }

    private fun showVersionInfoDialog() {
        val appVersionName = BuildConfig.VERSION_NAME
        val appVersionCode = BuildConfig.VERSION_CODE
        val tVersion = LocalVersionManager.getTimetableDbVersion(applicationContext)
        val cVersion = LocalVersionManager.getCommunityDbVersion(applicationContext)

        val message = """
            App Version: $appVersionName ($appVersionCode)
            Timetable DB: v$tVersion
            Community DB: v$cVersion
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("Version Information")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        refreshRecentSearches()
    }

    private fun refreshRecentSearches() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val recentSearches = UserDataManager.getRecentViews(applicationContext)
                withContext(Dispatchers.Main) {
                    if (::recentSearchAdapter.isInitialized) {
                        recentSearchAdapter.updateData(recentSearches)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing recent searches", e)
            }
        }
    }

    private fun getAppUrlFromAssets(): String? {
        return try {
            val jsonString = assets.open("app_config.json").bufferedReader().use { it.readText() }
            val config = Json { ignoreUnknownKeys = true }.decodeFromString<AppConfig>(jsonString)
            config.latestAppUrl
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read app_config.json from MainActivity", e)
            null
        }
    }

    /**
     * Sets up the RecyclerView for recent searches with an empty adapter.
     */
    private fun setupRecentSearchesRecyclerView() {
        recentSearchAdapter = RecentSearchAdapter(emptyList()) { recentSearch ->
            Log.i(TAG, "Clicked recent search: ${recentSearch.serviceName}")
            lifecycleScope.launch(Dispatchers.IO) {

                // --- NEW: Update timestamp by re-adding it ---
                UserDataManager.addRecentView(
                    applicationContext,
                    recentSearch.serviceId,
                    recentSearch.serviceName
                )
                // ---------------------------------------------

                val service = SearchManager.getBusServiceById(applicationContext, recentSearch.serviceId)
                withContext(Dispatchers.Main) {
                    if (service != null) {
                        val intent = BusDetailsActivity.newIntent(this@MainActivity, service)
                        startActivity(intent)
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "Could not find details for this service.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
        binding.recentSearchesRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.recentSearchesRecyclerView.adapter = recentSearchAdapter
    }

    /**
     * Main data loading function. Checks for user info, then loads recent searches,
     * and finally checks for updates.
     */
    private fun loadAppData() {
        Log.i(TAG, "loadAppData: Loading data without forced user info check...")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // We no longer verify user info here.
                // We proceed directly to loading content and checking updates.

                val recentSearches = UserDataManager.getRecentViews(applicationContext)
                withContext(Dispatchers.Main) {
                    recentSearchAdapter.updateData(recentSearches)
                }

                // Now check for DB updates (Automatic check = false)
                // Fix: Use named argument
                appUpdateManager.checkForUpdates(
                    forceCheck = false,
                    localTimetableVersion = initialTimetableVersion,
                    localCommunityVersion = initialCommunityVersion,
                    onUpdateAvailable = { isAvailable ->
                        updateUpdateButtonUI(isAvailable)
                    },
                    onUpdateComplete = {
                        updateUpdateButtonUI(false)
                    }
                )

            } catch (e: Exception) {
                Log.e(TAG, "Error loading app data", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error loading app data", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}