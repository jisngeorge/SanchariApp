package com.sanchari.bus.ui.activity

import android.content.res.Configuration
import android.graphics.Rect
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.descendants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sanchari.bus.R
import com.sanchari.bus.databinding.ActivityMainBinding
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class MainPageLayoutTest {
    @Test
    fun cardsAndActionsFitNarrowScreensAndLargeTextInBothThemes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
                for (fontScale in listOf(1f, 1.3f, 2f)) {
                    for (widthDp in listOf(320, 411)) {
                        val config = Configuration(instrumentation.targetContext.resources.configuration).apply {
                            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
                            this.fontScale = fontScale
                        }
                        val context = ContextThemeWrapper(
                            instrumentation.targetContext.createConfigurationContext(config),
                            R.style.Theme_SanchariApp_NoActionBar
                        )
                        val binding = ActivityMainBinding.inflate(LayoutInflater.from(context))
                        val parent = FrameLayout(context).apply { addView(binding.root) }
                        val density = context.resources.displayMetrics.density
                        val width = (widthDp * density).roundToInt()
                        val height = (800 * density).roundToInt()
                        fun layout() {
                            parent.measure(
                                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
                            )
                            parent.layout(0, 0, width, height)
                        }
                        layout()
                        assertEquals(context.getString(R.string.app_name), binding.textViewTitle.text.toString())
                        assertEquals(context.getString(R.string.title_recent_services),
                            binding.recentSearchesTitle.text.toString())
                        assertEquals(context.getString(R.string.hint_search_bus_name),
                            binding.busNameInputLayout.hint.toString())
                        assertEquals(context.getString(R.string.btn_check_updates),
                            binding.buttonCheckForUpdates.text.toString())
                        assertEquals(context.getString(R.string.home_drafts), binding.buttonDrafts.text.toString())
                        val originalWords = setOf(
                            R.string.app_name, R.string.title_recent_services, R.string.btn_find_bus,
                            R.string.btn_add_new_bus, R.string.btn_check_updates, R.string.btn_share_app,
                            R.string.btn_message_admin_report_issue, R.string.home_drafts
                        ).map(context::getString).toSet()
                        for (label in binding.root.descendants.filterIsInstance<TextView>()) {
                            if (label.visibility == View.VISIBLE && !label.text.isNullOrEmpty()) {
                                assertTrue("Unexpected main-page wording: ${label.text}",
                                    label.text.toString() in originalWords)
                            }
                        }
                        val actions = listOf(
                            binding.fromWildcardButton, binding.fromClearButton,
                            binding.toWildcardButton, binding.toClearButton, binding.buttonSwapLocations,
                            binding.searchButton, binding.buttonAddNewBus, binding.buttonDrafts,
                            binding.buttonCheckForUpdates, binding.buttonShareApp, binding.buttonMessageAdmin
                        )
                        for (action in actions) {
                            assertTrue("Touch target too narrow: ${action.id}", action.width >= 48 * density)
                            assertTrue("Touch target too short: ${action.id}", action.height >= 48 * density)
                            assertFalse(action.contentDescription.isNullOrBlank() &&
                                (action !is TextView || action.text.isNullOrBlank()))
                        }
                        for (label in binding.root.descendants.filterIsInstance<TextView>()) {
                            if (label is EditText || label.visibility != View.VISIBLE || label.layout == null) continue
                            val text = label.layout
                            assertTrue("Text clipped vertically: ${label.text} at font scale $fontScale",
                                label.height >= text.height + label.compoundPaddingTop + label.compoundPaddingBottom)
                            for (line in 0 until text.lineCount) {
                                assertEquals("Label truncated: ${label.text}", 0, text.getEllipsisCount(line))
                            }
                        }
                        val route = Rect().also {
                            binding.routeSearchCard.getDrawingRect(it)
                            binding.root.offsetDescendantRectToMyCoords(binding.routeSearchCard, it)
                        }
                        val search = Rect().also {
                            binding.searchButton.getDrawingRect(it)
                            binding.root.offsetDescendantRectToMyCoords(binding.searchButton, it)
                        }
                        assertTrue(route.contains(search))
                        if (fontScale == 1f) assertTrue("Primary search should be above the fold", search.bottom < height)

                        binding.fromAutocomplete.setText("A long existing stop name", false)
                        binding.toAutocomplete.setText("Another long stop name", false)
                        binding.fromInputLayout.error = context.getString(R.string.stop_name_not_found)
                        binding.toInputLayout.error = context.getString(R.string.stop_name_not_found)
                        layout()
                        for ((field, wildcard, clear) in listOf(
                            Triple(binding.fromAutocomplete, binding.fromWildcardButton, binding.fromClearButton),
                            Triple(binding.toAutocomplete, binding.toWildcardButton, binding.toClearButton)
                        )) {
                            fun bounds(view: View): Rect = Rect().also {
                                view.getDrawingRect(it)
                                binding.root.offsetDescendantRectToMyCoords(view, it)
                            }
                            val input = bounds(field)
                            val wildcardBounds = bounds(wildcard)
                            val clearBounds = bounds(clear)
                            assertTrue("Wildcard must stay inside its input", input.contains(wildcardBounds))
                            assertTrue("Clear must stay inside its input", input.contains(clearBounds))
                            assertTrue("Wildcard and clear targets must not overlap",
                                wildcardBounds.right <= clearBounds.left)
                        }
                        val scroll = binding.homeScrollView
                        scroll.scrollTo(0, scroll.getChildAt(0).height)
                        assertTrue("All actions must remain reachable by scrolling",
                            scroll.scrollY + scroll.height >= scroll.getChildAt(0).height)
                    }
                }
            }
        }
    }
}
