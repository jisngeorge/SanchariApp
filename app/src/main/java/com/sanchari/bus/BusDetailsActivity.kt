package com.sanchari.bus

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.RatingBar // Ensure this import is here
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.checkbox.MaterialCheckBox
import com.sanchari.bus.databinding.ActivityBusDetailsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
// --- NEW IMPORTS ---
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
// --- END NEW IMPORTS ---

class BusDetailsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBusDetailsBinding
    private var busService: BusService? = null
    private lateinit var busStopAdapter: BusStopAdapter
    private lateinit var commentAdapter: CommentAdapter

    // Store the user's details for pre-filling the form
    private var currentUser: User? = null

    companion object {
        private const val EXTRA_BUS_SERVICE = "EXTRA_BUS_SERVICE"
        private const val TAG = "BusDetailsActivity"

        // --- REMOVED GOOGLE FORM URLS ---

        fun newIntent(context: Context, service: BusService): Intent {
            return Intent(context, BusDetailsActivity::class.java).apply {
                putExtra(EXTRA_BUS_SERVICE, service)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBusDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set up the toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Bus Details" // Updated title

        // Get the bus service details
        busService = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_BUS_SERVICE, BusService::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_BUS_SERVICE)
        }

        if (busService == null) {
            Toast.makeText(this, "Error: Bus details not found.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Set bus service name on toolbar
        supportActionBar?.title = busService?.name ?: "Bus Details"
        supportActionBar?.subtitle = busService?.type

        setupRecyclerViews()

        // --- NEW: Setup the Running Status UI ---
        setupRunningStatusUI()
        // --- END OF NEW ---

        // Load all data
        loadBusStops()
        loadCommunityData()
        loadLocalUserData() // For pre-filling forms

        // Setup button listeners
        binding.buttonAddRating.setOnClickListener {
            // --- UPDATED ---
            showAddRatingDialog() // NEW Method
        }
        binding.buttonAddComment.setOnClickListener {
            // --- UPDATED ---
            showAddCommentDialog() // NEW Method
        }

        // --- ADDED NEW CLICK LISTENER ---
        binding.buttonSuggestEdit.setOnClickListener {
            busService?.let {
                val intent = SuggestEditActivity.newIntentForEdit(this, it)
                startActivity(intent)
            }
        }
        // --- END OF ADDITION ---
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun setupRecyclerViews() {
        // Bus Stops Adapter
        busStopAdapter = BusStopAdapter(emptyList())
        binding.recyclerViewBusStops.apply {
            layoutManager = LinearLayoutManager(this@BusDetailsActivity)
            adapter = busStopAdapter
            // Disable nested scrolling as we are in a NestedScrollView
            isNestedScrollingEnabled = false
        }

        // Comments Adapter
        commentAdapter = CommentAdapter(emptyList())
        binding.commentsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@BusDetailsActivity)
            adapter = commentAdapter
            // Disable nested scrolling
            isNestedScrollingEnabled = false
        }
    }

    // --- NEW FUNCTION ---
    /**
     * Sets up the "Is Running" toggle group based on the busService data.
     */
    private fun setupRunningStatusUI() {
        val service = busService ?: return

        // 1. Set the initial highlighted button
        if (service.isRunning) {
            binding.toggleGroupStatus.check(R.id.buttonStatusYes)
        } else {
            binding.toggleGroupStatus.check(R.id.buttonStatusNo)
        }

        // 2. Display the last reported time
        if (service.lastReportedTime > 0) {
            try {
                // --- FIX: Multiply Unix time (seconds) by 1000L to get milliseconds ---
                val date = Date(service.lastReportedTime * 1000L)
                val sdf = SimpleDateFormat("dd-MMM-yyyy", Locale.getDefault())
                binding.textViewLastReported.text = "Last reported: ${sdf.format(date)}"
                binding.textViewLastReported.visibility = View.VISIBLE
            } catch (e: Exception) {
                Log.e(TAG, "Error formatting lastReportedTime", e)
                binding.textViewLastReported.visibility = View.GONE
            }
        } else {
            binding.textViewLastReported.visibility = View.GONE
        }

        // 3. Add the click listener
        binding.toggleGroupStatus.addOnButtonCheckedListener { group, checkedId, isChecked ->
            if (!isChecked) {
                // This event fires when a button is *unchecked* (e.g., during a swap)
                // We only care about the one that is *checked*
                return@addOnButtonCheckedListener
            }

            val isNowRunning = checkedId == R.id.buttonStatusYes
            val wasRunning = service.isRunning

            // Only proceed if the user is suggesting a *change*
            if (isNowRunning != wasRunning) {
                // Show the warning popup
                showRunningStatusWarningDialog(isNowRunning)
            }
        }
    }
    // --- END OF NEW FUNCTION ---

    /**
     * Loads the bus stops from TimetableDatabase.
     */
    private fun loadBusStops() {
        val serviceId = busService?.serviceId ?: return
        showLoading(true) // Show main loading spinner

        lifecycleScope.launch(Dispatchers.IO) {
            val stops = SearchManager.getBusStops(applicationContext, serviceId)

            withContext(Dispatchers.Main) {
                showLoading(false) // Hide spinner once stops are loaded
                if (stops.isEmpty()) {
                    // Show error in the *stops* section
                    binding.recyclerViewBusStops.visibility = View.GONE
                    binding.textViewError.visibility = View.VISIBLE
                    binding.textViewError.text = "No stops found for this service."
                } else {
                    binding.recyclerViewBusStops.visibility = View.VISIBLE
                    binding.textViewError.visibility = View.GONE
                    busStopAdapter.updateStops(stops)
                }
            }
        }
    }

    /**
     * Loads ratings and comments from CommunityDatabase.
     */
    private fun loadCommunityData() {
        val serviceId = busService?.serviceId ?: return

        lifecycleScope.launch(Dispatchers.IO) {
            // Fetch rating and comments in parallel
            val ratingDeferred = async { CommunityDataManager.getBusRating(applicationContext, serviceId) }
            val commentsDeferred = async { CommunityDataManager.getComments(applicationContext, serviceId) }

            val rating = ratingDeferred.await()
            val comments = commentsDeferred.await()

            withContext(Dispatchers.Main) {
                // Update Ratings UI
                if (rating != null && rating.ratingCount > 0) {
                    binding.ratingsCard.visibility = View.VISIBLE
                    binding.textViewRatingCount.text = "Based on ${rating.ratingCount} ratings"
                    binding.ratingBarPunctuality.rating = rating.avgPunctuality
                    binding.ratingBarDrive.rating = rating.avgDrive
                    binding.ratingBarBehaviour.rating = rating.avgBehaviour
                } else {
                    binding.ratingsCard.visibility = View.GONE
                }

                // Update Comments UI
                if (comments.isEmpty()) {
                    binding.commentsRecyclerView.visibility = View.GONE
                    binding.textViewNoComments.visibility = View.VISIBLE
                } else {
                    binding.commentsRecyclerView.visibility = View.VISIBLE
                    binding.textViewNoComments.visibility = View.GONE
                    commentAdapter.updateComments(comments)
                }
            }
        }
    }

    /**
     * Loads the local user's data (email/phone) in the background.
     */
    private fun loadLocalUserData() {
        lifecycleScope.launch(Dispatchers.IO) {
            currentUser = UserDataManager.getUser(applicationContext)
        }
    }


    // --- NEW METHOD ---
    /**
     * Shows a dialog for the user to add a new rating.
     */
    private fun showAddRatingDialog() {
        // Inflate the custom dialog layout
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_rating, null)
        val ratingPunctuality = dialogView.findViewById<RatingBar>(R.id.ratingBarPunctualitySubmit)
        val ratingDrive = dialogView.findViewById<RatingBar>(R.id.ratingBarDriveSubmit)
        val ratingBehaviour = dialogView.findViewById<RatingBar>(R.id.ratingBarBehaviourSubmit)

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Submit") { dialog, _ ->
                val punctuality = ratingPunctuality.rating
                val drive = ratingDrive.rating
                val behaviour = ratingBehaviour.rating

                if (punctuality == 0f || drive == 0f || behaviour == 0f) {
                    Toast.makeText(this, "Please provide all three ratings.", Toast.LENGTH_SHORT).show()
                } else {
                    generateRatingJson(punctuality, drive, behaviour)
                    dialog.dismiss()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Shows a dialog for the user to add a new comment.
     */
    private fun showAddCommentDialog() {
        // Inflate the custom dialog layout
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_comment, null)
        val editTextComment = dialogView.findViewById<EditText>(R.id.editTextComment)
        val checkboxAnonymous = dialogView.findViewById<MaterialCheckBox>(R.id.checkboxAnonymous)

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Submit") { dialog, _ ->
                val commentText = editTextComment.text.toString().trim()
                if (commentText.isEmpty()) {
                    Toast.makeText(this, "Please enter a comment.", Toast.LENGTH_SHORT).show()
                } else {
                    val showUsername = !checkboxAnonymous.isChecked
                    generateCommentJson(commentText, showUsername)
                    dialog.dismiss()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- NEW METHOD ---
    /**
     * Shows the warning dialog before submitting a running status change.
     */
    private fun showRunningStatusWarningDialog(newStatusIsRunning: Boolean) {
        val originalStatusWasRunning = busService?.isRunning ?: return

        AlertDialog.Builder(this)
            .setTitle("Confirm Report")
            .setMessage("Please do not report a bus as 'Not Running' based on a single observation, holiday, or Sunday. This should only be reported if you have observed it is continuously not running on multiple working days.\n\nAre you sure you want to proceed?")
            .setPositiveButton("Proceed") { dialog, _ ->
                // User confirmed, generate the JSON
                generateRunningStatusJson(newStatusIsRunning)
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                // User cancelled. Revert the toggle group to its original state.
                if (originalStatusWasRunning) {
                    binding.toggleGroupStatus.check(R.id.buttonStatusYes)
                } else {
                    binding.toggleGroupStatus.check(R.id.buttonStatusNo)
                }
                dialog.dismiss()
            }
            .show()
    }
    // --- END OF NEW FUNCTION ---

    // --- NEW METHOD ---
    /**
     * Generates the JSON payload for a new rating and launches ConfirmationActivity.
     */
    private fun generateRatingJson(punctuality: Float, drive: Float, behaviour: Float) {
        val serviceId = busService?.serviceId ?: return

        // --- ADDED: Timestamp logic ---
        val timestamp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Instant.now().toString()
        } else {
            android.text.format.DateFormat.format("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Date()).toString()
        }

        val ratingJson = JSONObject().apply {
            put("type", "rating")
            put("serviceId", serviceId)
            put("ratingDate", timestamp) // --- ADDED: This line ---
            put("punctuality_5", punctuality) // Scaled to 5
            put("drive_5", drive)         // Scaled to 5
            put("behaviour_5", behaviour)   // Scaled to 5
        }

        val jsonPayload = ratingJson.toString(2)
        Log.d(TAG, "Generated Rating JSON: $jsonPayload")

        // Launch the same ConfirmationActivity
        val intent = ConfirmationActivity.newIntent(this, jsonPayload)
        startActivity(intent)
    }

    // --- NEW FUNCTION ---
    /**
     * Generates the JSON payload for a running status change and launches ConfirmationActivity.
     */
    private fun generateRunningStatusJson(newStatusIsRunning: Boolean) {
        val serviceId = busService?.serviceId ?: return

        val timestamp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Instant.now().toString()
        } else {
            android.text.format.DateFormat.format("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Date()).toString()
        }

        val statusJson = JSONObject().apply {
            put("type", "running_status_suggestion")
            put("serviceId", serviceId)
            put("suggestionDate", timestamp)
            put("suggestedStatus", if (newStatusIsRunning) "Running" else "Not Running")
            put("suggestedStatusBoolean", newStatusIsRunning)
        }

        val jsonPayload = statusJson.toString(2)
        Log.d(TAG, "Generated Status JSON: $jsonPayload")

        // Launch the same ConfirmationActivity
        val intent = ConfirmationActivity.newIntent(this, jsonPayload)
        startActivity(intent)
    }
    // --- END OF NEW FUNCTION ---

    /**
     * Generates the JSON payload for a new comment and launches ConfirmationActivity.
     */
    private fun generateCommentJson(commentText: String, showUsername: Boolean) {
        val serviceId = busService?.serviceId ?: return

        // Get current timestamp in ISO 8601 format (e.g., "2025-11-06T08:40:00Z")
        val timestamp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Instant.now().toString()
        } else {
            // Fallback for older APIs
            android.text.format.DateFormat.format("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Date()).toString()
        }

        val commentJson = JSONObject().apply {
            put("type", "comment") // To distinguish from "edit" or "rating"
            put("serviceId", serviceId)
            put("commentText", commentText)
            put("showUsername", showUsername)
            put("commentDate", timestamp)
        }

        val jsonPayload = commentJson.toString(2) // Indent by 2 spaces
        Log.d(TAG, "Generated Comment JSON: $jsonPayload")

        // Launch the same ConfirmationActivity
        val intent = ConfirmationActivity.newIntent(this, jsonPayload)
        startActivity(intent)
    }


    private fun showLoading(isLoading: Boolean) {
        // This now only controls the *main* spinner.
        // The content layout will be visible underneath.
        binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        if (isLoading) {
            // Hide error text if we are loading
            binding.textViewError.visibility = View.GONE
        }
    }

    private fun showError(message: String) {
        // This now only controls the *main* error text.
        binding.progressBar.visibility = View.GONE
        binding.textViewError.visibility = View.VISIBLE
        binding.textViewError.text = message
    }
}