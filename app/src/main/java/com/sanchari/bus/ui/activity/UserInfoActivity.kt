package com.sanchari.bus.ui.activity

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sanchari.bus.data.model.User
import com.sanchari.bus.data.manager.UserDataManager
import com.sanchari.bus.databinding.ActivityUserInfoBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Activity to collect the user's information (name, email, etc.)
 */
class UserInfoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUserInfoBinding
    private var currentUser: User? = null

    companion object {
        private const val TAG = "UserInfoActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUserInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.title = "Your Information"

        loadData()

        binding.saveButton.setOnClickListener {
            saveData()
        }
    }

    /**
     * Loads the existing user data from the database and populates the fields.
     */
    private fun loadData() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val user = UserDataManager.getUser(applicationContext)
                currentUser = user

                // Switch back to Main thread to update UI
                withContext(Dispatchers.Main) {
                    binding.nameEditText.setText(user.name)
                    binding.emailEditText.setText(user.email)
                    binding.phoneEditText.setText(user.phone)
                    binding.placeEditText.setText(user.place)
                    Log.i(TAG, "User data loaded into form")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading user data", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@UserInfoActivity, "Error loading your data", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Validates and saves the user's data from the form to the database.
     */
    private fun saveData() {
        val name = binding.nameEditText.text.toString().trim()
        val email = binding.emailEditText.text.toString().trim()
        val phone = binding.phoneEditText.text.toString().trim()
        val place = binding.placeEditText.text.toString().trim()

        // Simple validation
        if (name.isEmpty() || email.isEmpty() || phone.isEmpty()) {
            Toast.makeText(this, "Please fill in all required fields (Name, Email, Phone)", Toast.LENGTH_SHORT).show()
            return
        }

        // Email validation: must contain "@"
        if (!email.contains("@")) {
            Toast.makeText(this, "Please enter a valid email address", Toast.LENGTH_SHORT).show()
            return
        }

        // Phone validation: must have minimum 10 digits
        if (!phone.matches(Regex("\\d{10,}"))) {
            Toast.makeText(this, "Phone number must have at least 10 digits", Toast.LENGTH_SHORT).show()
            return
        }

        // Get the current user's UUID
        val userToSave = currentUser
        if (userToSave == null) {
            Log.e(TAG, "Cannot save, current user is null.")
            Toast.makeText(this, "Error: Could not find user profile", Toast.LENGTH_SHORT).show()
            return
        }

        // Create the updated User object
        val updatedUser = userToSave.copy(
            name = name,
            email = email,
            phone = phone,
            place = place
        )

        // Save in the background
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val success = UserDataManager.saveUser(applicationContext, updatedUser)
                withContext(Dispatchers.Main) {
                    if (success) {
                        Toast.makeText(this@UserInfoActivity, "Information Saved!", Toast.LENGTH_SHORT).show()
                        setResult(RESULT_OK)
                        // User info is saved, we can close this activity
                        finish()
                    } else {
                        Toast.makeText(this@UserInfoActivity, "Error saving data", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error saving user data", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@UserInfoActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // The deprecated onBackPressed() method has been removed to use the default system behavior.
}

