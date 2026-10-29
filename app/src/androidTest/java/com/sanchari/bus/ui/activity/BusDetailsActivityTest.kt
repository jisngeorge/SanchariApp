package com.sanchari.bus.ui.activity

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sanchari.bus.R
import com.sanchari.bus.data.local.DatabaseManager
import com.sanchari.bus.data.model.BusService
import com.sanchari.bus.databinding.ActivityBusDetailsBinding
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BusDetailsActivityTest {
    @Test
    fun emptyDataRetainsContributionActionsAndStatusCancellation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        runBlocking { DatabaseManager.initializeDatabases(context) }
        val service = BusService("details-ui-fixture", "Fixture bus", "ORDINARY", true, 1_700_000_000L, "11:55", "12:05")
        var editorIntent: Intent? = null
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.component?.className == SuggestEditActivity::class.java.name) {
                    editorIntent = intent
                    return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                }
                return null
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            ActivityScenario.launch<BusDetailsActivity>(BusDetailsActivity.newIntent(context, service)).use { scenario ->
                val deadline = SystemClock.uptimeMillis() + 5_000
                var loaded = false
                do {
                    scenario.onActivity { activity ->
                        val binding = binding(activity)
                        loaded = binding.progressBar.visibility == View.GONE &&
                            binding.textViewError.visibility == View.VISIBLE &&
                            binding.textViewNoComments.visibility == View.VISIBLE
                    }
                    if (loaded) break
                    SystemClock.sleep(25)
                } while (SystemClock.uptimeMillis() < deadline)
                assertTrue("Empty-state data should finish loading", loaded)
                scenario.onActivity { activity ->
                    val binding = binding(activity)
                    assertEquals(service.name, binding.toolbar.title.toString())
                    assertEquals(service.type, binding.toolbar.subtitle.toString())
                    assertEquals(R.id.buttonStatusYes, binding.toggleGroupStatus.checkedButtonId)
                    assertEquals(View.GONE, binding.ratingsCard.visibility)
                    assertEquals(View.GONE, binding.recyclerViewBusStops.visibility)
                    assertEquals(View.GONE, binding.commentsRecyclerView.visibility)
                    assertEquals(View.VISIBLE, binding.textViewLastReported.visibility)
                    assertEquals(View.VISIBLE, binding.buttonAddRating.visibility)
                    assertEquals(View.VISIBLE, binding.buttonAddComment.visibility)
                    assertEquals(activity.getString(R.string.msg_no_stops_found), binding.textViewError.text.toString())
                    binding.buttonAddRating.performClick()
                }
                onView(withId(R.id.ratingBarPunctualitySubmit)).check(matches(isDisplayed()))
                onView(withText("Cancel")).perform(click())
                scenario.onActivity { binding(it).buttonAddComment.performClick() }
                onView(withId(R.id.editTextComment)).check(matches(isDisplayed()))
                onView(withText("Cancel")).perform(click())
                onView(withId(R.id.buttonStatusNo)).perform(click())
                onView(withText("Confirm Report")).check(matches(isDisplayed()))
                onView(withText("Cancel")).perform(click())
                scenario.onActivity {
                    val group = binding(it).toggleGroupStatus
                    assertEquals("After cancellation: ${group.checkedButtonIds}, " +
                        "yes=${binding(it).buttonStatusYes.isChecked}, no=${binding(it).buttonStatusNo.isChecked}",
                        R.id.buttonStatusYes, group.checkedButtonId)
                    binding(it).buttonSuggestEdit.performClick()
                }
                @Suppress("DEPRECATION")
                val forwarded = editorIntent?.getParcelableExtra<BusService>("EXTRA_BUS_SERVICE")
                assertEquals(service, forwarded)
                scenario.recreate()
                scenario.onActivity {
                    assertEquals(service.name, binding(it).toolbar.title.toString())
                    assertEquals(R.id.buttonStatusYes, binding(it).toggleGroupStatus.checkedButtonId)
                }
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun binding(activity: BusDetailsActivity): ActivityBusDetailsBinding =
        ActivityBusDetailsBinding.bind(activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0))
}
