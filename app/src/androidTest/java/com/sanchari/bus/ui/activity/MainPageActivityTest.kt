package com.sanchari.bus.ui.activity

import android.content.ContentValues
import android.os.SystemClock
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sanchari.bus.R
import com.sanchari.bus.data.local.DatabaseConstants.RecentViewTable
import com.sanchari.bus.data.local.DatabaseManager
import com.sanchari.bus.data.local.LocalVersionManager
import com.sanchari.bus.data.local.UserDatabaseHelper
import com.sanchari.bus.data.manager.UserDataManager
import com.sanchari.bus.data.model.RecentSearch
import com.sanchari.bus.databinding.ActivityMainBinding
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainPageActivityTest {
    @Test
    fun recentServicesSwapAndOriginalUpdateLabelWorkAcrossResumeAndRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("This test must not retry existing queued contributions",
            context.filesDir.resolve("suggestions").listFiles().orEmpty().none { it.extension == "json" })
        val wasFirstRun = LocalVersionManager.isFirstRun(context)
        val lastCheck = LocalVersionManager.getLastUpdateCheckTime(context)
        val hadUpdate = LocalVersionManager.isUpdateAvailable(context)
        val previousRecents = UserDataManager.getRecentViews(context)
        try {
            runBlocking { DatabaseManager.initializeDatabases(context) }
            replaceRecentViews(emptyList())
            LocalVersionManager.setFirstRunCompleted(context)
            LocalVersionManager.saveLastUpdateCheckTime(context, System.currentTimeMillis())
            LocalVersionManager.setUpdateAvailable(context, true)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                await(scenario) {
                    it.recentSearchesRecyclerView.adapter?.itemCount == 0
                }
                scenario.onActivity { activity ->
                    val insets = requireNotNull(ViewCompat.getRootWindowInsets(activity.window.decorView))
                    assertTrue("The system clock must stay visible",
                        insets.isVisible(WindowInsetsCompat.Type.statusBars()))
                    assertEquals(activity.resources.getBoolean(R.bool.light_status_bar),
                        WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                            .isAppearanceLightStatusBars)
                    val binding = ActivityMainBinding.bind(activity.findViewById<View>(R.id.homeScrollView).parent as View)
                    assertEquals(activity.getString(R.string.btn_check_updates),
                        binding.buttonCheckForUpdates.text.toString())
                    val green = ContextCompat.getColor(activity, R.color.action_green)
                    val onGreen = ContextCompat.getColor(activity, R.color.action_on_green)
                    assertEquals(green, binding.buttonCheckForUpdates.backgroundTintList?.defaultColor)
                    assertEquals(onGreen, binding.buttonCheckForUpdates.currentTextColor)
                    assertEquals(onGreen, binding.buttonCheckForUpdates.iconTint?.defaultColor)
                    binding.fromAutocomplete.setText("Palai", false)
                    binding.toWildcardButton.performClick()
                    binding.buttonSwapLocations.performClick()
                    assertEquals("*", binding.fromAutocomplete.text.toString())
                    assertEquals("Palai", binding.toAutocomplete.text.toString())
                    assertFalse(binding.fromAutocomplete.isPopupShowing)
                    assertFalse(binding.toAutocomplete.isPopupShowing)
                }
                scenario.moveToState(Lifecycle.State.CREATED)
                UserDataManager.addRecentView(context, "home-ui-fixture", "Recently viewed fixture")
                scenario.moveToState(Lifecycle.State.RESUMED)
                await(scenario) {
                    it.recentSearchesRecyclerView.visibility == View.VISIBLE &&
                        it.recentSearchesRecyclerView.adapter?.itemCount == 1
                }
                scenario.recreate()
                await(scenario) {
                    it.recentSearchesRecyclerView.adapter?.itemCount == 1
                }
                scenario.moveToState(Lifecycle.State.CREATED)
                replaceRecentViews(emptyList())
                scenario.moveToState(Lifecycle.State.RESUMED)
                await(scenario) {
                    it.recentSearchesRecyclerView.adapter?.itemCount == 0
                }
            }
        } finally {
            replaceRecentViews(previousRecents)
            LocalVersionManager.saveLastUpdateCheckTime(context, lastCheck)
            LocalVersionManager.setUpdateAvailable(context, hadUpdate)
            if (wasFirstRun) context.getSharedPreferences("SanchariBusPrefs", 0)
                .edit().remove("is_first_run").commit()
        }
    }

    private fun await(scenario: ActivityScenario<MainActivity>, condition: (ActivityMainBinding) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        do {
            var matched = false
            scenario.onActivity { activity ->
                matched = condition(ActivityMainBinding.bind(activity.findViewById<View>(R.id.homeScrollView).parent as View))
            }
            if (matched) return
            SystemClock.sleep(25)
        } while (SystemClock.uptimeMillis() < deadline)
        fail("Main page did not reach the expected recent-service state")
    }

    private fun replaceRecentViews(recents: List<RecentSearch>) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        UserDatabaseHelper(context).writableDatabase.use { db ->
            db.beginTransaction()
            try {
                db.delete(RecentViewTable.TABLE_NAME, null, null)
                recents.forEach { recent ->
                    db.insertOrThrow(RecentViewTable.TABLE_NAME, null, ContentValues().apply {
                        put(RecentViewTable.COLUMN_SERVICE_ID, recent.serviceId)
                        put(RecentViewTable.COLUMN_SERVICE_NAME, recent.serviceName)
                        put(RecentViewTable.COLUMN_VIEWED_TIMESTAMP, recent.viewedTimestamp)
                    })
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }
}
