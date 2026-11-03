package com.sanchari.bus.ui.activity

import android.content.ContentValues
import android.content.Intent
import android.app.Activity
import android.app.Instrumentation
import android.graphics.Rect
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sanchari.bus.R
import com.sanchari.bus.data.local.DatabaseConstants.RecentViewTable
import com.sanchari.bus.data.local.DatabaseManager
import com.sanchari.bus.data.local.LocalVersionManager
import com.sanchari.bus.data.local.UserDatabaseHelper
import com.sanchari.bus.data.manager.UserDataManager
import com.sanchari.bus.data.manager.SearchManager
import com.sanchari.bus.data.model.ServerVersionInfo
import com.sanchari.bus.data.model.DatabaseVersion
import com.sanchari.bus.data.model.AppVersion
import com.sanchari.bus.ui.helper.AppUpdateManager
import com.sanchari.bus.BuildConfig
import com.sanchari.bus.data.model.RecentSearch
import com.sanchari.bus.databinding.ActivityMainBinding
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matchers.anything
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class MainPageActivityTest {
    @Test
    fun primaryActionsStayVisibleAndAllFiveVerticalRecentsRemainReachable() = isolatedHome {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val services = SearchManager.searchBusServicesByName(context, "").take(5)
        assertEquals(5, services.size)
        replaceRecentViews(services.mapIndexed { index, service ->
            RecentSearch(service.serviceId, "Long service name through several places ${service.name}",
                System.currentTimeMillis() - index)
        })
        val captured = CopyOnWriteArrayList<Intent>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.component?.className == BusDetailsActivity::class.java.name) {
                    captured += intent
                    return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                }
                return null
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                await(scenario) { it.recentSearchesRecyclerView.adapter?.itemCount == 5 }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val binding = mainBinding(activity)
                    assertTrue(activity.resources.configuration.screenWidthDp >= 360)
                    assertEquals(1f, activity.resources.configuration.fontScale)
                    val insets = requireNotNull(ViewCompat.getRootWindowInsets(activity.window.decorView))
                    assertTrue(insets.isVisible(WindowInsetsCompat.Type.statusBars()))
                    assertFalse(insets.isVisible(WindowInsetsCompat.Type.ime()))
                    val visible = Rect()
                    assertTrue(binding.homePrimaryActions.getGlobalVisibleRect(visible))
                    assertEquals(binding.homePrimaryActions.height, visible.height())
                    assertEquals(0, binding.homeScrollView.scrollY)
                    val list = binding.recentSearchesRecyclerView
                    val manager = list.layoutManager as androidx.recyclerview.widget.LinearLayoutManager
                    assertEquals(androidx.recyclerview.widget.LinearLayoutManager.VERTICAL, manager.orientation)
                    assertFalse(list.isNestedScrollingEnabled)
                    val first = requireNotNull(list.findViewHolderForAdapterPosition(0)).itemView
                    assertEquals(list.width, first.width)
                    assertTrue(first.contentDescription.contains(services.first().name))
                    binding.homeScrollView.scrollTo(0, binding.homeContent.height)
                }
                await(scenario) { it.recentSearchesRecyclerView.findViewHolderForAdapterPosition(4) != null }
                scenario.onActivity {
                    val last = requireNotNull(mainBinding(it).recentSearchesRecyclerView
                        .findViewHolderForAdapterPosition(4)).itemView
                    val visible = Rect()
                    assertTrue(last.getGlobalVisibleRect(visible))
                    assertEquals(last.height, visible.height())
                    last.performClick()
                }
                val deadline = SystemClock.uptimeMillis() + 5_000
                while (captured.isEmpty() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(25)
                assertEquals(1, captured.size)
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    fun importantActionsRemainReadableAndReachableWithLargeTextAndInlineErrors() = isolatedHome {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val services = SearchManager.searchBusServicesByName(context, "").take(5)
        replaceRecentViews(services.mapIndexed { index, service ->
            RecentSearch(service.serviceId, service.name, System.currentTimeMillis() - index)
        })
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await(scenario) { it.recentSearchesRecyclerView.adapter?.itemCount == 5 }
            scenario.onActivity { activity ->
                val binding = mainBinding(activity)
                binding.fromAutocomplete.setText("A long existing stop name", false)
                binding.toAutocomplete.setText("Another long existing stop name", false)
                binding.fromInputLayout.error = activity.getString(R.string.stop_name_not_found)
                binding.toInputLayout.error = activity.getString(R.string.stop_name_not_found)
                binding.root.clearFocus()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val binding = mainBinding(activity)
                for (button in listOf(binding.buttonAddNewBus, binding.buttonDrafts)) {
                    assertTrue(button.height >= 48 * activity.resources.displayMetrics.density)
                    assertTrue(button.height >= button.layout.height +
                        button.compoundPaddingTop + button.compoundPaddingBottom)
                    for (line in 0 until button.layout.lineCount) assertEquals(0, button.layout.getEllipsisCount(line))
                }
                if (activity.resources.configuration.fontScale > 1.2f) {
                    assertEquals(android.widget.LinearLayout.VERTICAL, binding.homePrimaryActions.orientation)
                }
                binding.homeScrollView.scrollTo(0, binding.homeContent.height)
                val visible = Rect()
                assertTrue(binding.recentSearchesRecyclerView.getGlobalVisibleRect(visible))
                assertTrue(binding.homeScrollView.scrollY + binding.homeScrollView.height >= binding.homeContent.height)
            }
        }
    }

    @Test
    fun overflowManualUpdateShareAdminAndUpdateIndicatorUseExistingFlows() = isolatedHome {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        LocalVersionManager.saveLatestAppUrl(context, "https://example.invalid/sanchari.apk")
        val fetched = AtomicInteger()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val shares = CopyOnWriteArrayList<Intent>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action == Intent.ACTION_CHOOSER) {
                    shares += intent
                    return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                }
                return null
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                await(scenario) {
                    MainActivity::class.java.getDeclaredField("dataInitialized")
                        .apply { isAccessible = true }.getBoolean(it.root.context as MainActivity)
                }
                scenario.onActivity { activity ->
                    val manager = AppUpdateManager(activity, fetchVersions = {
                        fetched.incrementAndGet()
                        ServerVersionInfo(
                            DatabaseVersion(LocalVersionManager.getTimetableDbVersion(context), "fixture"),
                            DatabaseVersion(LocalVersionManager.getCommunityDbVersion(context), "fixture"),
                            AppVersion(BuildConfig.VERSION_CODE + 1, "https://example.invalid/update.apk")
                        )
                    })
                    MainActivity::class.java.getDeclaredField("appUpdateManager")
                        .apply { isAccessible = true }.set(activity, manager)
                    mainBinding(activity).homeOverflowButton.performClick()
                }
                onView(withText(R.string.btn_check_updates)).inRoot(isPlatformPopup()).perform(click())
                await(scenario) { it.homeOverflowButton.isSelected }
                assertEquals(1, fetched.get())
                onView(withText("Later")).inRoot(isDialog()).perform(click())
                scenario.onActivity { activity ->
                    val button = mainBinding(activity).homeOverflowButton
                    val image = Bitmap.createBitmap(button.width, button.height, Bitmap.Config.ARGB_8888)
                    button.draw(Canvas(image))
                    assertEquals(ContextCompat.getColor(activity, R.color.action_green), image.getPixel(8, button.height / 2))
                    image.recycle()
                    button.performClick()
                }
                onView(withText(R.string.home_menu_share)).inRoot(isPlatformPopup()).perform(click())
                assertEquals(1, shares.size)
                @Suppress("DEPRECATION")
                val send = shares.single().getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
                assertEquals(Intent.ACTION_SEND, send.action)
                assertTrue(send.getStringExtra(Intent.EXTRA_TEXT)!!.contains("https://example.invalid/update.apk"))
                scenario.onActivity { mainBinding(it).homeOverflowButton.performClick() }
                onView(withText(R.string.home_menu_message_admin)).inRoot(isPlatformPopup()).perform(click())
                onView(withId(R.id.editTextMessage)).inRoot(isDialog()).check(matches(isDisplayed()))
                onView(withText(android.R.string.cancel)).inRoot(isDialog()).perform(click())
                scenario.onActivity { activity ->
                    val available = MainActivity::class.java.getDeclaredMethod("updateAvailabilityUI", Boolean::class.javaPrimitiveType)
                        .apply { isAccessible = true }
                    available.invoke(activity, false)
                    assertFalse(mainBinding(activity).homeOverflowButton.isSelected)
                    assertEquals(activity.getString(R.string.home_more_options),
                        mainBinding(activity).homeOverflowButton.contentDescription)
                }
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun mainBinding(activity: MainActivity) =
        ActivityMainBinding.bind(activity.findViewById<View>(R.id.homeScrollView).parent as View)

    private fun isolatedHome(test: () -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("Do not retry real contributions",
            context.filesDir.resolve("suggestions").listFiles().orEmpty().none { it.extension == "json" })
        val prefs = context.getSharedPreferences("SanchariBusPrefs", 0)
        val saved = prefs.all
        val previousRecents = UserDataManager.getRecentViews(context)
        try {
            runBlocking { DatabaseManager.initializeDatabases(context) }
            LocalVersionManager.setFirstRunCompleted(context)
            LocalVersionManager.saveLastUpdateCheckTime(context, System.currentTimeMillis())
            LocalVersionManager.setAutoDatabaseUpdatesEnabled(context, false)
            LocalVersionManager.setUpdateAvailable(context, false)
            test()
        } finally {
            replaceRecentViews(previousRecents)
            val editor = prefs.edit().clear()
            saved.forEach { (key, value) ->
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is String -> editor.putString(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    else -> error("Unexpected preference type")
                }
            }
            editor.commit()
        }
    }

    @Test
    fun recentServicesSwapAndOriginalUpdateLabelWorkAcrossResumeAndRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("This test must not retry existing queued contributions",
            context.filesDir.resolve("suggestions").listFiles().orEmpty().none { it.extension == "json" })
        val wasFirstRun = LocalVersionManager.isFirstRun(context)
        val lastCheck = LocalVersionManager.getLastUpdateCheckTime(context)
        val hadUpdate = LocalVersionManager.isUpdateAvailable(context)
        val autoUpdates = LocalVersionManager.autoDatabaseUpdatesEnabled(context)
        val previousRecents = UserDataManager.getRecentViews(context)
        try {
            runBlocking { DatabaseManager.initializeDatabases(context) }
            replaceRecentViews(emptyList())
            LocalVersionManager.setFirstRunCompleted(context)
            LocalVersionManager.saveLastUpdateCheckTime(context, System.currentTimeMillis())
            LocalVersionManager.setUpdateAvailable(context, true)
            context.getSharedPreferences("SanchariBusPrefs", 0).edit().remove("auto_database_updates").commit()
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
                    assertTrue(LocalVersionManager.autoDatabaseUpdatesEnabled(activity))
                    val onGreen = ContextCompat.getColor(activity, R.color.action_on_green)
                    assertTrue(binding.homeOverflowButton.isSelected)
                    assertEquals(onGreen, binding.homeOverflowButton.imageTintList?.defaultColor)
                    assertEquals(activity.getString(R.string.home_more_options_update),
                        binding.homeOverflowButton.contentDescription)
                    binding.homeOverflowButton.performClick()
                }
                for (label in listOf(R.string.btn_check_updates, R.string.home_menu_share,
                    R.string.home_menu_message_admin, R.string.home_menu_auto_updates,
                    R.string.home_menu_version)) {
                    onView(withText(label)).inRoot(isPlatformPopup()).check(matches(isDisplayed()))
                }
                onView(withText(R.string.home_menu_auto_updates)).inRoot(isPlatformPopup()).perform(click())
                assertFalse(LocalVersionManager.autoDatabaseUpdatesEnabled(context))
                scenario.onActivity { activity ->
                    val binding = ActivityMainBinding.bind(activity.findViewById<View>(R.id.homeScrollView).parent as View)
                    binding.fromAutocomplete.setText("Palai", false)
                    binding.toAutocomplete.requestFocus()
                    binding.stopWildcardButton.performClick()
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
                scenario.onActivity { activity ->
                    ActivityMainBinding.bind(activity.findViewById<View>(R.id.homeScrollView).parent as View)
                        .stopLocationButton.performClick()
                }
                onData(anything()).inRoot(isDialog()).atPosition(1).perform(click())
                onView(withId(R.id.coordinatesInput)).inRoot(isDialog())
                    .perform(replaceText("12.25, 77.5"), closeSoftKeyboard())
                onView(withId(R.id.radiusInput)).inRoot(isDialog())
                    .perform(replaceText("12.5"), closeSoftKeyboard())
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                onView(withId(R.id.coordinatesInput)).inRoot(isDialog()).check(matches(withText("12.25, 77.5")))
                onView(withId(R.id.radiusInput)).inRoot(isDialog()).check(matches(withText("12.5")))
                scenario.recreate()
                await(scenario) {
                    it.recentSearchesRecyclerView.adapter?.itemCount == 1
                }
                scenario.onActivity { activity ->
                    val binding = ActivityMainBinding.bind(activity.findViewById<View>(R.id.homeScrollView).parent as View)
                    assertFalse(LocalVersionManager.autoDatabaseUpdatesEnabled(activity))
                    assertEquals(activity.getString(R.string.location_to_action),
                        binding.stopLocationButton.contentDescription)
                }
                onView(withId(R.id.coordinatesInput)).inRoot(isDialog()).check(matches(withText("12.25, 77.5")))
                onView(withId(R.id.radiusInput)).inRoot(isDialog()).check(matches(withText("12.5")))
                onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click())
                scenario.onActivity { activity ->
                    ActivityMainBinding.bind(activity.findViewById<View>(R.id.homeScrollView).parent as View)
                        .homeOverflowButton.performClick()
                }
                onView(withText(R.string.home_menu_version)).inRoot(isPlatformPopup()).perform(click())
                onView(withText(R.string.version_information)).inRoot(isDialog()).check(matches(isDisplayed()))
                onView(withText(android.R.string.ok)).inRoot(isDialog()).perform(click())
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
            LocalVersionManager.setAutoDatabaseUpdatesEnabled(context, autoUpdates)
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
