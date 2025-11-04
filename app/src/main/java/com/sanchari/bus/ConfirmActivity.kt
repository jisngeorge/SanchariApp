package com.sanchari.bus

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
// Updated import
import com.sanchari.bus.databinding.ActivityConfirmBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ConfirmationActivity : AppCompatActivity() {

    // Updated binding type
    private lateinit var binding: ActivityConfirmBinding
    private var suggestionJson: String? = null
    private var currentUser: User? = null

    companion object {
        private const val TAG = "ConfirmationActivity"
        private const val EXTRA_JSON_PAYLOAD = "EXTRA_JSON_PAYLOAD"

        fun newIntent(context: Context, jsonPayload: String): Intent {
            return Intent(context, ConfirmationActivity::class.java).apply {
                putExtra(EXTRA_JSON_PAYLOAD, jsonPayload)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Updated binding inflation
        binding = ActivityConfirmBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Setup Toolbar
        binding.toolbar.setNavigationOnClickListener { finish() }

        // Get the JSON payload from SuggestEditActivity
        suggestionJson = intent.getStringExtra(EXTRA_JSON_PAYLOAD)
        if (suggestionJson == null) {
            Log.e(TAG, "No JSON payload provided. Finishing activity.")
            Toast.makeText(this, "An error occurred.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        loadUserData()

        binding.buttonApplySuggestion.setOnClickListener {
            handleFinalSubmission()
        }
    }

    private fun loadUserData() {
        lifecycleScope.launch(Dispatchers.IO) {
            val user = UserDataManager.getUser(applicationContext)
            withContext(Dispatchers.Main) {
                currentUser = user
                populateUserData(user)
            }
        }
    }

    private fun populateUserData(user: User) {
        binding.editTextName.setText(user.name)
        binding.editTextEmail.setText(user.email)
        binding.editTextPhone.setText(user.phone)
        binding.editTextPlace.setText(user.place)
    }

    private fun handleFinalSubmission() {
        val user = currentUser ?: return
        val json = suggestionJson ?: return

        // --- 1. Get potentially updated user info ---
        val newName = binding.editTextName.text.toString().trim()
        val newEmail = binding.editTextEmail.text.toString().trim()
        val newPhone = binding.editTextPhone.text.toString().trim()
        val newPlace = binding.editTextPlace.text.toString().trim()

        // --- 2. Validate user info ---
        if (newName.isEmpty() || newEmail.isEmpty() || newPhone.isEmpty()) {
            Toast.makeText(this, "Please fill in your name, email, and phone.", Toast.LENGTH_SHORT).show()
            return
        }

        // --- 3. Check if user info changed and save if needed ---
        if (user.name != newName || user.email != newEmail || user.phone != newPhone || user.place != newPlace) {
            val updatedUser = user.copy(
                name = newName,
                email = newEmail,
                phone = newPhone,
                place = newPlace
            )
            lifecycleScope.launch(Dispatchers.IO) {
                val saveSuccess = UserDataManager.saveUser(applicationContext, updatedUser)
                if (saveSuccess) {
                    Log.i(TAG, "User info updated in local database.")
                } else {
                    Log.e(TAG, "Failed to update user info.")
                }
            }
        }

        // --- 4. Handle the final JSON submission ---
        // As per our design, we are not uploading this yet.
        // We will just log it and show a success message.
        Log.i(TAG, "Final Submission Payload:")
        Log.i(TAG, "User: $newName, $newEmail, $newPhone")
        Log.i(TAG, "JSON: $json")

        // Show success dialog
        AlertDialog.Builder(this)
            .setTitle("Suggestion Sent")
            .setMessage("Thank you! Your suggestion has been submitted for review.")
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
                // Finish this activity and SuggestEditActivity
                val intent = Intent(this, MainActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent)
                finish()
            }
            .setCancelable(false)
            .show()
    }
}

