package com.sanchari.bus.ui.activity

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteException
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.sanchari.bus.R
import com.sanchari.bus.data.manager.SearchManager
import com.sanchari.bus.data.remote.NetworkManager
import com.sanchari.bus.data.local.SuggestionStorageManager
import com.sanchari.bus.data.model.User
import com.sanchari.bus.data.manager.UserDataManager
import com.sanchari.bus.databinding.ActivityConfirmBinding
import com.sanchari.bus.ui.helper.DraftManager
import com.sanchari.bus.ui.helper.ContributionPreview
import com.sanchari.bus.ui.helper.ContributionPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONException
import java.io.IOException

class ConfirmationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConfirmBinding
    private var suggestionJson: String? = null
    private var currentUser: User? = null
    private var draftFileName: String? = null
    private var previewReady = false
    private var submissionInProgress = false
    private var commentContribution = false
    private var commentRequestsName = false
    internal var uploadContribution: suspend (String) -> Boolean = {
        NetworkManager.uploadDataToGoogleSheet(applicationContext, it)
    }
    internal var persistContribution: (String) -> Boolean = {
        SuggestionStorageManager.saveSuggestion(applicationContext, it)
    }

    companion object {
        private const val TAG = "ConfirmationActivity"
        private const val EXTRA_JSON_PAYLOAD = "EXTRA_JSON_PAYLOAD"
        const val EXTRA_DRAFT_FILE_NAME = "EXTRA_DRAFT_FILE_NAME"

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

        // Get the JSON payload and potential draft file name
        suggestionJson = intent.getStringExtra(EXTRA_JSON_PAYLOAD)
        draftFileName = intent.getStringExtra(EXTRA_DRAFT_FILE_NAME)

        if (suggestionJson == null) {
            Log.e(TAG, "No JSON payload provided. Finishing activity.")
            Toast.makeText(this, "An error occurred.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.buttonApplySuggestion.isEnabled = false
        binding.editTextName.doAfterTextChanged { updateContactUi() }
        updateContactUi()
        loadPreview()
        loadUserData()

        binding.buttonApplySuggestion.setOnClickListener {
            handleFinalSubmission()
        }
    }

    private fun loadPreview() {
        lifecycleScope.launch {
            try {
                val lines = withContext(Dispatchers.IO) {
                    val suggestion = JSONObject(requireNotNull(suggestionJson))
                    val type = suggestion.getString("type")
                    val id = suggestion.optJSONObject("service")?.optString("serviceId")
                        ?: suggestion.optString("serviceId")
                    val original = if (id.isNotBlank() && type != "new_schedule")
                        SearchManager.getBusServiceById(applicationContext, id) else null
                    val stops = if (original != null && type == "edit_schedule")
                        SearchManager.getEditorBusStops(applicationContext, id) else emptyList()
                    ContributionPreview.build(applicationContext, suggestion, original, stops, includeUsername = false)
                }
                val text = SpannableStringBuilder()
                lines.forEachIndexed { index, line ->
                    if (index > 0) text.append('\n')
                    val start = text.length
                    text.append(when (line.change) {
                        ContributionPreview.Change.ADDED -> "+ "
                        ContributionPreview.Change.REMOVED -> "- "
                        ContributionPreview.Change.UNCHANGED -> ""
                    })
                    val contentStart = text.length
                    text.append(line.text)
                    val color = when (line.change) {
                        ContributionPreview.Change.ADDED -> R.color.preview_added
                        ContributionPreview.Change.REMOVED -> R.color.preview_removed
                        ContributionPreview.Change.UNCHANGED -> null
                    }
                    if (color != null) text.setSpan(ForegroundColorSpan(ContextCompat.getColor(
                        this@ConfirmationActivity, color)), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    val fieldColor = when (line.field) {
                        ContributionPreview.Field.BUS_NAME -> R.color.brand_primary
                        ContributionPreview.Field.BUS_TYPE -> R.color.preview_bus_type
                        ContributionPreview.Field.DETAIL -> null
                    }
                    if (fieldColor != null) {
                        // Keep the +/- marker in its change colour.
                        text.setSpan(ForegroundColorSpan(ContextCompat.getColor(
                            this@ConfirmationActivity, fieldColor)), contentStart, text.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                binding.textContributionPreview.text = text
                binding.textContributionPreview.contentDescription = lines.joinToString("\n") {
                    when (it.change) {
                        ContributionPreview.Change.ADDED -> getString(R.string.preview_added, it.text)
                        ContributionPreview.Change.REMOVED -> getString(R.string.preview_removed, it.text)
                        ContributionPreview.Change.UNCHANGED -> it.text
                    }
                }
                val suggestion = JSONObject(requireNotNull(suggestionJson))
                commentContribution = suggestion.getString("type") == "comment"
                commentRequestsName = suggestion.optBoolean("showUsername")
                updateContactUi()
                previewReady = true
                binding.buttonApplySuggestion.isEnabled = currentUser != null && !submissionInProgress
            } catch (e: JSONException) {
                showPreviewError(e)
            } catch (e: SQLiteException) {
                showPreviewError(e)
            } catch (e: IllegalArgumentException) {
                showPreviewError(e)
            }
        }
    }

    private fun showPreviewError(error: Exception) {
        Log.e(TAG, "Failed to build contribution preview", error)
        binding.textContributionPreview.setText(R.string.preview_failed)
        binding.buttonApplySuggestion.isEnabled = false
    }

    private fun loadUserData() {
        lifecycleScope.launch {
            try {
                val user = withContext(Dispatchers.IO) { UserDataManager.getUser(applicationContext) }
                currentUser = user
                updateContactUi()
                binding.buttonApplySuggestion.isEnabled = previewReady && !submissionInProgress
            } catch (e: SQLiteException) {
                Log.e(TAG, "Contributor identity unavailable", e)
                AlertDialog.Builder(this@ConfirmationActivity)
                    .setMessage(R.string.contributor_load_failed)
                    .setPositiveButton(R.string.editor_retry) { _, _ -> loadUserData() }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
        }
    }

    private fun updateContactUi() {
        listOf(binding.editTextName, binding.editTextEmail, binding.editTextPhone, binding.editTextPlace)
            .forEach { it.isEnabled = currentUser != null && !submissionInProgress }
        binding.textCommentPrivacy.isVisible = commentContribution
        binding.textContributorHelp.setText(if (commentContribution && commentRequestsName)
            R.string.contributor_named_comment_help else R.string.contributor_privacy)
        if (!binding.editTextName.text.isNullOrBlank()) binding.inputName.error = null
        val nameShown = !binding.editTextName.text.isNullOrBlank() &&
            commentRequestsName
        binding.textCommentPrivacy.setText(when {
            nameShown -> R.string.contributor_named
            commentRequestsName -> R.string.contributor_comment_name_required
            else -> R.string.contributor_anonymous
        })
    }

    private fun handleFinalSubmission() {
        if (!previewReady || submissionInProgress) return
        val user = currentUser ?: return
        val json = suggestionJson ?: return

        // 1. Get updated user info
        val newName = binding.editTextName.text.toString().trim()
        val newEmail = binding.editTextEmail.text.toString().trim()
        val newPhone = binding.editTextPhone.text.toString().trim()
        val newPlace = binding.editTextPlace.text.toString().trim()

        if (commentContribution && commentRequestsName && newName.isEmpty()) {
            binding.inputName.error = getString(R.string.contributor_comment_name_required)
            binding.editTextName.requestFocus()
            Toast.makeText(this, R.string.contributor_comment_name_required, Toast.LENGTH_LONG).show()
            return
        }

        if (newEmail.isNotEmpty() && !newEmail.contains("@")) {
            Toast.makeText(this, "Please enter a valid email address", Toast.LENGTH_SHORT).show()
            return
        }

        // Phone validation: must have minimum 10 digits
        if (newPhone.isNotEmpty() && !newPhone.matches(Regex("\\d{10,}"))) {
            Toast.makeText(this, "Phone number must have at least 10 digits", Toast.LENGTH_SHORT).show()
            return
        }

        val contributor = user.copy(name = newName, email = newEmail, phone = newPhone, place = newPlace)
        val updatedUser = user.copy(
            name = newName.ifBlank { user.name }, email = newEmail.ifBlank { user.email },
            phone = newPhone.ifBlank { user.phone }, place = newPlace.ifBlank { user.place }
        )
        val finalPayloadJson = ContributionPayload.wrap(json, contributor)
        submissionInProgress = true
        binding.buttonApplySuggestion.isEnabled = false
        updateContactUi()
        lifecycleScope.launch {
            if (user != updatedUser) {
                val saved = withContext(Dispatchers.IO) { UserDataManager.saveUser(applicationContext, updatedUser) }
                if (!saved) {
                    submissionInProgress = false
                    binding.buttonApplySuggestion.isEnabled = previewReady
                    updateContactUi()
                    Toast.makeText(this@ConfirmationActivity, R.string.contributor_save_failed, Toast.LENGTH_LONG).show()
                    return@launch
                }
                currentUser = updatedUser
            }
            uploadOrSave(finalPayloadJson)
        }
    }

    private fun uploadOrSave(jsonPayload: String) {
        // Show progress bar
        binding.progressBar.visibility = View.VISIBLE
        binding.buttonApplySuggestion.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            // Try to upload using the NetworkManager
            val success = uploadContribution(jsonPayload)
            val savedLocally = !success && persistContribution(jsonPayload)

            withContext(Dispatchers.Main) {
                binding.progressBar.visibility = View.GONE

                if (success) {
                    logSubmission(jsonPayload)
                    deleteDraftIfExists()
                    showSuccessDialog("Your suggestion has been submitted successfully.")
                } else {
                    // If upload fails, save locally
                    if (savedLocally) {
                        deleteDraftIfExists()
                        Toast.makeText(this@ConfirmationActivity, R.string.pending_upload_saved, Toast.LENGTH_LONG).show()
                        showSuccessDialog(getString(R.string.pending_upload_saved))
                    } else {
                        submissionInProgress = false
                        binding.buttonApplySuggestion.isEnabled = previewReady
                        updateContactUi()
                        showErrorDialog("Failed to submit or save suggestion.")
                    }
                }
            }
        }
    }

    private suspend fun deleteDraftIfExists() {
        draftFileName?.let { fileName ->
            try {
                DraftManager.deleteDraftAsync(applicationContext, fileName).await()
            } catch (e: IOException) {
                Toast.makeText(this, com.sanchari.bus.R.string.editor_draft_cleanup_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun logSubmission(jsonPayload: String) {
        try {
            val root = JSONObject(jsonPayload)
            val suggestion = root.getJSONObject("suggestion")
            if (suggestion.optString("type") !in listOf("new_schedule", "edit_schedule")) return
            val service = suggestion.getJSONObject("service")
            val busName = service.optString("name", "Unknown")
            val busType = service.optString("type", "")
            val stopsArray = suggestion.optJSONArray("stops")
            val startingPlace = if (stopsArray != null && stopsArray.length() > 0) {
                stopsArray.getJSONObject(0).optString("locationName", "")
            } else ""

            lifecycleScope.launch(Dispatchers.IO) {
                UserDataManager.addSubmissionLog(applicationContext, busName, busType, startingPlace)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error logging submission", e)
        }
    }

    private fun showSuccessDialog(message: String) {
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