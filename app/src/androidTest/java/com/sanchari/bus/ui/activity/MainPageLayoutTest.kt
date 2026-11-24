package com.sanchari.bus.ui.activity

import android.content.res.Configuration
import android.graphics.Rect
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.core.view.descendants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sanchari.bus.R
import com.sanchari.bus.databinding.ActivityMainBinding
import com.sanchari.bus.data.model.RecentSearch
import com.sanchari.bus.ui.adapter.RecentSearchAdapter
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
                    for (widthDp in listOf(320, 360, 411)) {
                        val config = Configuration(instrumentation.targetContext.resources.configuration).apply {
                            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
                            this.fontScale = fontScale
                        }
                        val context = ContextThemeWrapper(
                            instrumentation.targetContext.createConfigurationContext(config),
                            R.style.Theme_SanchariApp_NoActionBar
                        )
                        val binding = ActivityMainBinding.inflate(LayoutInflater.from(context))
                        binding.homeUpdateButton.visibility = View.VISIBLE
                        binding.recentSearchesRecyclerView.layoutManager =
                            LinearLayoutManager(context)
                        binding.recentSearchesRecyclerView.adapter = RecentSearchAdapter(
                            (1..5).map { RecentSearch("service-$it",
                                "A long service name for a bus passing through several places $it", it.toLong()) }) {}
                        val parent = FrameLayout(context).apply { addView(binding.root) }
                        val density = context.resources.displayMetrics.density
                        val width = (widthDp * density).roundToInt()
                        val height = (728 * density).roundToInt()
                        fun layout() {
                            parent.measure(
                                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
                            )
                            parent.layout(0, 0, width, height)
                        }
                        layout()
                        layout()
                        val title = binding.textViewTitle
                        val color = { id: Int -> androidx.core.content.ContextCompat.getColor(context, id) }
                        assertEquals(color(R.color.brand_header),
                            (binding.homeHeader.background as android.graphics.drawable.ColorDrawable).color)
                        assertEquals(color(R.color.brand_on_primary), title.currentTextColor)
                        assertEquals(color(R.color.brand_on_primary), binding.homeMenuButton.imageTintList?.defaultColor)
                        val drawerHeader = binding.homeNavigation.getHeaderView(0) as ViewGroup
                        assertEquals(color(R.color.white),
                            (drawerHeader.background as android.graphics.drawable.ColorDrawable).color)
                        assertEquals(context.getString(R.string.app_name),
                            drawerHeader.descendants.filterIsInstance<TextView>().single().text.toString())
                        assertNotNull(drawerHeader.descendants.filterIsInstance<android.widget.ImageView>().single().drawable)
                        val titleCenter = (title.left + title.right) / 2
                        assertEquals((title.parent as View).width / 2, titleCenter)
                        binding.homeUpdateButton.visibility = View.GONE
                        layout()
                        assertEquals(titleCenter, (title.left + title.right) / 2)
                        binding.homeUpdateButton.visibility = View.VISIBLE
                        layout()
                        assertEquals(context.getString(R.string.app_name), binding.textViewTitle.text.toString())
                        assertEquals(context.getString(R.string.title_recent_services),
                            binding.recentSearchesTitle.text.toString())
                        assertEquals(context.getString(R.string.hint_search_bus_name),
                            binding.busNameInputLayout.hint.toString())
                        assertEquals(context.getString(R.string.home_drafts), binding.buttonDrafts.text.toString())
                        assertEquals("Add Bus", binding.buttonAddNewBus.text.toString())
                        assertEquals("Drafts", binding.buttonDrafts.text.toString())
                        val green = androidx.core.content.ContextCompat.getColor(context, R.color.action_green)
                        assertEquals(green, binding.buttonAddNewBus.iconTint
                            ?.getColorForState(binding.buttonAddNewBus.drawableState, 0))
                        binding.buttonAddNewBus.isEnabled = false
                        assertNotEquals(green, binding.buttonAddNewBus.iconTint
                            ?.getColorForState(binding.buttonAddNewBus.drawableState, 0))
                        binding.buttonAddNewBus.isEnabled = true
                        if (fontScale == 1f) {
                            assertEquals(1, binding.buttonAddNewBus.layout.lineCount)
                            assertEquals(1, binding.buttonDrafts.layout.lineCount)
                        }
                        val originalWords = setOf(
                            R.string.app_name, R.string.title_recent_services, R.string.btn_find_bus,
                            R.string.btn_add_new_bus, R.string.home_drafts
                        ).map(context::getString).toSet()
                        for (label in binding.homeScrollView.descendants.filterIsInstance<TextView>()) {
                            if (label.visibility == View.VISIBLE && !label.text.isNullOrEmpty() &&
                                label.id != R.id.serviceName) {
                                assertTrue("Unexpected main-page wording: ${label.text}",
                                    label.text.toString() in originalWords)
                            }
                        }
                        val actions = listOf(
                            binding.stopLocationButton, binding.stopWildcardButton, binding.buttonSwapLocations,
                            binding.searchButton, binding.buttonAddNewBus, binding.buttonDrafts,
                            binding.homeMenuButton, binding.homeUpdateButton
                        )
                        for (action in actions) {
                            assertTrue("Touch target too narrow: ${action.id}", action.width >= 48 * density)
                            assertTrue("Touch target too short: ${action.id}", action.height >= 48 * density)
                            assertFalse(action.contentDescription.isNullOrBlank() &&
                                (action !is TextView || action.text.isNullOrBlank()))
                        }
                        for (label in binding.homeScrollView.descendants.filterIsInstance<TextView>()) {
                            if (label is EditText || label.visibility != View.VISIBLE || label.layout == null) continue
                            val text = label.layout
                            assertTrue("Text clipped vertically: ${label.text} at font scale $fontScale",
                                label.height >= text.height + label.compoundPaddingTop + label.compoundPaddingBottom)
                            for (line in 0 until text.lineCount) {
                                if (label.id != R.id.serviceName)
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
                        if (fontScale == 1f && widthDp >= 360) {
                            assertTrue("Important actions must stay above the fold",
                                binding.homePrimaryActions.bottom <= height)
                            assertEquals(5, binding.recentSearchesRecyclerView.adapter!!.itemCount)
                        }
                        assertFalse(binding.recentSearchesRecyclerView.isNestedScrollingEnabled)
                        for (position in 0 until 5) {
                            val row = requireNotNull(
                                binding.recentSearchesRecyclerView.findViewHolderForAdapterPosition(position)
                            ).itemView as ViewGroup
                            assertEquals(binding.recentSearchesRecyclerView.width, row.width)
                            assertTrue(row.height >= 48 * density)
                            assertEquals(1, row.descendants.filterIsInstance<TextView>().count())
                            assertEquals("A long service name for a bus passing through several places ${position + 1}",
                                row.contentDescription.toString())
                        }

                        binding.fromAutocomplete.setText("A long existing stop name", false)
                        binding.toAutocomplete.setText("Another long stop name", false)
                        binding.fromInputLayout.error = context.getString(R.string.stop_name_not_found)
                        binding.toInputLayout.error = context.getString(R.string.stop_name_not_found)
                        layout()
                        for ((field, buttons) in listOf(
                            binding.fromAutocomplete to listOf(binding.stopLocationButton,
                                binding.stopWildcardButton)
                        )) {
                            fun bounds(view: View): Rect = Rect().also {
                                view.getDrawingRect(it)
                                binding.root.offsetDescendantRectToMyCoords(view, it)
                            }
                            val input = bounds(field)
                            val targets = buttons.map(::bounds)
                            val destination = bounds(binding.toAutocomplete)
                            for (target in targets) {
                                assertTrue("Controls must sit below the full-width input", target.top >= input.bottom)
                                assertTrue("Shared controls must sit above To", target.bottom <= destination.top)
                                assertTrue(target.left >= input.left && target.right <= input.right)
                            }
                            for ((left, right) in targets.zipWithNext()) {
                                assertTrue("Stop controls must not overlap", left.right <= right.left)
                            }
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

    @Test
    fun drawerUsesShortLabelsForHomeActions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext,
                R.style.Theme_SanchariApp_NoActionBar)
            val navigation = ActivityMainBinding.inflate(LayoutInflater.from(context)).homeNavigation
            val menu = navigation.menu
            val expected = linkedMapOf(
                R.id.action_check_db_updates to "Check DB updates",
                R.id.action_home_settings to "Settings",
                R.id.action_share_app to "Share",
                R.id.action_message_admin to "Message admin"
            )
            assertEquals(expected.size, menu.size())
            expected.entries.forEachIndexed { index, (id, label) ->
                assertEquals(id, menu.getItem(index).itemId)
                assertEquals(label, menu.getItem(index).title.toString())
                assertNotNull("Every drawer item needs an icon", menu.getItem(index).icon)
            }
            val background = navigation.background
            val fill = (background as? com.google.android.material.shape.MaterialShapeDrawable)?.fillColor?.defaultColor
                ?: (background as android.graphics.drawable.ColorDrawable).color
            assertEquals(androidx.core.content.ContextCompat.getColor(context, R.color.brand_header), fill)
        }
    }
}
