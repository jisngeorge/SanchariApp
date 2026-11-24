package com.sanchari.bus.ui.activity

import android.os.SystemClock
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.longClick
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.sanchari.bus.BuildConfig
import com.sanchari.bus.R
import com.sanchari.bus.data.local.LocalVersionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsActivityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun settingsShowVersionsAppUpdateOptionsAndOpenDebugOnLongPress() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val code = activity.findViewById<TextView>(R.id.versionCode)
                assertTrue(code.text.contains(BuildConfig.VERSION_CODE.toString()))
                assertTrue(code.isLongClickable)
                assertTrue(activity.findViewById<TextView>(R.id.timetableVersion).text
                    .contains("v${LocalVersionManager.getTimetableDbVersion(activity)}"))
                assertEquals(LocalVersionManager.autoDatabaseUpdatesEnabled(activity),
                    activity.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(
                        R.id.autoDatabaseUpdates).isChecked)
            }
            onView(withId(R.id.checkAppUpdate)).perform(click())
            onView(withText(R.string.update_google_play)).inRoot(isDialog()).check(matches(isDisplayed()))
            onView(withText(R.string.apk_download)).inRoot(isDialog()).check(matches(isDisplayed()))
            onView(withText(android.R.string.cancel)).inRoot(isDialog()).perform(click())

            onView(withId(R.id.versionCode)).perform(longClick())
            val deadline = SystemClock.uptimeMillis() + 5_000
            var debug: DebugActivity? = null
            while (debug == null && SystemClock.uptimeMillis() < deadline) {
                instrumentation.runOnMainSync {
                    debug = ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(Stage.RESUMED).filterIsInstance<DebugActivity>().firstOrNull()
                }
                if (debug == null) SystemClock.sleep(50)
            }
            val opened = requireNotNull(debug) { "Long-pressing the version code should open the debug page" }
            var report = ""
            while (SystemClock.uptimeMillis() < deadline + 5_000) {
                instrumentation.runOnMainSync { report = opened.findViewById<TextView>(R.id.debugText).text.toString() }
                if (report.isNotEmpty()) break
                SystemClock.sleep(50)
            }
            assertTrue(report.contains(BuildConfig.VERSION_NAME))
            assertFalse(report.contains("uuid", ignoreCase = true))
            pressBack()
        }
    }
}
