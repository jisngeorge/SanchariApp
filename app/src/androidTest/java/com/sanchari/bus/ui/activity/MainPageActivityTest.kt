package com.sanchari.bus.ui.activity

import android.content.ContentValues
import android.content.Intent
import android.app.Activity
import android.app.Instrumentation
import android.graphics.Rect
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.view.View
import android.widget.AdapterView
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
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
import org.hamcrest.Matcher
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class MainPageActivityTest {
    @Test
    fun statusBarRemainsReadableInBothThemesAcrossRecreationAndDrawerChanges() = isolatedHome {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val previousMode = AppCompatDelegate.getDefaultNightMode()
        try {
            for (mode in listOf(AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.MODE_NIGHT_YES)) {
                instrumentation.runOnMainSync { AppCompatDelegate.setDefaultNightMode(mode) }
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    await(scenario) {
                        ViewCompat.getRootWindowInsets(it.root)?.isVisible(
                            WindowInsetsCompat.Type.statusBars()) == true
                    }
                    fun assertReadable() {
                        scenario.onActivity { activity ->
                            val decor = activity.window.decorView
                            assertTrue(requireNotNull(ViewCompat.getRootWindowInsets(decor))
                                .isVisible(WindowInsetsCompat.Type.statusBars()))
                            val lightIcons = !WindowCompat.getInsetsController(activity.window, decor)
                                .isAppearanceLightStatusBars
                            assertEquals(mode == AppCompatDelegate.MODE_NIGHT_YES, lightIcons)
                            val backdrop = mainBinding(activity).homeDrawer.statusBarBackgroundDrawable
                            val background = (backdrop as ColorDrawable).color
                            assertEquals(ContextCompat.getColor(activity, R.color.home_background), background)
                            assertTrue(ColorUtils.calculateContrast(
                                if (lightIcons) Color.WHITE else Color.BLACK, background) >= 4.5)
                        }
                    }
                    assertReadable()
                    scenario.onActivity {
                        mainBinding(it).homeDrawer.openDrawer(mainBinding(it).homeNavigation, false)
                    }
                    assertReadable()
                    scenario.recreate()
                    assertReadable()
                }
            }
        } finally {
            instrumentation.runOnMainSync { AppCompatDelegate.setDefaultNightMode(previousMode) }
        }
    }

    @Test
    fun populatedStopFieldsShowClearImmediatelyOnFocusAndClearOnlyThatField() = isolatedHome {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await(scenario) {
                MainActivity::class.java.getDeclaredField("dataInitialized")
                    .apply { isAccessible = true }.getBoolean(it.root.context as MainActivity)
            }
            scenario.onActivity {
                val binding = mainBinding(it)
                binding.fromAutocomplete.setText("From fixture", false)
                binding.toAutocomplete.setText("To fixture", false)
                binding.busNameAutocomplete.requestFocus()
                assertFalse(binding.fromInputLayout.isEndIconVisible)
                assertFalse(binding.toInputLayout.isEndIconVisible)
                binding.fromAutocomplete.requestFocus()
                assertTrue(binding.fromInputLayout.isEndIconVisible)
                assertFalse(binding.toInputLayout.isEndIconVisible)
                binding.toAutocomplete.requestFocus()
                assertFalse(binding.fromInputLayout.isEndIconVisible)
                assertTrue(binding.toInputLayout.isEndIconVisible)
                binding.toInputLayout.findViewById<View>(
                    com.google.android.material.R.id.text_input_end_icon).performClick()
                assertEquals("", binding.toAutocomplete.text.toString())
                assertEquals("From fixture", binding.fromAutocomplete.text.toString())
                assertFalse(binding.toInputLayout.isEndIconVisible)
            }
        }
    }

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
    fun drawerManualUpdateShareAdminAndUpdateIndicatorUseExistingFlows() = isolatedHome {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        LocalVersionManager.saveLatestAppUrl(context, "https://example.invalid/sanchari.apk")
        val fetched = AtomicInteger()
        val failFetch = java.util.concurrent.atomic.AtomicBoolean(false)
        val installed = AtomicInteger()
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
                    val manager = AppUpdateManager(activity, downloadApp = { error("Unexpected APK download") }, fetchVersions = {
                        fetched.incrementAndGet()
                        if (failFetch.get()) null else ServerVersionInfo(
                            DatabaseVersion(LocalVersionManager.getTimetableDbVersion(context), "fixture"),
                            DatabaseVersion(LocalVersionManager.getCommunityDbVersion(context), "fixture"),
                            AppVersion(BuildConfig.VERSION_CODE + 1, "https://example.invalid/update.apk")
                        )
                    }, installDatabase = { _, _, _ -> installed.incrementAndGet(); true })
                    MainActivity::class.java.getDeclaredField("appUpdateManager")
                        .apply { isAccessible = true }.set(activity, manager)
                    assertEquals(View.GONE, mainBinding(activity).homeUpdateButton.visibility)
                    mainBinding(activity).homeMenuButton.performClick()
                }
                await(scenario) { it.homeDrawer.isDrawerOpen(it.homeNavigation) }
                onView(withText(R.string.home_menu_check_db_updates)).perform(click())
                await(scenario) { it.homeUpdateButton.visibility == View.VISIBLE }
                assertEquals(1, fetched.get())
                assertEquals("Database-only check must not download unchanged data", 0, installed.get())
                onView(withText(R.string.apk_download)).check(doesNotExist())

                scenario.onActivity { activity ->
                    val button = mainBinding(activity).homeUpdateButton
                    assertEquals(ContextCompat.getColor(activity, R.color.home_update_arrow), button.imageTintList?.defaultColor)
                    button.performClick()
                }
                awaitDialogText(R.string.updates_list_title)
                assertEquals("Tapping the arrow re-checks for updates", 2, fetched.get())
                onView(withId(R.id.updateGooglePlay)).inRoot(isDialog()).check(matches(isDisplayed()))
                onView(withId(R.id.updateApk)).inRoot(isDialog()).check(matches(isDisplayed()))
                onView(withId(R.id.timetableUpdateRow)).inRoot(isDialog())
                    .check(matches(org.hamcrest.Matchers.not(isDisplayed())))
                onView(withText(R.string.updates_later)).inRoot(isDialog()).perform(activate())

                failFetch.set(true)
                scenario.onActivity { mainBinding(it).homeUpdateButton.performClick() }
                awaitDialogText(R.string.updates_list_title)
                assertEquals(3, fetched.get())
                onView(withId(R.id.updateApk)).inRoot(isDialog()).check(matches(isDisplayed()))
                onView(withText(R.string.updates_later)).inRoot(isDialog()).perform(activate())
                scenario.onActivity { assertEquals(View.VISIBLE, mainBinding(it).homeUpdateButton.visibility) }

                scenario.onActivity { mainBinding(it).homeMenuButton.performClick() }
                await(scenario) { it.homeDrawer.isDrawerOpen(it.homeNavigation) }
                onView(withText(R.string.home_menu_share)).perform(click())
                assertEquals(1, shares.size)
                val send = androidx.core.content.IntentCompat.getParcelableExtra(
                    shares.single(), Intent.EXTRA_INTENT, Intent::class.java
                )!!
                assertEquals(Intent.ACTION_SEND, send.action)
                assertTrue(send.getStringExtra(Intent.EXTRA_TEXT)!!.contains("https://example.invalid/update.apk"))
                scenario.onActivity { mainBinding(it).homeMenuButton.performClick() }
                await(scenario) { it.homeDrawer.isDrawerOpen(it.homeNavigation) }
                onView(withText(R.string.home_menu_message_admin)).perform(click())
                onView(withId(R.id.editTextMessage)).inRoot(isDialog()).check(matches(isDisplayed()))
                onView(withText(android.R.string.cancel)).inRoot(isDialog()).perform(activate())
                scenario.onActivity { activity ->
                    assertEquals(activity.getString(R.string.home_updates_available),
                        mainBinding(activity).homeUpdateButton.contentDescription)
                }
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun activate(): ViewAction = object : ViewAction {
        override fun getConstraints(): Matcher<View> = isDisplayed()
        override fun getDescription(): String = "Activate a visible menu item or button"
        override fun perform(uiController: UiController, view: View) {
            if (view.isClickable) {
                assertTrue(view.performClick())
            } else {
                var row = view
                while (row.parent !is AdapterView<*>) {
                    row = row.parent as? View ?: error("No menu adapter for this view")
                }
                val parent = row.parent as AdapterView<*>
                val position = parent.getPositionForView(row)
                assertTrue(parent.performItemClick(row, position, parent.getItemIdAtPosition(position)))
            }
            uiController.loopMainThreadUntilIdle()
        }
    }

    private fun awaitDialogText(text: Int) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (true) {
            try {
                onView(withText(text)).inRoot(isDialog()).check(matches(isDisplayed()))
                return
            } catch (e: Throwable) {
                if (SystemClock.uptimeMillis() > deadline) throw e
                SystemClock.sleep(50)
            }
        }
    }

    private fun mainBinding(activity: MainActivity) =
        ActivityMainBinding.bind(activity.findViewById(R.id.homeDrawer))

    private fun saveKnownUpdates(appUpdate: Boolean = false, timetableUpdate: Boolean = false) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        LocalVersionManager.saveRemoteVersions(context, ServerVersionInfo(
            DatabaseVersion(LocalVersionManager.getTimetableDbVersion(context) + if (timetableUpdate) 1 else 0, "fixture"),
            DatabaseVersion(LocalVersionManager.getCommunityDbVersion(context), "fixture"),
            if (appUpdate) AppVersion(BuildConfig.VERSION_CODE + 1, "https://example.invalid/update.apk") else null
        ))
    }

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
            saveKnownUpdates()
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
    fun recentServicesSwapAndDrawerWorkAcrossResumeAndRecreation() = isolatedHome {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
            replaceRecentViews(emptyList())
            saveKnownUpdates(timetableUpdate = true)
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
                    val binding = mainBinding(activity)
                    assertTrue(LocalVersionManager.autoDatabaseUpdatesEnabled(activity))
                    assertEquals(View.GONE, binding.homeUpdateButton.visibility)
                    binding.homeDrawer.openDrawer(binding.homeNavigation, false)
                    activity.onBackPressedDispatcher.onBackPressed()
                }
                await(scenario) { !it.homeDrawer.isDrawerVisible(it.homeNavigation) }
                scenario.onActivity { mainBinding(it).homeMenuButton.performClick() }
                await(scenario) { it.homeDrawer.isDrawerOpen(it.homeNavigation) }
                for (label in listOf(R.string.home_menu_check_db_updates, R.string.home_menu_settings,
                    R.string.home_menu_share, R.string.home_menu_message_admin)) {
                    onView(withText(label)).check(matches(isDisplayed()))
                }
                scenario.onActivity { activity ->
                    val menu = mainBinding(activity).homeNavigation.menu
                    for (i in 0 until menu.size()) assertNotNull(menu.getItem(i).icon)
                }
                onView(withText(R.string.home_menu_settings)).perform(click())
                onView(withId(R.id.autoDatabaseUpdates))
                    .check(matches(androidx.test.espresso.matcher.ViewMatchers.isChecked()))
                onView(withId(R.id.autoDatabaseUpdates)).perform(click())
                onView(withId(R.id.autoDatabaseUpdates))
                    .check(matches(org.hamcrest.Matchers.not(androidx.test.espresso.matcher.ViewMatchers.isChecked())))
                androidx.test.espresso.Espresso.pressBack()
                await(scenario) { it.homeUpdateButton.visibility == View.VISIBLE }
                assertFalse(LocalVersionManager.autoDatabaseUpdatesEnabled(context))
                scenario.onActivity { activity ->
                    val binding = mainBinding(activity)
                    assertEquals(View.VISIBLE, binding.homeUpdateButton.visibility)
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
                    mainBinding(activity).stopLocationButton.performClick()
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
                    val binding = mainBinding(activity)
                    assertFalse(LocalVersionManager.autoDatabaseUpdatesEnabled(activity))
                    assertEquals(View.VISIBLE, binding.homeUpdateButton.visibility)
                    assertEquals(activity.getString(R.string.location_to_action),
                        binding.stopLocationButton.contentDescription)
                }
                onView(withId(R.id.coordinatesInput)).inRoot(isDialog()).check(matches(withText("12.25, 77.5")))
                onView(withId(R.id.radiusInput)).inRoot(isDialog()).check(matches(withText("12.5")))
                onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click())
                scenario.moveToState(Lifecycle.State.CREATED)
                replaceRecentViews(emptyList())
                scenario.moveToState(Lifecycle.State.RESUMED)
                await(scenario) {
                    it.recentSearchesRecyclerView.adapter?.itemCount == 0
                }
            }
    }

    private fun await(scenario: ActivityScenario<MainActivity>, condition: (ActivityMainBinding) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        do {
            var matched = false
            scenario.onActivity { activity ->
                matched = condition(mainBinding(activity))
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
