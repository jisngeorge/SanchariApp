package com.sanchari.bus.ui.activity

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.doOnLayout
import android.widget.LinearLayout
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.color.MaterialColors
import com.sanchari.bus.databinding.ActivityMainBinding
import com.sanchari.bus.ui.helper.AppUpdateManager
import com.sanchari.bus.ui.helper.BusSearchHandler
import com.sanchari.bus.ui.helper.StopLocationHandler
import com.sanchari.bus.data.local.DatabaseManager
import com.sanchari.bus.data.local.LocalVersionManager
import com.sanchari.bus.ui.adapter.RecentSearchAdapter
import com.sanchari.bus.data.manager.SearchManager
import com.sanchari.bus.ui.helper.UploadManager
import com.sanchari.bus.data.manager.UserDataManager
import com.sanchari.bus.ui.helper.MainSubmissionHandler
import com.sanchari.bus.data.model.AppConfig
import com.sanchari.bus.data.model.SubmissionLog
import com.sanchari.bus.BuildConfig
import com.sanchari.bus.R
import com.sanchari.bus.ui.helper.DraftManager
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var recentSearchAdapter: RecentSearchAdapter

    // Refactored Managers
    private lateinit var appUpdateManager: AppUpdateManager
    private lateinit var uploadManager: UploadManager
    private lateinit var busSearchHandler: BusSearchHandler
    private lateinit var stopLocationHandler: StopLocationHandler
    private lateinit var mainSubmissionHandler: MainSubmissionHandler

    private var dataInitialized = false

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
        busSearchHandler = BusSearchHandler(this, binding, addSearchMetadata = { intent, from, to ->
            stopLocationHandler.addSearchMetadata(intent, from, to)
        })
        mainSubmissionHandler = MainSubmissionHandler(this)

        // --- Setup UI ---
        busSearchHandler.setup() // Sets up search bars and buttons
        busSearchHandler.restoreSelectedStop(savedInstanceState?.getBoolean("selected_stop_is_from", true) ?: true)
        stopLocationHandler = StopLocationHandler(this, binding, busSearchHandler::validateMapStop,
            selectedStopIsFrom = { busSearchHandler.selectedStopIsFrom })
        stopLocationHandler.setup(savedInstanceState)

        updateAvailabilityUI(LocalVersionManager.isUpdateAvailable(this))

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
            stopLocationHandler.swapFields()
        }

        // --- ADDED NEW CLICK LISTENER ---
        binding.buttonAddNewBus.setOnClickListener {
            val intent = SuggestEditActivity.newIntentForNew(this)
            startActivity(intent)
        }

        binding.buttonDrafts.setOnClickListener {
            showDraftsAndSubmissions()
        }

        binding.homeOverflowButton.setOnClickListener { showHomeOptions() }
        binding.homePrimaryActions.doOnLayout { actions ->
            val stacked = resources.configuration.fontScale > 1.2f ||
                actions.width / resources.displayMetrics.density < 328
            binding.homePrimaryActions.orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            for (button in listOf(binding.buttonAddNewBus, binding.buttonDrafts)) {
                button.layoutParams = (button.layoutParams as LinearLayout.LayoutParams).apply {
                    width = if (stacked) ViewGroup.LayoutParams.MATCH_PARENT else 0
                    weight = if (stacked) 0f else 1f
                    if (stacked) {
                        marginStart = 0
                        marginEnd = 0
                    }
                }
            }
        }

        setupRecentSearchesRecyclerView()

        // --- Background Initialization ---
        lifecycleScope.launch(Dispatchers.IO) {
            Log.i(TAG, "Initializing databases...")
            DatabaseManager.initializeDatabases(applicationContext)
            launch { uploadManager.retryPendingUploads() }
            busSearchHandler.loadStopSuggestions()
            withContext(Dispatchers.Main) { loadAppData() }
        }
    }

    private fun showHomeOptions() {
        PopupMenu(this, binding.homeOverflowButton).apply {
            inflate(R.menu.menu_main)
            menu.findItem(R.id.action_auto_updates).isChecked =
                LocalVersionManager.autoDatabaseUpdatesEnabled(this@MainActivity)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_check_updates -> checkForUpdatesManually()
                    R.id.action_auto_updates -> {
                        val enabled = !item.isChecked
                        LocalVersionManager.setAutoDatabaseUpdatesEnabled(this@MainActivity, enabled)
                        if (!enabled) appUpdateManager.cancelAutomaticUpdates()
                    }
                    R.id.action_share_app -> shareApp()
                    R.id.action_message_admin -> mainSubmissionHandler.showMessageAdminDialog()
                    R.id.action_version_info -> showVersionInfoDialog()
                    else -> return@setOnMenuItemClickListener false
                }
                true
            }
            show()
        }
    }

    private fun checkForUpdatesManually() {
        Toast.makeText(this, "Checking for updates...", Toast.LENGTH_SHORT).show()
        appUpdateManager.checkForUpdates(
            forceCheck = true,
            localTimetableVersion = LocalVersionManager.getTimetableDbVersion(applicationContext),
            localCommunityVersion = LocalVersionManager.getCommunityDbVersion(applicationContext),
            onUpdateAvailable = ::updateAvailabilityUI,
            onUpdateComplete = ::onUpdatesCompleted
        )
    }

    private fun shareApp() {
        val appUrl = LocalVersionManager.getLatestAppUrl(this)
        if (!appUrl.isNullOrBlank()) {
            shareAppUrl(appUrl)
        } else {
            lifecycleScope.launch(Dispatchers.IO) {
                val assetUrl = getAppUrlFromAssets()
                withContext(Dispatchers.Main) {
                    if (!assetUrl.isNullOrBlank()) {
                        shareAppUrl(assetUrl)
                        LocalVersionManager.saveLatestAppUrl(this@MainActivity, assetUrl)
                    } else {
                        Toast.makeText(this@MainActivity, "Update link not available yet. Please check for updates first.", Toast.LENGTH_LONG).show()
                        checkForUpdatesManually()
                    }
                }
            }
        }
    }

    private fun shareAppUrl(url: String) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Check out Sanchari Bus App")
            putExtra(Intent.EXTRA_TEXT, "Download the Sanchari Bus App here: $url")
        }
        startActivity(Intent.createChooser(shareIntent, "Share App via"))
    }

    private fun onUpdatesCompleted() {
        updateAvailabilityUI(LocalVersionManager.isUpdateAvailable(this))
        lifecycleScope.launch {
            busSearchHandler.loadStopSuggestions()
        }
    }

    private fun showDraftsAndSubmissions() {
        val drafts = DraftManager.getDrafts(this)
        val hasLogs = UserDataManager.getSubmissionLogs(this).isNotEmpty()

        if (drafts.isEmpty() && !hasLogs) {
            Toast.makeText(this, "No drafts or submissions yet.", Toast.LENGTH_SHORT).show()
            return
        }

        if (drafts.isEmpty() && hasLogs) {
            showSubmissionLogs(UserDataManager.getSubmissionLogs(this))
            return
        }

        val displayNames = drafts.map { it.displayName }.toTypedArray()

        val builder = AlertDialog.Builder(this)
            .setTitle("Saved Drafts")
            .setItems(displayNames) { _, which ->
                // Open SuggestEditActivity — user can load draft from history there
                val intent = SuggestEditActivity.newIntentForNew(this)
                intent.putExtra(SuggestEditActivity.EXTRA_LOAD_DRAFT_FILE, drafts[which].fileName)
                startActivity(intent)
            }
            .setNegativeButton("Close", null)

        if (hasLogs) {
            builder.setNeutralButton("Submissions") { _, _ ->
                showSubmissionLogs(UserDataManager.getSubmissionLogs(this))
            }
        }

        builder.show()
    }

    private fun showSubmissionLogs(logs: List<SubmissionLog>) {
        val dateFormat = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
        val items = logs.map { log ->
            val date = dateFormat.format(Date(log.submittedAt))
            "${log.busName} (${log.busType}) - ${log.startingPlace}\n$date"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Submission History")
            .setItems(items, null)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun updateAvailabilityUI(isUpdateAvailable: Boolean) {
        binding.homeOverflowButton.isSelected = isUpdateAvailable
        binding.homeOverflowButton.contentDescription = getString(
            if (isUpdateAvailable) R.string.home_more_options_update else R.string.home_more_options)
        if (isUpdateAvailable) {
            binding.homeOverflowButton.imageTintList =
                AppCompatResources.getColorStateList(this, R.color.primary_action_text)
        } else {
            binding.homeOverflowButton.imageTintList = ColorStateList.valueOf(
                MaterialColors.getColor(binding.root, androidx.appcompat.R.attr.colorPrimary))
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
            .setTitle(R.string.version_information)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        busSearchHandler.resumeSuggestions()
        stopLocationHandler.onResume()
        refreshRecentSearches()
        if (dataInitialized) checkAutomaticUpdates()
    }

    override fun onPause() {
        appUpdateManager.onPause()
        stopLocationHandler.onPause()
        busSearchHandler.pauseSuggestions()
        super.onPause()
    }

    override fun onStop() {
        stopLocationHandler.onStop(isChangingConfigurations)
        super.onStop()
    }

    override fun onDestroy() {
        stopLocationHandler.destroy()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("selected_stop_is_from", busSearchHandler.selectedStopIsFrom)
        super.onSaveInstanceState(outState)
        stopLocationHandler.saveState(outState)
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
                    dataInitialized = true
                    checkAutomaticUpdates()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading app data", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error loading app data", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun checkAutomaticUpdates() {
        appUpdateManager.checkForUpdates(
            forceCheck = false,
            localTimetableVersion = LocalVersionManager.getTimetableDbVersion(this),
            localCommunityVersion = LocalVersionManager.getCommunityDbVersion(this),
            onUpdateAvailable = ::updateAvailabilityUI,
            onUpdateComplete = ::onUpdatesCompleted
        )
    }
}