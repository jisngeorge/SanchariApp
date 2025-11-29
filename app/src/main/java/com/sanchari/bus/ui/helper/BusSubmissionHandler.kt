package com.sanchari.bus.ui.helper

import android.os.Build
import android.text.format.DateFormat
import android.util.Log
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.RatingBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.checkbox.MaterialCheckBox
import com.sanchari.bus.R
import com.sanchari.bus.data.model.BusService
import com.sanchari.bus.ui.activity.ConfirmationActivity
import org.json.JSONObject
import java.time.Instant
import java.util.Date

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
        private const val TAG = "BusSubmissionHandler"
    }

    fun showAddRatingDialog(service: BusService) {
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_add_rating, null)
        val ratingPunctuality = dialogView.findViewById<RatingBar>(R.id.ratingBarPunctualitySubmit)
        val ratingDrive = dialogView.findViewById<RatingBar>(R.id.ratingBarDriveSubmit)
        val ratingBehaviour = dialogView.findViewById<RatingBar>(R.id.ratingBarBehaviourSubmit)

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

    fun showAddCommentDialog(service: BusService) {
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_add_comment, null)
        val editTextComment = dialogView.findViewById<EditText>(R.id.editTextComment)
        val checkboxAnonymous = dialogView.findViewById<MaterialCheckBox>(R.id.checkboxAnonymous)

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

    private fun generateRatingJson(service: BusService, punctuality: Float, drive: Float, behaviour: Float) {
        val timestamp = getIsoTimestamp()

        val ratingJson = JSONObject().apply {
            put("type", "rating")
            put("serviceId", service.serviceId)
            put("ratingDate", timestamp)
            put("punctuality_5", punctuality)
            put("drive_5", drive)
            put("behaviour_5", behaviour)
        }

        launchConfirmation(ratingJson.toString(2))
    }

    private fun generateCommentJson(service: BusService, commentText: String, showUsername: Boolean) {
        val timestamp = getIsoTimestamp()

        val commentJson = JSONObject().apply {
            put("type", "comment")
            put("serviceId", service.serviceId)
            put("commentText", commentText)
            put("showUsername", showUsername)
            put("commentDate", timestamp)
        }

        launchConfirmation(commentJson.toString(2))
    }

    private fun generateRunningStatusJson(service: BusService, newStatusIsRunning: Boolean) {
        val timestamp = getIsoTimestamp()

        val statusJson = JSONObject().apply {
            put("type", "running_status_suggestion")
            put("serviceId", service.serviceId)
            put("suggestionDate", timestamp)
            put("suggestedStatus", if (newStatusIsRunning) "Running" else "Not Running")
            put("suggestedStatusBoolean", newStatusIsRunning)
        }

        launchConfirmation(statusJson.toString(2))
    }

    private fun launchConfirmation(jsonPayload: String) {
        Log.d(TAG, "Generated JSON: $jsonPayload")
        val intent = ConfirmationActivity.newIntent(activity, jsonPayload)
        activity.startActivity(intent)
    }

    private fun getIsoTimestamp(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Instant.now().toString()
        } else {
            DateFormat.format("yyyy-MM-dd'T'HH:mm:ss'Z'", Date()).toString()
        }
    }
}