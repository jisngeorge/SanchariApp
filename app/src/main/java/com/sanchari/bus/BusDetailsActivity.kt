package com.sanchari.bus

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.sanchari.bus.databinding.ActivityBusDetailsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

        // TODO: Replace with your actual Google Form URLs
        private const val GOOGLE_FORM_URL_RATING = "https://docs.google.com/forms/..."
        private const val GOOGLE_FORM_URL_COMMENT = "https://docs.google.com/forms/..."

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
            openGoogleForm("rating")
        }
        binding.buttonAddComment.setOnClickListener {
            openGoogleForm("comment")
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
     * Opens the Google Form in a browser, pre-filled with user and service data.
     */
    private fun openGoogleForm(formType: String) {
        val serviceId = busService?.serviceId ?: return
        val user = currentUser // Get the loaded user data

        // Build the URL with pre-filled parameters
        // Example for Google Forms:
        // .../viewform?entry.12345=ServiceIdValue&entry.67890=EmailValue
        val baseUrl = if (formType == "rating") GOOGLE_FORM_URL_RATING else GOOGLE_FORM_URL_COMMENT

        // TODO: Replace 'entry.XXXXX' with your actual entry IDs from Google Forms
        val preFillServiceId = "entry.100001=${Uri.encode(serviceId)}"
        val preFillEmail = "entry.100002=${Uri.encode(user?.email ?: "")}"
        val preFillPhone = "entry.100003=${Uri.encode(user?.phone ?: "")}"

        val fullUrl = "$baseUrl?$preFillServiceId&$preFillEmail&$preFillPhone"

        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(fullUrl))
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening Google Form", e)
            Toast.makeText(this, "Error opening form. Please try again.", Toast.LENGTH_SHORT).show()
        }
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

