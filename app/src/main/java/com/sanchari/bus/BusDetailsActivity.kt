package com.sanchari.bus

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater // NEW Import
import android.view.MenuItem
import android.view.View
import android.widget.EditText // NEW Import
import android.widget.Toast
import androidx.appcompat.app.AlertDialog // NEW Import
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.checkbox.MaterialCheckBox // NEW Import
import com.sanchari.bus.databinding.ActivityBusDetailsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject // NEW Import
import java.time.Instant // NEW Import

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

        // REMOVED Google Form URLs

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

        setupRecyclerViews()

        // Load all data
        loadBusStops()
        loadCommunityData()
        loadLocalUserData() // For pre-filling forms

        // Setup button listeners
        binding.buttonAddRating.setOnClickListener {
            // openGoogleForm("rating")
            // TODO: Implement in-app rating dialog
            Toast.makeText(this, "Add Rating feature coming soon.", Toast.LENGTH_SHORT).show()
        }
        binding.buttonAddComment.setOnClickListener {
            // openGoogleForm("comment")
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

    /**
     * REMOVED openGoogleForm(...) method
     */

    // --- NEW METHOD ---
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

        val jsonPayload = commentJson.toString(2) // Indent for readability
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