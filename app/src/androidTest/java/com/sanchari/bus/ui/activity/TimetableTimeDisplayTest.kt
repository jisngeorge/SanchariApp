package com.sanchari.bus.ui.activity

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.timepicker.MaterialTimePicker
import com.sanchari.bus.R
import com.sanchari.bus.data.model.BusService
import com.sanchari.bus.data.model.BusStop
import com.sanchari.bus.data.model.RouteSearchResult
import com.sanchari.bus.ui.adapter.BusServiceAdapter
import com.sanchari.bus.ui.adapter.BusStopAdapter
import com.sanchari.bus.ui.adapter.StopEditAdapter
import com.sanchari.bus.ui.helper.DraftManager
import com.sanchari.bus.data.local.DatabaseManager
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimetableTimeDisplayTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var initialDraftFiles = emptySet<String>()

    @Before
    fun initializeLocalData() {
        runBlocking { DatabaseManager.initializeDatabases(instrumentation.targetContext) }
        initialDraftFiles = DraftManager.getDrafts(instrumentation.targetContext).map { it.fileName }.toSet()
    }

    @After
    fun removeTestAutosaves() {
        val context = instrumentation.targetContext
        runBlocking { DraftManager.deleteDraftAsync(context, DraftManager.newDraftFileName()).await() }
        DraftManager.getDrafts(context).filter { it.fileName !in initialDraftFiles }
            .forEach { DraftManager.deleteDraft(context, it.fileName) }
    }

    @Test
    fun serviceAndHaltRowsDisplayAmPmWithoutChangingSourceTimes() {
        launchEditor().use { scenario ->
            onView(withText("Got it")).perform(click())
            scenario.onActivity { activity ->
                val parent = activity.findViewById<ViewGroup>(android.R.id.content)
                val service = BusService("test", "Test", "ORDINARY", true, 0L, "00:15", "14:30")
                val services = BusServiceAdapter {}
                val serviceRow = services.onCreateViewHolder(parent, 0)
                serviceRow.bind(RouteSearchResult(service, "Start", "End", 1, 2))
                assertEquals("12:15 AM", serviceRow.itemView.findViewById<TextView>(R.id.fromTime).text.toString())
                assertEquals("2:30 PM", serviceRow.itemView.findViewById<TextView>(R.id.toTime).text.toString())
                assertEquals("00:15", service.fromTime)
                assertEquals("14:30", service.toTime)

                val stops = listOf(
                    stop("Halt", "11:55", 1),
                    stop("Halt", "12:05", 2),
                    stop("Unknown", "--:--", 3)
                )
                val adapter = BusStopAdapter(stops)
                val row = adapter.onCreateViewHolder(parent, 0)
                adapter.onBindViewHolder(row, 0)
                assertEquals(
                    "11:55 AM - 12:05 PM",
                    row.itemView.findViewById<TextView>(R.id.textViewScheduledTime).text.toString()
                )
                adapter.onBindViewHolder(row, 1)
                assertEquals("--:--", row.itemView.findViewById<TextView>(R.id.textViewScheduledTime).text.toString())
                assertEquals(listOf("11:55", "12:05", "--:--"), stops.map { it.scheduledTime })
            }
        }
    }

    @Test
    fun editorUses12HourPickerButNewAndEditedPayloadsKeep24HourTimes() {
        var capturedIntent: Intent? = null
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.component?.className == ConfirmationActivity::class.java.name) {
                    capturedIntent = intent
                    return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                }
                return null
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            launchEditor().use { scenario ->
                onView(withText("Got it")).perform(click())
                scenario.onActivity { activity ->
                    val recycler = activity.findViewById<RecyclerView>(R.id.recyclerViewStopsEditor)
                    val adapter = recycler.adapter as StopEditAdapter
                    val stops = adapter.getStopsData()
                    stops.first().stopName = "Start"
                    adapter.updateTime(0, "14:30")
                    val parent = activity.findViewById<ViewGroup>(android.R.id.content)
                    val row = adapter.onCreateViewHolder(parent, 0)
                    adapter.onBindViewHolder(row, 0)
                    assertEquals("2:30 PM", row.binding.editTextScheduledTime.text.toString())
                    assertEquals("14:30", stops.first().scheduledTime)
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val recycler = activity.findViewById<RecyclerView>(R.id.recyclerViewStopsEditor)
                    val row = requireNotNull(recycler.findViewHolderForAdapterPosition(0))
                    row.itemView.findViewById<TextView>(R.id.editTextScheduledTime).performClick()
                    activity.supportFragmentManager.executePendingTransactions()
                    val picker = activity.supportFragmentManager.findFragmentByTag("TimePicker") as MaterialTimePicker
                    assertEquals(
                        android.view.View.VISIBLE,
                        picker.requireView().findViewById<android.view.View>(
                            com.google.android.material.R.id.material_clock_period_toggle
                        ).visibility
                    )
                    assertEquals(14, picker.hour)
                    assertEquals(30, picker.minute)
                    picker.dismiss()
                }
                scenario.onActivity { activity ->
                    activity.findViewById<TextView>(R.id.editTextServiceName).text = "Test service"
                    activity.findViewById<android.view.View>(R.id.buttonAddStop).performClick()
                    val adapter = activity.findViewById<RecyclerView>(R.id.recyclerViewStopsEditor).adapter as StopEditAdapter
                    adapter.getStopsData().last().stopName = "End"
                    adapter.updateTime(1, "16:05")
                    activity.findViewById<android.view.View>(R.id.buttonApplyChanges).performClick()
                }
                assertPayload(waitForIntent { capturedIntent }, "new_schedule", "14:30", "16:05")
                capturedIntent = null

                scenario.onActivity { activity ->
                    activity.setOriginalService(
                        BusService("existing-test", "Test service", "ORDINARY", true, 0L, "14:30", "16:05")
                    )
                    activity.findViewById<android.view.View>(R.id.buttonApplyChanges).performClick()
                }
                assertPayload(waitForIntent { capturedIntent }, "edit_schedule", "14:30", "16:05")
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun waitForIntent(intent: () -> Intent?): Intent {
        val deadline = System.currentTimeMillis() + 5_000
        while (intent() == null && System.currentTimeMillis() < deadline) Thread.sleep(25)
        return requireNotNull(intent())
    }

    private fun assertPayload(intent: Intent, type: String, from: String, to: String) {
        val payload = intent.getStringExtra("EXTRA_JSON_PAYLOAD")
        assertNotNull(payload)
        val root = JSONObject(requireNotNull(payload))
        assertEquals(type, root.getString("type"))
        assertEquals(from, root.getJSONObject("service").getString("fromTime"))
        assertEquals(to, root.getJSONObject("service").getString("toTime"))
        val stops = root.getJSONArray("stops")
        assertEquals(from, stops.getJSONObject(0).getString("scheduledTime"))
        assertEquals(to, stops.getJSONObject(1).getString("scheduledTime"))
    }

    private fun launchEditor(): ActivityScenario<SuggestEditActivity> =
        ActivityScenario.launch(SuggestEditActivity.newIntentForNew(instrumentation.targetContext))

    private fun stop(name: String, time: String, order: Int) =
        BusStop(order, "test", name, 0.0, 0.0, time, order)
}
