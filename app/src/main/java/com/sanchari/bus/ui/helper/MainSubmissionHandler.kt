package com.sanchari.bus.ui.helper

import android.util.Log
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.sanchari.bus.R
import com.sanchari.bus.ui.activity.ConfirmationActivity
import org.json.JSONObject

class MainSubmissionHandler(private val activity: AppCompatActivity) {

    companion object {
        private const val TAG = "MainSubmissionHandler"
    }

    fun showMessageAdminDialog() {
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_message_admin, null)
        val editTextMessage = dialogView.findViewById<EditText>(R.id.editTextMessage)

        AlertDialog.Builder(activity)
            .setView(dialogView)
            .setPositiveButton("Send") { dialog, _ ->
                val messageText = editTextMessage.text.toString().trim()
                if (messageText.isEmpty()) {
                    Toast.makeText(activity, "Please enter a message.", Toast.LENGTH_SHORT).show()
                } else {
                    generateMessageJson(messageText)
                    dialog.dismiss()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun generateMessageJson(messageText: String) {
        // Unix Timestamp (Seconds)
        val timestamp = System.currentTimeMillis() / 1000

        val jsonObject = JSONObject().apply {
            put("type", "admin_message")
            put("messageText", messageText)
            put("messageDate", timestamp)
        }

        val jsonPayload = jsonObject.toString(2)
        Log.d(TAG, "Generated Admin Message JSON: $jsonPayload")

        val intent = ConfirmationActivity.newIntent(activity, jsonPayload)
        activity.startActivity(intent)
    }
}