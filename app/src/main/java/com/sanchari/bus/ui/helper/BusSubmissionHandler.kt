package com.sanchari.bus.ui.helper

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.RatingBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.BundleCompat
import com.google.android.material.checkbox.MaterialCheckBox
import com.sanchari.bus.R
import com.sanchari.bus.data.model.BusService
import com.sanchari.bus.ui.activity.ConfirmationActivity
import org.json.JSONObject

/**
 * Handles the UI and Logic for submitting user contributions:
 * - Add Rating
 * - Add Comment
 * - Suggest Running Status
 */
class BusSubmissionHandler(
    private val activity: AppCompatActivity
) {

    companion object {
        private const val STATE_CONTRIBUTION = "pending_bus_contribution"
    }

    private var pendingService: BusService? = null
    private var pendingSuggestion: String? = null
    private val confirmationLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val service = pendingService
        val suggestion = pendingSuggestion?.let(::JSONObject)
        pendingService = null
        pendingSuggestion = null
        if (result.resultCode == Activity.RESULT_CANCELED && !activity.isFinishing &&
            !activity.isDestroyed && service != null && suggestion != null) {
            when (suggestion.getString("type")) {
                "rating" -> showRatingDialog(service, suggestion)
                "comment" -> showCommentDialog(service, suggestion)
            }
        }
    }

    init {
        activity.savedStateRegistry.consumeRestoredStateForKey(STATE_CONTRIBUTION)?.let {
            pendingService = BundleCompat.getParcelable(it, "service", BusService::class.java)
            pendingSuggestion = it.getString("suggestion")
        }
        activity.savedStateRegistry.registerSavedStateProvider(STATE_CONTRIBUTION) {
            Bundle().apply {
                putParcelable("service", pendingService)
                putString("suggestion", pendingSuggestion)
            }
        }
    }

    fun showAddRatingDialog(service: BusService) = showRatingDialog(service, null)

    private fun showRatingDialog(service: BusService, initial: JSONObject?) {
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_add_rating, null)
        val ratingPunctuality = dialogView.findViewById<RatingBar>(R.id.ratingBarPunctualitySubmit)
        val ratingDrive = dialogView.findViewById<RatingBar>(R.id.ratingBarDriveSubmit)
        val ratingBehaviour = dialogView.findViewById<RatingBar>(R.id.ratingBarBehaviourSubmit)
        if (initial != null) {
            ratingPunctuality.rating = initial.getDouble("punctuality_5").toFloat()
            ratingDrive.rating = initial.getDouble("drive_5").toFloat()
            ratingBehaviour.rating = initial.getDouble("behaviour_5").toFloat()
        }

        AlertDialog.Builder(activity)
            .setView(dialogView)
            .setPositiveButton("Submit") { dialog, _ ->
                val punctuality = ratingPunctuality.rating
                val drive = ratingDrive.rating
                val behaviour = ratingBehaviour.rating

                if (punctuality == 0f || drive == 0f || behaviour == 0f) {
                    Toast.makeText(activity, "Please provide all three ratings.", Toast.LENGTH_SHORT).show()
                } else {
                    generateRatingJson(service, punctuality, drive, behaviour)
                    dialog.dismiss()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun showAddCommentDialog(service: BusService) = showCommentDialog(service, null)

    private fun showCommentDialog(service: BusService, initial: JSONObject?) {
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_add_comment, null)
        val editTextComment = dialogView.findViewById<EditText>(R.id.editTextComment)
        val checkboxAnonymous = dialogView.findViewById<MaterialCheckBox>(R.id.checkboxAnonymous)
        if (initial != null) {
            editTextComment.setText(initial.getString("commentText"))
            checkboxAnonymous.isChecked = !initial.getBoolean("showUsername")
        }

        AlertDialog.Builder(activity)
            .setView(dialogView)
            .setPositiveButton("Submit") { dialog, _ ->
                val commentText = editTextComment.text.toString().trim()
                if (commentText.isEmpty()) {
                    Toast.makeText(activity, "Please enter a comment.", Toast.LENGTH_SHORT).show()
                } else {
                    val showUsername = !checkboxAnonymous.isChecked
                    generateCommentJson(service, commentText, showUsername)
                    dialog.dismiss()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun showRunningStatusWarningDialog(service: BusService, newStatusIsRunning: Boolean, revertToggleAction: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle("Confirm Report")
            .setMessage("Please do not report a bus as 'Not Running' based on a single observation, holiday, or Sunday. This should only be reported if you have observed it is continuously not running on multiple working days.\n\nAre you sure you want to proceed?")
            .setPositiveButton("Proceed") { dialog, _ ->
                generateRunningStatusJson(service, newStatusIsRunning)
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                revertToggleAction()
                dialog.dismiss()
            }
            .show()
    }

    // --- UPDATED JSON GENERATION METHODS ---

    private fun generateRatingJson(service: BusService, punctuality: Float, drive: Float, behaviour: Float) {
        val timestamp = System.currentTimeMillis() / 1000
        val ratingJson = JSONObject().apply {
            put("type", "rating")
            put("serviceId", service.serviceId)
            put("timestamp", timestamp) // Standardized key
            put("punctuality_5", punctuality)
            put("drive_5", drive)
            put("behaviour_5", behaviour)
        }
        launchConfirmation(service, ratingJson.toString(2))
    }

    private fun generateCommentJson(service: BusService, commentText: String, showUsername: Boolean) {
        val timestamp = System.currentTimeMillis() / 1000
        val commentJson = JSONObject().apply {
            put("type", "comment")
            put("serviceId", service.serviceId)
            put("commentText", commentText)
            put("showUsername", showUsername)
            put("timestamp", timestamp) // Standardized key
        }
        launchConfirmation(service, commentJson.toString(2))
    }

    private fun generateRunningStatusJson(service: BusService, newStatusIsRunning: Boolean) {
        val timestamp = System.currentTimeMillis() / 1000
        val statusJson = JSONObject().apply {
            put("type", "running_status_suggestion")
            put("serviceId", service.serviceId)
            put("timestamp", timestamp) // Standardized key
            put("suggestedStatus", if (newStatusIsRunning) "Running" else "Not Running")
            put("suggestedStatusBoolean", newStatusIsRunning)
        }
        launchConfirmation(service, statusJson.toString(2))
    }

    private fun launchConfirmation(service: BusService, jsonPayload: String) {
        pendingService = service
        pendingSuggestion = jsonPayload
        val intent = ConfirmationActivity.newIntent(activity, jsonPayload)
        confirmationLauncher.launch(intent)
    }
}