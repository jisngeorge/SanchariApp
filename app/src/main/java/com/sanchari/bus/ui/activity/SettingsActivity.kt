package com.sanchari.bus.ui.activity

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import com.sanchari.bus.BuildConfig
import com.sanchari.bus.R
import com.sanchari.bus.data.local.LocalVersionManager
import com.sanchari.bus.databinding.ActivitySettingsBinding
import com.sanchari.bus.ui.helper.ApkDownloadManager
import com.sanchari.bus.ui.helper.AppUpdateManager

/** Update preferences, app-update checks and version information. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var apkDownloadManager: ApkDownloadManager
    private lateinit var appUpdateManager: AppUpdateManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        apkDownloadManager = ApkDownloadManager(this, savedInstanceState)
        appUpdateManager = AppUpdateManager(this, apkDownloadManager::download)

        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.autoDatabaseUpdates.isChecked = LocalVersionManager.autoDatabaseUpdatesEnabled(this)
        binding.autoDatabaseUpdates.setOnCheckedChangeListener { _, enabled ->
            LocalVersionManager.setAutoDatabaseUpdatesEnabled(this, enabled)
        }
        binding.checkAppUpdate.setOnClickListener {
            appUpdateManager.showAppUpdateOptions(
                LocalVersionManager.getTimetableDbVersion(this),
                LocalVersionManager.getCommunityDbVersion(this),
                onUpdateAvailable = {},
                onUpdateComplete = ::showVersions
            )
        }
        binding.versionCode.setOnLongClickListener {
            startActivity(Intent(this, DebugActivity::class.java))
            true
        }
        ViewCompat.replaceAccessibilityAction(binding.versionCode, AccessibilityActionCompat.ACTION_LONG_CLICK,
            getString(R.string.settings_version_code_hint), null)
    }

    override fun onResume() {
        super.onResume()
        showVersions()
    }

    private fun showVersions() {
        binding.appVersion.showValue(R.string.settings_app_version, BuildConfig.VERSION_NAME)
        binding.versionCode.showValue(R.string.settings_version_code, BuildConfig.VERSION_CODE.toString())
        binding.timetableVersion.showValue(R.string.settings_timetable_db,
            "v${LocalVersionManager.getTimetableDbVersion(this)}")
        binding.communityVersion.showValue(R.string.settings_community_db,
            "v${LocalVersionManager.getCommunityDbVersion(this)}")
    }

    private fun TextView.showValue(@StringRes label: Int, value: String) {
        text = getString(label) + "\n" + value
    }

    override fun onPause() {
        apkDownloadManager.onPause()
        appUpdateManager.onPause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        apkDownloadManager.saveState(outState)
        super.onSaveInstanceState(outState)
    }
}
