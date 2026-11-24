package com.sanchari.bus.ui.activity

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sanchari.bus.BuildConfig
import com.sanchari.bus.R
import com.sanchari.bus.data.local.DatabaseConstants
import com.sanchari.bus.data.local.LocalVersionManager
import com.sanchari.bus.data.local.SuggestionStorageManager
import com.sanchari.bus.databinding.ActivityDebugBinding
import com.sanchari.bus.ui.helper.DraftManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Read-only diagnostics. Deliberately excludes the user UUID, contacts and payload contents. */
class DebugActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDebugBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDebugBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.copyDebug.setOnClickListener {
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText(getString(R.string.debug_title), binding.debugText.text))
            Toast.makeText(this, R.string.debug_copied, Toast.LENGTH_SHORT).show()
        }
        lifecycleScope.launch {
            binding.debugText.text = withContext(Dispatchers.IO) { buildReport(applicationContext) }
        }
    }

    private fun buildReport(context: Context): String {
        val remote = LocalVersionManager.remoteVersions(context)
        val availability = LocalVersionManager.updateAvailability(context)
        val lastCheck = LocalVersionManager.getLastUpdateCheckTime(context)
        fun dbSize(name: String) = context.getDatabasePath(name).takeIf { it.exists() }
            ?.let { "${it.length() / 1024} KB" } ?: "missing"
        return buildString {
            appendLine("App")
            appendLine("  Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("  Build type: ${BuildConfig.BUILD_TYPE}")
            appendLine("  Package: ${context.packageName}")
            appendLine()
            appendLine("Device")
            appendLine("  Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("  Model: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine()
            appendLine("Databases (installed / last remote)")
            appendLine("  Timetable: v${LocalVersionManager.getTimetableDbVersion(context)} / v${remote.timetable}" +
                ", ${dbSize(DatabaseConstants.TIMETABLE_DATABASE_NAME)}")
            appendLine("  Community: v${LocalVersionManager.getCommunityDbVersion(context)} / v${remote.community}" +
                ", ${dbSize(DatabaseConstants.COMMUNITY_DATABASE_NAME)}")
            appendLine("  Remote app version code: ${remote.app}")
            appendLine()
            appendLine("Updates")
            appendLine("  Automatic DB updates: ${LocalVersionManager.autoDatabaseUpdatesEnabled(context)}")
            appendLine("  Last automatic check: " + if (lastCheck == 0L) "never" else
                DateFormat.getDateTimeInstance().format(Date(lastCheck)))
            appendLine("  Available: app=${availability.app}, timetable=${availability.timetable}, " +
                "community=${availability.community}")
            appendLine("  Manifest URL: ${LocalVersionManager.getVersionsUrl(context) ?: "not configured"}")
            appendLine("  Upload endpoint configured: ${!LocalVersionManager.getCommunityUrl(context).isNullOrBlank()}")
            appendLine()
            appendLine("Local contributions")
            appendLine("  Drafts: ${DraftManager.getDrafts(context).size}")
            append("  Pending uploads: ${SuggestionStorageManager.getPendingSuggestions(context).size}")
        }
    }
}
