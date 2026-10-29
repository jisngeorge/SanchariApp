package com.sanchari.bus.ui.activity

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sanchari.bus.R
import com.sanchari.bus.data.local.DatabaseManager
import com.sanchari.bus.data.manager.SearchManager
import com.sanchari.bus.data.model.BusService
import com.sanchari.bus.data.model.RouteSearchResult
import com.sanchari.bus.databinding.ActivityMainBinding
import com.sanchari.bus.ui.helper.BusSearchHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StopInputValidationTest {
    private lateinit var binding: ActivityMainBinding
    private lateinit var handler: BusSearchHandler

    @Test
    fun incompleteNamesAreNotFlaggedWhileTypingButUnknownNamesAreFlaggedOnBlur() {
        launch().use { scenario ->
            scenario.onActivity { activity ->
                binding.fromAutocomplete.requestFocus()
                binding.fromAutocomplete.setText("Pal", false)
                assertNull(binding.fromInputLayout.error)
                binding.toAutocomplete.requestFocus()
                assertEquals(activity.getString(R.string.stop_name_not_found), binding.fromInputLayout.error)

                binding.fromAutocomplete.requestFocus()
                binding.fromAutocomplete.setText("Palai", false)
                assertNull(binding.fromInputLayout.error)
                binding.toAutocomplete.requestFocus()
                assertNull(binding.fromInputLayout.error)
                binding.toAutocomplete.setText("Not a stop", false)
                assertNull(binding.toInputLayout.error)
                binding.fromAutocomplete.requestFocus()
                assertEquals(activity.getString(R.string.stop_name_not_found), binding.toInputLayout.error)
            }
        }
    }

    @Test
    fun completeNamesAcceptCaseAndWhitespaceAndResolveToStoredSpelling() {
        launch().use { scenario ->
            scenario.onActivity {
                binding.fromAutocomplete.setText("  pAlAi  ", false)
                binding.toAutocomplete.setText("koCHI", false)
                assertEquals("Palai" to "Kochi", handler.validateRouteStops())
                assertNull(binding.fromInputLayout.error)
                assertNull(binding.toInputLayout.error)

                handler.setStopSuggestions(listOf("Palai", "PALAI", "Kochi"))
                binding.fromAutocomplete.setText("Palai", false)
                assertEquals("Palai" to "Kochi", handler.validateRouteStops())
                binding.fromAutocomplete.setText("PALAI", false)
                assertEquals("PALAI" to "Kochi", handler.validateRouteStops())
            }
        }
    }

    @Test
    fun routeValidationRequiresFullNamesAndRejectsBothInvalidFields() {
        launch().use { scenario ->
            scenario.onActivity { activity ->
                binding.fromAutocomplete.setText("Pal", false)
                binding.toAutocomplete.setText("Missing", false)
                assertNull(handler.validateRouteStops())
                val error = activity.getString(R.string.stop_name_not_found)
                assertEquals(error, binding.fromInputLayout.error)
                assertEquals(error, binding.toInputLayout.error)
                assertFalse(activity.isFinishing)
            }
        }
    }

    @Test
    fun wildcardAndClearButtonsRemoveErrorsAndBothWildcardsRemainInvalid() {
        launch().use { scenario ->
            scenario.onActivity { activity ->
                binding.fromAutocomplete.setText("Unknown", false)
                binding.toAutocomplete.setText("Kochi", false)
                assertNull(handler.validateRouteStops())
                binding.fromWildcardButton.performClick()
                assertNull(binding.fromInputLayout.error)
                assertEquals("*" to "Kochi", handler.validateRouteStops())
                binding.toWildcardButton.performClick()
                assertNull(handler.validateRouteStops())
                val error = activity.getString(R.string.wildcard_both_invalid)
                assertEquals(error, binding.fromInputLayout.error)
                assertEquals(error, binding.toInputLayout.error)
                binding.fromAutocomplete.setText("palai", false)
                assertNull(binding.fromInputLayout.error)
                assertNull(binding.toInputLayout.error)
                assertEquals("Palai" to "*", handler.validateRouteStops())
                binding.fromClearButton.performClick()
                assertNull(binding.fromInputLayout.error)
                assertNull(handler.validateRouteStops())
                assertEquals(activity.getString(R.string.stop_name_required), binding.fromInputLayout.error)
            }
        }
    }

    @Test
    fun blanksRequireInputOnlyOnSubmissionAndEmbeddedStarsAreLiteralNames() {
        launch().use { scenario ->
            scenario.onActivity { activity ->
                binding.fromAutocomplete.requestFocus()
                binding.toAutocomplete.requestFocus()
                assertNull(binding.fromInputLayout.error)
                binding.searchButton.performClick()
                assertEquals(activity.getString(R.string.stop_name_required), binding.fromInputLayout.error)
                assertEquals(activity.getString(R.string.stop_name_required), binding.toInputLayout.error)
                handler.setStopSuggestions(listOf("A*", "Kochi"))
                binding.fromAutocomplete.setText("a*", false)
                binding.toAutocomplete.setText("kochi", false)
                assertEquals("A*" to "Kochi", handler.validateRouteStops())
            }
        }
    }

    @Test
    fun refreshedSuggestionsRevalidateNamesWithoutFlaggingUnloadedData() {
        launch().use { scenario ->
            scenario.onActivity { activity ->
                handler.setStopSuggestions(emptyList())
                binding.fromAutocomplete.setText("New stop", false)
                binding.toAutocomplete.setText("Kochi", false)
                assertNull(handler.validateRouteStops())
                assertNull(binding.fromInputLayout.error)
                assertNull(binding.toInputLayout.error)
                binding.busNameAutocomplete.requestFocus()
                handler.setStopSuggestions(listOf("Palai", "Kochi"))
                assertEquals(activity.getString(R.string.stop_name_not_found), binding.fromInputLayout.error)
                handler.setStopSuggestions(listOf("New stop", "Kochi"))
                assertNull(binding.fromInputLayout.error)
                assertEquals("New stop" to "Kochi", handler.validateRouteStops())
            }
        }
    }

    @Test
    fun findBusShowsUnknownStopErrorWithoutOpeningResults() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        kotlinx.coroutines.runBlocking {
            DatabaseManager.initializeDatabases(instrumentation.targetContext)
        }
        val knownStop = SearchManager.getStopSuggestions(instrumentation.targetContext).first()
        val monitor = android.app.Instrumentation.ActivityMonitor(
            SearchResultsActivity::class.java.name, null, true
        )
        try {
            launch().use { scenario ->
                instrumentation.addMonitor(monitor)
                scenario.onActivity {
                    binding.fromAutocomplete.setText("Definitely not a timetable stop 472", false)
                    binding.toAutocomplete.setText(knownStop, false)
                    binding.searchButton.performClick()
                }
                val deadline = android.os.SystemClock.uptimeMillis() + 5_000
                var validated = false
                do {
                    scenario.onActivity { activity ->
                        validated = binding.searchButton.isEnabled &&
                            binding.fromInputLayout.error == activity.getString(R.string.stop_name_not_found)
                    }
                    if (validated) break
                    android.os.SystemClock.sleep(25)
                } while (android.os.SystemClock.uptimeMillis() < deadline)
                assertTrue("Find Bus must reject an unknown stop inline", validated)
                assertEquals(0, monitor.hits)
                scenario.onActivity {
                    assertNull(binding.toInputLayout.error)
                }
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    fun errorMessagesDoNotPushStopButtonsBelowTheirInputFields() {
        launch().use { scenario ->
            scenario.onActivity {
                binding.fromAutocomplete.setText("Unknown", false)
                binding.toAutocomplete.setText("Unknown", false)
                handler.validateRouteStops()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                for ((field, button) in listOf(
                    binding.fromAutocomplete to binding.fromWildcardButton,
                    binding.toAutocomplete to binding.toWildcardButton
                )) {
                    val fieldPosition = IntArray(2)
                    val buttonPosition = IntArray(2)
                    field.getLocationOnScreen(fieldPosition)
                    button.getLocationOnScreen(buttonPosition)
                    assertTrue(buttonPosition[1] >= fieldPosition[1])
                    assertTrue(buttonPosition[1] + button.height <= fieldPosition[1] + field.height)
                }
            }
        }
    }

    private fun launch(): ActivityScenario<SearchResultsActivity> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val result = RouteSearchResult(
            BusService("fixture", "Fixture", "ORDINARY", true, 0L, "08:00", "09:00"),
            "Palai", "Kochi", 1, 2
        )
        val intent = Intent(context, SearchResultsActivity::class.java).apply {
            putParcelableArrayListExtra(SearchResultsActivity.EXTRA_ROUTE_RESULTS, arrayListOf(result))
            putExtra(SearchResultsActivity.EXTRA_FROM_STOP, "Palai")
            putExtra(SearchResultsActivity.EXTRA_TO_STOP, "Kochi")
        }
        return ActivityScenario.launch<SearchResultsActivity>(intent).also { scenario ->
            scenario.onActivity { activity ->
                binding = ActivityMainBinding.inflate(activity.layoutInflater)
                activity.setContentView(binding.root)
                handler = BusSearchHandler(activity, binding)
                handler.setup()
                handler.setStopSuggestions(listOf("Palai", "Kochi"))
            }
        }
    }
}
