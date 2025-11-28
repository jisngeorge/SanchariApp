package com.sanchari.bus

import android.content.Context
import android.content.Intent
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BusDetailsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBusDetailsBinding
    private var busService: BusService? = null
    private lateinit var busStopAdapter: BusStopAdapter
    private lateinit var commentAdapter: CommentAdapter

    // Refactored Manager
    private lateinit var submissionHandler: BusSubmissionHandler

    // Store the user's details for pre-filling the form
    private var currentUser: User? = null

    companion object {
        private const val EXTRA_BUS_SERVICE = "EXTRA_BUS_SERVICE"
        private const val TAG = "BusDetailsActivity"

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

        submissionHandler = BusSubmissionHandler(this)

        // Set up the toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Bus Details"

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

        setupRunningStatusUI()

        // Load all data
        loadBusStops()
        loadCommunityData()
        loadLocalUserData()

        // Setup button listeners
        binding.buttonAddRating.setOnClickListener {
            busService?.let { submissionHandler.showAddRatingDialog(it) }
        }
        binding.buttonAddComment.setOnClickListener {
            busService?.let { submissionHandler.showAddCommentDialog(it) }
        }

        binding.buttonSuggestEdit.setOnClickListener {
            busService?.let {
                val intent = SuggestEditActivity.newIntentForEdit(this, it)
                startActivity(intent)
            }
        }
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
            isNestedScrollingEnabled = false
        }

        // Comments Adapter
        commentAdapter = CommentAdapter(emptyList())
        binding.commentsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@BusDetailsActivity)
            adapter = commentAdapter
            isNestedScrollingEnabled = false
        }
    }

    private fun setupRunningStatusUI() {
        val service = busService ?: return

        if (service.isRunning) {
            binding.toggleGroupStatus.check(R.id.buttonStatusYes)
        } else {
            binding.toggleGroupStatus.check(R.id.buttonStatusNo)
        }

        if (service.lastReportedTime > 0) {
            try {
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

        binding.toggleGroupStatus.addOnButtonCheckedListener { group, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener

            val isNowRunning = checkedId == R.id.buttonStatusYes
            val wasRunning = service.isRunning

            if (isNowRunning != wasRunning) {
                // Delegate to handler
                submissionHandler.showRunningStatusWarningDialog(service, isNowRunning) {
                    // Revert action (lambda)
                    if (wasRunning) {
                        binding.toggleGroupStatus.check(R.id.buttonStatusYes)
                    } else {
                        binding.toggleGroupStatus.check(R.id.buttonStatusNo)
                    }
                }
            }
        }
    }

    private fun loadBusStops() {
        val serviceId = busService?.serviceId ?: return
        showLoading(true)

        lifecycleScope.launch(Dispatchers.IO) {
            val stops = SearchManager.getBusStops(applicationContext, serviceId)

            withContext(Dispatchers.Main) {
                showLoading(false)
                if (stops.isEmpty()) {
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

    private fun loadCommunityData() {
        val serviceId = busService?.serviceId ?: return

        lifecycleScope.launch(Dispatchers.IO) {
            val ratingDeferred = async { CommunityDataManager.getBusRating(applicationContext, serviceId) }
            val commentsDeferred = async { CommunityDataManager.getComments(applicationContext, serviceId) }

            val rating = ratingDeferred.await()
            val comments = commentsDeferred.await()

            withContext(Dispatchers.Main) {
                if (rating != null && rating.ratingCount > 0) {
                    binding.ratingsCard.visibility = View.VISIBLE
                    binding.textViewRatingCount.text = "Based on ${rating.ratingCount} ratings"
                    binding.ratingBarPunctuality.rating = rating.avgPunctuality
                    binding.ratingBarDrive.rating = rating.avgDrive
                    binding.ratingBarBehaviour.rating = rating.avgBehaviour
                } else {
                    binding.ratingsCard.visibility = View.GONE
                }

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

    private fun loadLocalUserData() {
        lifecycleScope.launch(Dispatchers.IO) {
            currentUser = UserDataManager.getUser(applicationContext)
        }
    }

    private fun showLoading(isLoading: Boolean) {
        binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        if (isLoading) {
            binding.textViewError.visibility = View.GONE
        }
    }
}