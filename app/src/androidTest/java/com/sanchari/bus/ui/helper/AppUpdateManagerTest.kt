package com.sanchari.bus.ui.helper

import android.app.Activity
import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.content.ActivityNotFoundException
import android.os.Build
import android.provider.MediaStore
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matcher
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList
import java.io.File
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
        scenario.onActivity {
            manager.checkForUpdates(force, 1, 1, available, complete)
        }
        awaitCheck(scenario, manager)
    }

    private fun awaitCheck(scenario: ActivityScenario<MainActivity>, manager: AppUpdateManager) {
        lateinit var job: Job
        scenario.onActivity {
            job = AppUpdateManager::class.java.getDeclaredField("updateJob").apply {
                isAccessible = true
            }.get(manager) as Job
        }
        runBlocking { job.join() }
        instrumentation.waitForIdleSync()
    }

    @Test
    fun databaseChoiceUpdatesOnlyDatabasesAndKeepsTheAppUpdateAvailable() = fixture { scenario ->
        val installed = AtomicInteger()
        lateinit var manager: AppUpdateManager
        LocalVersionManager.saveLastUpdateCheckTime(context, 1234L)
        scenario.onActivity {
            manager = AppUpdateManager(it, downloadApp = { error("Unexpected APK download") },
                fetchVersions = { info.copy(app = AppVersion(BuildConfig.VERSION_CODE + 1, "fixture")) },
                installDatabase = { _, _, _ -> installed.incrementAndGet(); true })
            manager.showUpdateOptions(1, 1, {}, {})
        }
        clickDialogButton(com.sanchari.bus.R.string.update_check_databases)
        awaitCheck(scenario, manager)
        assertEquals(2, installed.get())
        assertTrue(LocalVersionManager.isUpdateAvailable(context))
        assertEquals(1234L, LocalVersionManager.getLastUpdateCheckTime(context))
        onView(withText(com.sanchari.bus.R.string.update_google_play)).check(doesNotExist())
        onView(withText(containsString("New App Version"))).check(doesNotExist())
    }

    @Test
    fun appChoiceOffersPlayAndApkWithoutUpdatingDatabases() = fixture { scenario ->
        val installed = AtomicInteger()
        var downloaded: AppVersion? = null
        val app = AppVersion(BuildConfig.VERSION_CODE + 1, "fixture")
        lateinit var manager: AppUpdateManager
        LocalVersionManager.saveLastUpdateCheckTime(context, 1234L)
        scenario.onActivity {
            manager = AppUpdateManager(it, downloadApp = { downloaded = it },
                fetchVersions = { info.copy(app = app) },
                installDatabase = { _, _, _ -> installed.incrementAndGet(); true })
            manager.showUpdateOptions(1, 1, {}, {})
        }
        clickDialogButton(com.sanchari.bus.R.string.update_app)
        onView(withText(com.sanchari.bus.R.string.update_google_play)).inRoot(isDialog()).check(matches(isDisplayed()))
        clickDialogButton(com.sanchari.bus.R.string.apk_download)
        awaitCheck(scenario, manager)
        assertEquals(0, installed.get())
        assertTrue(LocalVersionManager.isUpdateAvailable(context))
        assertEquals(1234L, LocalVersionManager.getLastUpdateCheckTime(context))
        onView(withText(containsString("New Bus Timetable"))).check(doesNotExist())
        clickDialogButton(com.sanchari.bus.R.string.apk_download)
        awaitCheck(scenario, manager)
        assertEquals(app, downloaded)
        assertEquals(0, installed.get())
    }

    @Test
    fun manualOptionsAndAvailableUpdateBothOpenGooglePlayWithoutAnInstaller() = fixture { scenario ->
        val opened = CopyOnWriteArrayList<Intent>()
        val firstOpened = CountDownLatch(1)
        val bothOpened = CountDownLatch(2)
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action == Intent.ACTION_VIEW) {
                    opened += intent
                    firstOpened.countDown()
                    bothOpened.countDown()
                    return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                }
                return null
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            lateinit var manager: AppUpdateManager
            scenario.onActivity {
                manager = AppUpdateManager(it, downloadApp = { error("Unexpected APK download") },
                    fetchVersions = { info.copy(app = AppVersion(BuildConfig.VERSION_CODE + 1, "fixture")) },
                    installDatabase = { _, _, _ -> true })
                manager.showUpdateOptions(1, 1, {}, {})
            }
            clickDialogButton(com.sanchari.bus.R.string.update_app)
            clickDialogButton(com.sanchari.bus.R.string.update_google_play)
            assertTrue("Manual update should open Google Play", firstOpened.await(5, TimeUnit.SECONDS))
            runCheck(scenario, manager)
            onView(withText(com.sanchari.bus.R.string.apk_download)).inRoot(isDialog()).check(matches(isDisplayed()))
            clickDialogButton(com.sanchari.bus.R.string.update_google_play)
            assertTrue("Available update should open Google Play", bothOpened.await(5, TimeUnit.SECONDS))
            assertEquals(2, opened.size)
            opened.forEach {
                assertEquals("com.android.vending", it.`package`)
                assertEquals("market://details?id=${context.packageName}", it.data.toString())
                assertNull(it.type)
            }
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(
                        android.content.pm.PackageManager.GET_PERMISSIONS.toLong()
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.GET_PERMISSIONS
                )
            }
            val requested = packageInfo.requestedPermissions.orEmpty()
            assertFalse(requested.contains("android.permission.REQUEST_INSTALL_PACKAGES"))
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    fun apkDownloadShowsProgressThenSavesPickerDestinationWithVersionedName() = fixture { scenario ->
        org.junit.Assume.assumeTrue(Build.VERSION.SDK_INT >= 29)
        val destination = requireNotNull(context.contentResolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "Sanchari-apk-save-test-${System.nanoTime()}.apk")
                put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }))
        val pickerRequests = CopyOnWriteArrayList<Intent>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action == Intent.ACTION_CREATE_DOCUMENT) {
                    pickerRequests += intent
                    return Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(destination))
                }
                if (intent.action == Intent.ACTION_VIEW) error("Must never launch an installer")
                return null
            }
        }
        instrumentation.addMonitor(monitor)
        val release = CompletableDeferred<Unit>()
        lateinit var manager: ApkDownloadManager
        try {
            scenario.onActivity {
                manager = MainActivity::class.java.getDeclaredField("apkDownloadManager")
                    .apply { isAccessible = true }.get(it) as ApkDownloadManager
                val fakeDownload: suspend (String, File) -> Boolean = { _, file ->
                    release.await()
                    File(context.applicationInfo.sourceDir).copyTo(file, overwrite = true)
                    true
                }
                ApkDownloadManager::class.java.getDeclaredField("downloadFile")
                    .apply { isAccessible = true }.set(manager, fakeDownload)
                manager.download(AppVersion(BuildConfig.VERSION_CODE + 1, "fixture"))
            }
            onView(withText(com.sanchari.bus.R.string.apk_downloading)).inRoot(isDialog())
                .check(matches(isDisplayed()))
            release.complete(Unit)
            val deadline = SystemClock.uptimeMillis() + 10_000
            var done = false
            while (!done && SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity {
                    done = pickerRequests.isNotEmpty() &&
                        ApkDownloadManager::class.java.getDeclaredField("stagedFile")
                            .apply { isAccessible = true }.get(manager) == null
                }
                if (!done) SystemClock.sleep(25)
            }
            assertTrue("APK save did not finish", done)
            assertEquals(1, pickerRequests.size)
            assertEquals("Sanchari_v${BuildConfig.VERSION_NAME}.apk",
                pickerRequests.single().getStringExtra(Intent.EXTRA_TITLE))
            assertArrayEquals(File(context.applicationInfo.sourceDir).readBytes(),
                context.contentResolver.openInputStream(destination)!!.use { it.readBytes() })
        } finally {
            release.complete(Unit)
            instrumentation.removeMonitor(monitor)
            context.contentResolver.delete(destination, null, null)
        }
    }

    @Test
    fun cancelledApkPickerRemovesTheTemporaryDownloadAndDoesNotLaunchAnInstaller() = fixture { scenario ->
        val requests = CopyOnWriteArrayList<Intent>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                requests += intent
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            val manager = configureApkDownload(scenario)
            scenario.onActivity { manager.download(AppVersion(12345, "fixture")) }
            awaitApkCompletion(scenario, manager)
            assertEquals(1, requests.size)
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, requests.single().action)
            assertEquals("Sanchari_v12345.apk", requests.single().getStringExtra(Intent.EXTRA_TITLE))
            scenario.onActivity {
                assertNull(ApkDownloadManager::class.java.getDeclaredField("stagedFile")
                    .apply { isAccessible = true }.get(manager))
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    fun pausingAnApkDownloadCancelsWorkAndRemovesItsIncompleteFile() = fixture { scenario ->
        val manager = configureApkDownload(scenario)
        val started = CountDownLatch(1)
        lateinit var staged: File
        scenario.onActivity {
            val download: suspend (String, File) -> Boolean = { _, file ->
                staged = file
                file.writeText("Incomplete APK")
                started.countDown()
                awaitCancellation()
            }
            ApkDownloadManager::class.java.getDeclaredField("downloadFile")
                .apply { isAccessible = true }.set(manager, download)
            manager.download(AppVersion(12345, "fixture"))
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        assertTrue(staged.exists())
        lateinit var job: Job
        scenario.onActivity {
            manager.onPause()
            job = ApkDownloadManager::class.java.getDeclaredField("job")
                .apply { isAccessible = true }.get(manager) as Job
        }
        runBlocking { job.join() }
        assertFalse(staged.exists())
        scenario.onActivity {
            assertNull(ApkDownloadManager::class.java.getDeclaredField("stagedFile")
                .apply { isAccessible = true }.get(manager))
            assertNull(ApkDownloadManager::class.java.getDeclaredField("progress")
                .apply { isAccessible = true }.get(manager))
        }
    }

    @Test
    fun missingPickerPublishesVersionedApkInDownloads() = fixture { scenario ->
        org.junit.Assume.assumeTrue(Build.VERSION.SDK_INT >= 29)
        val version = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        val name = "Sanchari_v$version.apk"
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action == Intent.ACTION_CREATE_DOCUMENT) throw ActivityNotFoundException("Test: no picker")
                error("Unexpected external activity: ${intent.action}")
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            val manager = configureApkDownload(scenario)
            scenario.onActivity { manager.download(AppVersion(version, "fixture")) }
            awaitApkCompletion(scenario, manager)
            context.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.IS_PENDING),
                "${MediaStore.Downloads.DISPLAY_NAME} = ?", arrayOf(name), null)!!.use { cursor ->
                assertTrue("APK should be available in Downloads", cursor.moveToFirst())
                assertEquals(0, cursor.getInt(1))
                val uri = android.content.ContentUris.withAppendedId(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0))
                assertEquals("APK fixture", context.contentResolver.openInputStream(uri)!!
                    .bufferedReader().use { it.readText() })
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            context.contentResolver.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                "${MediaStore.Downloads.DISPLAY_NAME} = ?", arrayOf(name))
        }
    }

    @Test
    fun googlePlayFallsBackToTheSameAppListingInABrowser() = fixture { scenario ->
        val requests = CopyOnWriteArrayList<Intent>()
        val opened = CountDownLatch(1)
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                requests += intent
                if (intent.data?.scheme == "market") throw ActivityNotFoundException("Test: no Play Store")
                opened.countDown()
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            scenario.onActivity {
                AppUpdateManager(it, downloadApp = { error("Unexpected APK download") })
                    .showUpdateOptions(1, 1, {}, {})
            }
            clickDialogButton(com.sanchari.bus.R.string.update_app)
            clickDialogButton(com.sanchari.bus.R.string.update_google_play)
            assertTrue("The browser listing should open", opened.await(5, TimeUnit.SECONDS))
            assertEquals(2, requests.size)
            assertEquals("https://play.google.com/store/apps/details?id=${context.packageName}",
                requests.last().data.toString())
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun configureApkDownload(scenario: ActivityScenario<MainActivity>): ApkDownloadManager {
        lateinit var manager: ApkDownloadManager
        scenario.onActivity {
            manager = MainActivity::class.java.getDeclaredField("apkDownloadManager")
                .apply { isAccessible = true }.get(it) as ApkDownloadManager
            val download: suspend (String, File) -> Boolean = { _, file ->
                file.writeText("APK fixture")
                true
            }
            ApkDownloadManager::class.java.getDeclaredField("downloadFile")
                .apply { isAccessible = true }.set(manager, download)
        }
        return manager
    }

    private fun clickDialogButton(text: Int) {
        onView(withText(text)).inRoot(isDialog()).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = isDisplayed()
            override fun getDescription(): String = "Activate a visible dialog button"
            override fun perform(uiController: UiController, view: View) {
                assertTrue(view.performClick())
                uiController.loopMainThreadUntilIdle()
            }
        })
    }

    private fun awaitApkCompletion(scenario: ActivityScenario<MainActivity>, manager: ApkDownloadManager) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        var done = false
        while (!done && SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity {
                val job = ApkDownloadManager::class.java.getDeclaredField("job")
                    .apply { isAccessible = true }.get(manager) as Job?
                val staged = ApkDownloadManager::class.java.getDeclaredField("stagedFile")
                    .apply { isAccessible = true }.get(manager)
                done = job?.isCompleted == true && staged == null
            }
            if (!done) SystemClock.sleep(25)
        }
        assertTrue("APK operation did not finish", done)
    }

    @Test
    fun sevenDayBoundaryAndManualBypassArePreserved() = fixture { scenario ->
        assertFalse(AppUpdateManager.isCheckDue(1L, 604800000L))
        assertTrue(AppUpdateManager.isCheckDue(1L, 604800001L))
        assertTrue(AppUpdateManager.isCheckDue(0L, 1L))
        val fetched = AtomicInteger()
        lateinit var manager: AppUpdateManager
        scenario.onActivity { manager = AppUpdateManager(it, downloadApp = { error("Unexpected APK download") }, fetchVersions = {
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
        scenario.onActivity { manager = AppUpdateManager(it, downloadApp = { error("Unexpected APK download") },
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
        scenario.onActivity { manager = AppUpdateManager(it, downloadApp = { error("Unexpected APK download") }, fetchVersions = { info },
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
        scenario.onActivity { manager = AppUpdateManager(it, downloadApp = { error("Unexpected APK download") }, fetchVersions = { info },
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
        scenario.onActivity { manager = AppUpdateManager(it, downloadApp = { error("Unexpected APK download") }, fetchVersions = { info },
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
            manager = AppUpdateManager(it, downloadApp = { error("Unexpected APK download") }, fetchVersions = { info }, installDatabase = { _, _, _ ->
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
