package com.sanchari.bus

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View // --- FIXED: Import for View.VISIBLE ---
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sanchari.bus.databinding.ActivityConfirmBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
// --- FIXED: Imports for OkHttp and Extensions ---
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
// --- END FIXED ---

class ConfirmationActivity : AppCompatActivity() {

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
        binding = ActivityConfirmBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Setup Toolbar
        binding.toolbar.setNavigationOnClickListener { finish() }

        // Get the JSON payload
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

        // 1. Get updated user info
        val newName = binding.editTextName.text.toString().trim()
        val newEmail = binding.editTextEmail.text.toString().trim()
        val newPhone = binding.editTextPhone.text.toString().trim()
        val newPlace = binding.editTextPlace.text.toString().trim()

        // 2. Validate
        if (newName.isEmpty() || newEmail.isEmpty() || newPhone.isEmpty()) {
            Toast.makeText(this, "Please fill in your name, email, and phone.", Toast.LENGTH_SHORT).show()
            return
        }

        // 3. Save user info if changed
        if (user.name != newName || user.email != newEmail || user.phone != newPhone || user.place != newPlace) {
            val updatedUser = user.copy(
                name = newName,
                email = newEmail,
                phone = newPhone,
                place = newPlace
            )
            lifecycleScope.launch(Dispatchers.IO) {
                UserDataManager.saveUser(applicationContext, updatedUser)
            }
        }

        // 4. Create Final JSON
        val finalPayloadJson = """
            {
              "submittedBy": {
                "name": "$newName",
                "email": "$newEmail",
                "phone": "$newPhone",
                "place": "$newPlace",
                "uuid": "${user.uuid}"
              },
              "suggestion": $json
            }
        """.trimIndent()

        // 5. Save Locally (Backup)
        SuggestionStorageManager.saveSuggestion(applicationContext, finalPayloadJson)

        // 6. Upload to Google Sheet
        uploadToGoogleSheet(finalPayloadJson)
    }

    private fun uploadToGoogleSheet(jsonPayload: String) {
        // --- NEW LOGIC: Dynamic URL with Fallback ---
        val defaultUrl = "https://script.google.com/macros/s/AKfycbyDLyr_WttKbklkys3Jim8K6u07XCytYUEi2RWY58EIKGlKzl1WZuhe5QVMDoajHP7x/exec" // Your hardcoded backup
        val dynamicUrl = LocalVersionManager.getCommunityUrl(applicationContext)

        val url = if (!dynamicUrl.isNullOrBlank()) {
            Log.d(TAG, "Using dynamic Community URL")
            dynamicUrl
        } else {
            Log.d(TAG, "Using default Community URL")
            defaultUrl
        }
        // --- END NEW LOGIC ---

        // Show progress bar
        binding.progressBar.visibility = View.VISIBLE
        binding.buttonApplySuggestion.isEnabled = false // Prevent double clicks

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = jsonPayload.toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val client = OkHttpClient()
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string()
                    Log.i(TAG, "Google Sheet Response: $responseBody")

                    withContext(Dispatchers.Main) {
                        binding.progressBar.visibility = View.GONE
                        binding.buttonApplySuggestion.isEnabled = true

                        if (response.isSuccessful) {
                            showSuccessDialog()
                        } else {
                            showErrorDialog("Server error: ${response.code}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Upload failed", e)
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.buttonApplySuggestion.isEnabled = true

                    // If network fails, we treat it as a success because we saved it locally!
                    showSuccessDialog("Saved offline. Will sync later.")
                }
            }
        }
    }

    private fun showSuccessDialog(message: String = "Your input has been submitted.") {
        AlertDialog.Builder(this)
            .setTitle("Thank You!")
            .setMessage(message)
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
                val intent = Intent(this, MainActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent)
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun showErrorDialog(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}