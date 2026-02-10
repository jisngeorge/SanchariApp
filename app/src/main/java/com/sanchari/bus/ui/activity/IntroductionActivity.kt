package com.sanchari.bus.ui.activity

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.sanchari.bus.databinding.ActivityIntroductionBinding

/**
 * Activity to display the app introduction and feature guide on first run.
 * It does NOT collect user information.
 */
class IntroductionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityIntroductionBinding

    companion object {
        // We keep this constant if needed, though logic is simplified now
        const val EXTRA_IS_INTRO = "EXTRA_IS_INTRO"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityIntroductionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.title = "Welcome"

        // Ensure the feature guide is visible (in case XML defaults to 'gone')
        binding.featureGuideLayout.visibility = View.VISIBLE

        // The layout constraints (layout_constraintTop_toBottomOf) ensure
        // the button will never overlap the text content, even on small screens.
        // If content is tall, the ScrollView allows reaching the button.

        binding.buttonGetStarted.setOnClickListener {
            // User has read the intro, close this activity to return to MainActivity
            finish()
        }
    }
}