package com.sanchari.bus.ui.activity

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
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
import com.sanchari.bus.BuildConfig // Added BuildConfig import
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

    // Launcher for UserInfoActivity
    private val userInfoLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
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

        // Initialize Managers
        appUpdateManager = AppUpdateManager(this)
        uploadManager = UploadManager(this)
        busSearchHandler = BusSearchHandler(this, binding)
        mainSubmissionHandler = MainSubmissionHandler(this)

        // --- Setup UI ---
        busSearchHandler.setup() // Sets up search bars and buttons

        // --- ADDED NEW CLICK LISTENER ---
        binding.buttonAddNewBus.setOnClickListener {
            val intent = SuggestEditActivity.newIntentForNew(this)
            startActivity(intent)
        }

        binding.buttonCheckForUpdates.setOnClickListener {
            Toast.makeText(this, "Checking for updates...", Toast.LENGTH_SHORT).show()
            // Force update on manual click
            appUpdateManager.checkForUpdates(forceCheck = true, initialTimetableVersion, initialCommunityVersion)
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
                            appUpdateManager.checkForUpdates(forceCheck = true, currentT, currentC)
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

                    val recentSearches = UserDataManager.getRecentViews(applicationContext)
                    withContext(Dispatchers.Main) {
                        recentSearchAdapter.updateData(recentSearches)
                    }

                    // Now check for DB updates (Automatic check = false)
                    appUpdateManager.checkForUpdates(forceCheck = false, initialTimetableVersion, initialCommunityVersion)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading app data", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error loading app data", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}