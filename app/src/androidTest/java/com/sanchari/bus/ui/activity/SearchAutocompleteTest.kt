package com.sanchari.bus.ui.activity

import android.content.Intent
import android.content.ContentValues
import android.app.Instrumentation
import android.database.sqlite.SQLiteException
import android.os.SystemClock
import android.widget.Filterable
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.platform.app.InstrumentationRegistry
import com.sanchari.bus.R
import com.sanchari.bus.data.model.BusService
import com.sanchari.bus.data.model.RouteSearchResult
import com.sanchari.bus.data.local.DatabaseConstants.RecentViewTable
import com.sanchari.bus.data.local.UserDatabaseHelper
import com.sanchari.bus.data.manager.UserDataManager
import com.sanchari.bus.ui.adapter.BusSearchSuggestionAdapter
import com.sanchari.bus.databinding.ActivityMainBinding
import com.sanchari.bus.ui.helper.BusSearchHandler
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.hamcrest.Matchers.instanceOf
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class SearchAutocompleteTest {
    private lateinit var binding: ActivityMainBinding
    private lateinit var handler: BusSearchHandler

    @Test
    fun stopFieldsFilterIndependentlyStartingWithOneCharacter() {
        launch().use { scenario ->
            scenario.onActivity {
                assertNotSame(binding.fromAutocomplete.adapter, binding.toAutocomplete.adapter)
                assertEquals(1, binding.fromAutocomplete.threshold)
                assertEquals(1, binding.toAutocomplete.threshold)
                binding.fromAutocomplete.requestFocus()
                binding.fromAutocomplete.setText("P")
            }
            await(scenario) { binding.fromAutocomplete.adapter.count == 2 }
            scenario.onActivity {
                binding.toAutocomplete.requestFocus()
                binding.toAutocomplete.setText("K")
            }
            await(scenario) {
                binding.toAutocomplete.adapter.count == 1 && binding.toAutocomplete.isPopupShowing
            }
            scenario.onActivity {
                assertEquals(2, binding.fromAutocomplete.adapter.count)
                assertEquals("Kochi", binding.toAutocomplete.adapter.getItem(0))
                binding.fromAutocomplete.requestFocus()
            }
            await(scenario) {
                binding.fromAutocomplete.adapter.count == 2 && binding.fromAutocomplete.isPopupShowing
            }
        }
    }

    @Test
    fun loadingSuggestionsRefreshesAlreadyTypedInputAndUnchangedListsKeepAdapters() {
        launch().use { scenario ->
            scenario.onActivity {
                handler.setStopSuggestions(emptyList())
                binding.fromAutocomplete.requestFocus()
                binding.fromAutocomplete.setText("T")
            }
            await(scenario) { binding.fromAutocomplete.adapter.count == 0 }
            scenario.onActivity {
                handler.setStopSuggestions(listOf("Thiruvananthapuram", "Kochi"))
            }
            await(scenario) {
                binding.fromAutocomplete.adapter.count == 1 && binding.fromAutocomplete.isPopupShowing
            }
            scenario.onActivity {
                val from = binding.fromAutocomplete.adapter
                val to = binding.toAutocomplete.adapter
                handler.setStopSuggestions(listOf("Thiruvananthapuram", "Kochi"))
                assertSame(from, binding.fromAutocomplete.adapter)
                assertSame(to, binding.toAutocomplete.adapter)
                binding.fromWildcardButton.performClick()
                assertEquals("*", binding.fromAutocomplete.text.toString())
                assertFalse(binding.fromAutocomplete.isPopupShowing)
            }
            SystemClock.sleep(200)
            scenario.onActivity { assertFalse(binding.fromAutocomplete.isPopupShowing) }
        }
    }

    @Test
    fun firstBusQueryDisplaysDatabaseSubstringMatchesWithoutAnExtraKeystroke() {
        launch { listOf(service("one", "Superbus"), service("two", "Superbus")) }.use { scenario ->
            scenario.onActivity {
                binding.busNameAutocomplete.requestFocus()
                binding.busNameAutocomplete.setText("per")
            }
            await(scenario) {
                binding.busNameAutocomplete.adapter.count == 2 && binding.busNameAutocomplete.isPopupShowing
            }
            scenario.onActivity {
                val adapter = binding.busNameAutocomplete.adapter
                assertEquals("one", (adapter.getItem(0) as BusSearchHandler.BusSearchItem).service.serviceId)
                assertEquals("two", (adapter.getItem(1) as BusSearchHandler.BusSearchItem).service.serviceId)
                (adapter as Filterable).filter.filter("per")
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { assertEquals(2, binding.busNameAutocomplete.adapter.count) }
        }
    }

    @Test
    fun pendingPlatformFilterCannotHideFreshDatabaseResults() {
        val reportedCount = AtomicInteger(-1)
        launch().use { scenario ->
            scenario.onActivity {
                val adapter = binding.busNameAutocomplete.adapter as BusSearchSuggestionAdapter
                adapter.filter.filter("per") { reportedCount.set(it) }
                adapter.replaceResults(listOf(BusSearchHandler.BusSearchItem(service("one", "Superbus"))))
            }
            await(scenario) { reportedCount.get() >= 0 }
            scenario.onActivity {
                assertEquals(1, reportedCount.get())
                assertEquals(1, binding.busNameAutocomplete.adapter.count)
            }
        }
    }

    @Test
    fun selectingABusOpensDetailsAndClearsTheFieldWithoutAnotherQuery() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val previousRecents = UserDataManager.getRecentViews(context)
        val calls = AtomicInteger()
        val monitor = Instrumentation.ActivityMonitor(BusDetailsActivity::class.java.name, null, true)
        instrumentation.addMonitor(monitor)
        try {
            launch {
                calls.incrementAndGet()
                listOf(service("autocomplete-selection-fixture", "Superbus"))
            }.use { scenario ->
                scenario.onActivity {
                    binding.busNameAutocomplete.requestFocus()
                    binding.busNameAutocomplete.setText("per")
                }
                await(scenario) {
                    binding.busNameAutocomplete.isPopupShowing && binding.busNameAutocomplete.adapter.count == 1
                }
                onData(instanceOf(BusSearchHandler.BusSearchItem::class.java))
                    .inRoot(isPlatformPopup()).atPosition(0).perform(click())
                scenario.onActivity {
                    assertEquals("Completion must open the selected service", 1, monitor.hits)
                    assertEquals("", binding.busNameAutocomplete.text.toString())
                }
                await(scenario) {
                    binding.busNameAutocomplete.text.isEmpty() &&
                        UserDataManager.getRecentViews(context).any { it.serviceId == "autocomplete-selection-fixture" }
                }
                scenario.onActivity {
                    assertEquals(1, monitor.hits)
                    assertEquals(1, calls.get())
                    assertEquals(0, binding.busNameAutocomplete.adapter.count)
                    assertFalse(binding.busNameAutocomplete.isPopupShowing)
                }
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            UserDatabaseHelper(context).writableDatabase.use { db ->
                db.beginTransaction()
                try {
                    db.delete(RecentViewTable.TABLE_NAME, null, null)
                    previousRecents.forEach { recent ->
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

    @Test
    fun rapidTypingKeepsTheDebounceAndOnlyQueriesTheLatestText() {
        val calls = AtomicInteger()
        launch { query ->
            calls.incrementAndGet()
            listOf(service(query, query))
        }.use { scenario ->
            scenario.onActivity {
                binding.busNameAutocomplete.requestFocus()
                binding.busNameAutocomplete.setText("S")
                binding.busNameAutocomplete.setText("Su")
                binding.busNameAutocomplete.setText("Super")
            }
            await(scenario) { binding.busNameAutocomplete.adapter.count == 1 }
            scenario.onActivity {
                assertEquals(1, calls.get())
                assertEquals("Super", firstBusId())
            }
        }
    }

    @Test
    fun lateResultsCannotReplaceANewerQueryOrRepopulateAClearedField() {
        val slowStarted = AtomicInteger()
        launch { query ->
            if (query == "Old" || query == "Cleared") {
                slowStarted.incrementAndGet()
                withContext(NonCancellable) { delay(350) }
            }
            listOf(service(query, query))
        }.use { scenario ->
            scenario.onActivity {
                binding.busNameAutocomplete.requestFocus()
                binding.busNameAutocomplete.setText("Old")
            }
            await(scenario) { slowStarted.get() == 1 }
            scenario.onActivity { binding.busNameAutocomplete.setText("New") }
            await(scenario) { binding.busNameAutocomplete.adapter.count == 1 && firstBusId() == "New" }
            SystemClock.sleep(400)
            scenario.onActivity {
                assertEquals("New", firstBusId())
                binding.busNameAutocomplete.setText("Cleared")
                assertEquals(0, binding.busNameAutocomplete.adapter.count)
            }
            await(scenario) { slowStarted.get() == 2 }
            scenario.onActivity { binding.busNameAutocomplete.setText("", false) }
            SystemClock.sleep(400)
            scenario.onActivity {
                assertEquals(0, binding.busNameAutocomplete.adapter.count)
                assertFalse(binding.busNameAutocomplete.isPopupShowing)
            }
        }
    }

    @Test
    fun noMatchesAndFocusLossClearRowsAndTappingRestoresCurrentSuggestions() {
        launch { query -> if (query == "Match") listOf(service("match", "Match")) else emptyList() }
            .use { scenario ->
                scenario.onActivity {
                    binding.busNameAutocomplete.requestFocus()
                    binding.busNameAutocomplete.setText("Match")
                }
                await(scenario) { binding.busNameAutocomplete.isPopupShowing }
                scenario.onActivity {
                    binding.busNameAutocomplete.dismissDropDown()
                    binding.busNameAutocomplete.performClick()
                }
                await(scenario) { binding.busNameAutocomplete.isPopupShowing }
                scenario.onActivity { binding.fromAutocomplete.requestFocus() }
                await(scenario) {
                    binding.busNameAutocomplete.adapter.count == 0 && !binding.busNameAutocomplete.isPopupShowing
                }
                scenario.onActivity {
                    binding.busNameAutocomplete.requestFocus()
                    binding.busNameAutocomplete.setText("Missing")
                }
                SystemClock.sleep(250)
                scenario.onActivity {
                    assertEquals(0, binding.busNameAutocomplete.adapter.count)
                    assertFalse(binding.busNameAutocomplete.isPopupShowing)
                    assertNull(binding.busNameInputLayout.error)
                }
            }
    }

    @Test
    fun lookupFailureIsExplicitAndRetryClearsTheError() {
        launch { query ->
            if (query == "Fail") throw SQLiteException("Test lookup failure")
            listOf(service(query, query))
        }.use { scenario ->
            scenario.onActivity {
                binding.busNameAutocomplete.requestFocus()
                binding.busNameAutocomplete.setText("Fail")
            }
            await(scenario) { binding.busNameInputLayout.error != null }
            scenario.onActivity { activity ->
                assertEquals(activity.getString(R.string.bus_name_search_unavailable), binding.busNameInputLayout.error)
                assertEquals(0, binding.busNameAutocomplete.adapter.count)
                binding.busNameAutocomplete.setText("Retry")
                assertNull(binding.busNameInputLayout.error)
            }
            await(scenario) { binding.busNameAutocomplete.adapter.count == 1 && firstBusId() == "Retry" }
        }
    }

    @Test
    fun pausingCancelsSuggestionsAndResumingRefreshesTheFocusedQuery() {
        val started = AtomicInteger()
        launch { query ->
            started.incrementAndGet()
            withContext(NonCancellable) { delay(200) }
            listOf(service(query, query))
        }.use { scenario ->
            scenario.onActivity {
                binding.busNameAutocomplete.requestFocus()
                binding.busNameAutocomplete.setText("Match")
            }
            await(scenario) { started.get() == 1 }
            scenario.onActivity { handler.pauseSuggestions() }
            SystemClock.sleep(250)
            scenario.onActivity {
                assertEquals(0, binding.busNameAutocomplete.adapter.count)
                assertFalse(binding.busNameAutocomplete.isPopupShowing)
                handler.resumeSuggestions()
            }
            await(scenario) { binding.busNameAutocomplete.adapter.count == 1 && firstBusId() == "Match" }
        }
    }

    private fun firstBusId(): String =
        (binding.busNameAutocomplete.adapter.getItem(0) as BusSearchHandler.BusSearchItem).service.serviceId

    private fun launch(
        search: suspend (String) -> List<BusService> = { emptyList() }
    ): ActivityScenario<SearchResultsActivity> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val result = RouteSearchResult(service("fixture", "Fixture"), "Palai", "Kochi", 1, 2)
        val intent = Intent(context, SearchResultsActivity::class.java).apply {
            putParcelableArrayListExtra(SearchResultsActivity.EXTRA_ROUTE_RESULTS, arrayListOf(result))
            putExtra(SearchResultsActivity.EXTRA_FROM_STOP, "Palai")
            putExtra(SearchResultsActivity.EXTRA_TO_STOP, "Kochi")
        }
        return ActivityScenario.launch<SearchResultsActivity>(intent).also { scenario ->
            scenario.onActivity { activity ->
                binding = ActivityMainBinding.inflate(activity.layoutInflater)
                activity.setContentView(binding.root)
                handler = BusSearchHandler(activity, binding, search)
                handler.setup()
                handler.setStopSuggestions(listOf("Palai", "Punalur", "Kochi"))
            }
        }
    }

    private fun await(scenario: ActivityScenario<SearchResultsActivity>, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        do {
            var matched = false
            scenario.onActivity { matched = condition() }
            if (matched) return
            SystemClock.sleep(25)
        } while (SystemClock.uptimeMillis() < deadline)
        fail("Autocomplete did not reach the expected state")
    }

    private fun service(id: String, name: String) =
        BusService(id, name, "ORDINARY", true, 0L, "08:00", "09:00")
}
