package com.sanchari.bus.ui.helper

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sanchari.bus.BuildConfig
import com.sanchari.bus.data.local.DatabaseManager
import com.sanchari.bus.data.local.LocalVersionManager
import com.sanchari.bus.data.model.AppVersion
import com.sanchari.bus.data.model.DatabaseVersion
import com.sanchari.bus.data.model.ServerVersionInfo
import com.sanchari.bus.ui.activity.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matchers.containsString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import android.os.SystemClock

@RunWith(AndroidJUnit4::class)
class AppUpdateManagerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val currentTime = System.currentTimeMillis()
    private val info = ServerVersionInfo(DatabaseVersion(2, "fixture"), DatabaseVersion(2, "fixture"))

    private fun fixture(test: (ActivityScenario<MainActivity>) -> Unit) {
        assertTrue("Do not retry real contributions in tests",
            context.filesDir.resolve("suggestions").listFiles().orEmpty().none { it.extension == "json" })
        val prefs = context.getSharedPreferences("SanchariBusPrefs", 0)
        val saved = prefs.all
        try {
            runBlocking { DatabaseManager.initializeDatabases(context) }
            LocalVersionManager.setFirstRunCompleted(context)
            LocalVersionManager.saveLastUpdateCheckTime(context, currentTime)
            LocalVersionManager.setAutoDatabaseUpdatesEnabled(context, true)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val deadline = SystemClock.uptimeMillis() + 10_000
                var ready = false
                do {
                    scenario.onActivity {
                        ready = MainActivity::class.java.getDeclaredField("dataInitialized").apply {
                            isAccessible = true
                        }.getBoolean(it)
                    }
                    if (!ready) SystemClock.sleep(20)
                } while (!ready && SystemClock.uptimeMillis() < deadline)
                assertTrue("Main initialization did not finish", ready)
                var ownedJob: Job? = null
                scenario.onActivity {
                    val owned = MainActivity::class.java.getDeclaredField("appUpdateManager").apply {
                        isAccessible = true
                    }.get(it) as AppUpdateManager
                    ownedJob = AppUpdateManager::class.java.getDeclaredField("updateJob").apply {
                        isAccessible = true
                    }.get(owned) as Job?
                }
                runBlocking { ownedJob?.join() }
                test(scenario)
            }
        } finally {
            val editor = prefs.edit().clear()
            for ((key, value) in saved) {
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is String -> editor.putString(key, value)
                    is Float -> editor.putFloat(key, value)
                    else -> error("Unexpected update preference type")
                }
            }
            editor.commit()
        }
    }

    private fun runCheck(scenario: ActivityScenario<MainActivity>, manager: AppUpdateManager, force: Boolean = true,
                         available: (Boolean) -> Unit = {}, complete: () -> Unit = {}) {
        lateinit var job: Job
        scenario.onActivity {
            manager.checkForUpdates(force, 1, 1, available, complete)
            job = AppUpdateManager::class.java.getDeclaredField("updateJob").apply {
                isAccessible = true
            }.get(manager) as Job
        }
        runBlocking { job.join() }
        instrumentation.waitForIdleSync()
    }

    @Test
    fun sevenDayBoundaryAndManualBypassArePreserved() = fixture { scenario ->
        assertFalse(AppUpdateManager.isCheckDue(1L, 604800000L))
        assertTrue(AppUpdateManager.isCheckDue(1L, 604800001L))
        assertTrue(AppUpdateManager.isCheckDue(0L, 1L))
        val fetched = AtomicInteger()
        lateinit var manager: AppUpdateManager
        scenario.onActivity { manager = AppUpdateManager(it, fetchVersions = {
            fetched.incrementAndGet()
            ServerVersionInfo(DatabaseVersion(1, "fixture"), DatabaseVersion(1, "fixture"))
        }, now = { currentTime }) }
        runCheck(scenario, manager, force = false)
        assertEquals(0, fetched.get())
        runCheck(scenario, manager)
        assertEquals(1, fetched.get())
    }

    @Test
    fun automaticDataUpdatesDoNotAskAndKeepApkConfirmationAndBadge() = fixture { scenario ->
        val installed = AtomicInteger()
        val refreshed = AtomicInteger()
        lateinit var manager: AppUpdateManager
        scenario.onActivity { manager = AppUpdateManager(it,
            fetchVersions = { info.copy(app = AppVersion(BuildConfig.VERSION_CODE + 1, "https://example.invalid/app.apk")) },
            installDatabase = { _, _, _ -> installed.incrementAndGet(); true }) }
        LocalVersionManager.saveLastUpdateCheckTime(context, 0L)
        runCheck(scenario, manager, force = false, complete = { refreshed.incrementAndGet() })
        assertEquals(2, installed.get())
        assertEquals(1, refreshed.get())
        assertTrue(LocalVersionManager.isUpdateAvailable(context))
        onView(withText(containsString("New App Version"))).inRoot(isDialog()).check(matches(isDisplayed()))
        onView(withText(containsString("New Bus Timetable"))).check(doesNotExist())
        onView(withText("Later")).inRoot(isDialog()).perform(click())
    }

    @Test
    fun completeAutomaticUpdateClearsBadgeWithoutConfirmation() = fixture { scenario ->
        val installed = AtomicInteger()
        val refreshed = AtomicInteger()
        lateinit var manager: AppUpdateManager
        scenario.onActivity { manager = AppUpdateManager(it, fetchVersions = { info },
            installDatabase = { _, _, _ -> installed.incrementAndGet(); true }) }
        runCheck(scenario, manager, complete = { refreshed.incrementAndGet() })
        assertEquals(2, installed.get())
        assertEquals(1, refreshed.get())
        assertFalse(LocalVersionManager.isUpdateAvailable(context))
        assertTrue(LocalVersionManager.getLastUpdateCheckTime(context) > 0L)
        onView(withText("Updates Available")).check(doesNotExist())
    }

    @Test
    fun disablingAutomationKeepsDatabasePromptWithoutDownloading() = fixture { scenario ->
        LocalVersionManager.setAutoDatabaseUpdatesEnabled(context, false)
        val installed = AtomicInteger()
        lateinit var manager: AppUpdateManager
        scenario.onActivity { manager = AppUpdateManager(it, fetchVersions = { info },
            installDatabase = { _, _, _ -> installed.incrementAndGet(); true }) }
        runCheck(scenario, manager)
        assertEquals(0, installed.get())
        onView(withText(containsString("New Bus Timetable"))).inRoot(isDialog()).check(matches(isDisplayed()))
        onView(withText("Later")).inRoot(isDialog()).perform(click())
    }

    @Test
    fun partialSuccessRefreshesDataKeepsBadgeAndAllowsRetry() = fixture { scenario ->
        val refreshed = AtomicInteger()
        lateinit var manager: AppUpdateManager
        scenario.onActivity { manager = AppUpdateManager(it, fetchVersions = { info },
            installDatabase = { name, _, _ -> name.startsWith("Timetable") }) }
        runCheck(scenario, manager, complete = { refreshed.incrementAndGet() })
        assertEquals(1, refreshed.get())
        assertTrue(LocalVersionManager.isUpdateAvailable(context))
        assertEquals(0L, LocalVersionManager.getLastUpdateCheckTime(context))
        onView(withText("Updates Available")).check(doesNotExist())
    }

    @Test
    fun pauseCancelsUnfinishedDownloadsAndPermitsLaterRetry() = fixture { scenario ->
        val started = CountDownLatch(1)
        val refreshed = AtomicInteger()
        lateinit var manager: AppUpdateManager
        lateinit var job: Job
        scenario.onActivity {
            manager = AppUpdateManager(it, fetchVersions = { info }, installDatabase = { _, _, _ ->
                started.countDown()
                awaitCancellation()
            })
            manager.checkForUpdates(true, 1, 1, onUpdateComplete = { refreshed.incrementAndGet() })
            job = AppUpdateManager::class.java.getDeclaredField("updateJob").apply {
                isAccessible = true
            }.get(manager) as Job
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        scenario.onActivity { manager.onPause() }
        runBlocking { job.join() }
        assertEquals(0, refreshed.get())
        assertEquals(0L, LocalVersionManager.getLastUpdateCheckTime(context))
    }
}
