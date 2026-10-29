package com.sanchari.bus.ui.activity

import android.content.res.Configuration
import android.graphics.Rect
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.descendants
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sanchari.bus.R
import com.sanchari.bus.data.model.BusStop
import com.sanchari.bus.data.model.UserComment
import com.sanchari.bus.databinding.ActivityBusDetailsBinding
import com.sanchari.bus.ui.adapter.BusStopAdapter
import com.sanchari.bus.ui.adapter.CommentAdapter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class BusDetailsLayoutTest {
    @Test
    fun stopRangesRatingsAndCommentsFitNarrowScreensWithLargeTextInBothThemes() {
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
                        val binding = ActivityBusDetailsBinding.inflate(LayoutInflater.from(context))
                        val stops = listOf(
                            BusStop(1, "fixture", "A long stop name for a scheduled halt", 0.0, 0.0, "11:55", 1),
                            BusStop(2, "fixture", "A long stop name for a scheduled halt", 0.0, 0.0, "12:05", 2),
                            BusStop(3, "fixture", "Another stop", 0.0, 0.0, "--:--", 3)
                        )
                        binding.recyclerViewBusStops.layoutManager = LinearLayoutManager(context)
                        binding.recyclerViewBusStops.adapter = BusStopAdapter(stops)
                        binding.commentsRecyclerView.layoutManager = LinearLayoutManager(context)
                        binding.commentsRecyclerView.adapter = CommentAdapter(listOf(UserComment(
                            1, "fixture", "A long community contributor display name",
                            "A community comment that wraps over several lines without overlapping its date or author.", 0L
                        )))
                        binding.textViewLastReported.visibility = View.VISIBLE
                        binding.textViewLastReported.text = context.getString(R.string.label_last_reported, "29-Oct-2026")
                        binding.textViewRatingCount.text = context.getString(R.string.label_based_on_ratings, 12)
                        binding.toggleGroupStatus.check(R.id.buttonStatusYes)
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
                        for (action in listOf(binding.buttonSuggestEdit, binding.buttonAddRating,
                            binding.buttonAddComment, binding.buttonStatusYes, binding.buttonStatusNo)) {
                            assertTrue("Action needs a 48dp target", action.width >= 48 * density)
                            assertTrue("Action needs a 48dp target", action.height >= 48 * density)
                        }
                        for (label in binding.root.descendants.filterIsInstance<TextView>()) {
                            if (label.visibility != View.VISIBLE || label.layout == null) continue
                            val text = label.layout
                            assertTrue("Text clipped at font scale $fontScale: ${label.text}",
                                label.height >= text.height + label.compoundPaddingTop + label.compoundPaddingBottom)
                            for (line in 0 until text.lineCount) {
                                assertEquals("Unexpected truncation: ${label.text}", 0, text.getEllipsisCount(line))
                            }
                        }
                        for (rating in listOf(binding.ratingBarPunctuality, binding.ratingBarDrive, binding.ratingBarBehaviour)) {
                            val container = rating.parent as View
                            assertTrue("All five stars must fit", rating.right <= container.width)
                            assertEquals(5, rating.numStars)
                            assertTrue(rating.isIndicator)
                            val label = (rating.parent as android.view.ViewGroup).descendants
                                .filterIsInstance<TextView>().single()
                            assertEquals(rating.id, label.labelFor)
                            assertTrue("Stars must not overlap their label", rating.top >= label.bottom)
                        }
                        val haltRow = requireNotNull(binding.recyclerViewBusStops.findViewHolderForAdapterPosition(0)).itemView
                        val name = haltRow.findViewById<TextView>(R.id.textViewStopName)
                        val time = haltRow.findViewById<TextView>(R.id.textViewScheduledTime)
                        assertEquals("11:55 AM - 12:05 PM", time.text.toString())
                        assertEquals(android.graphics.Color.rgb(211, 47, 47), name.currentTextColor)
                        fun bounds(root: android.view.ViewGroup, view: View): Rect = Rect().also {
                            view.getDrawingRect(it)
                            root.offsetDescendantRectToMyCoords(view, it)
                        }
                        assertTrue(bounds(haltRow as android.view.ViewGroup, time).top >= bounds(haltRow, name).bottom)
                        val unknown = requireNotNull(binding.recyclerViewBusStops.findViewHolderForAdapterPosition(1)).itemView
                        assertEquals("--:--", unknown.findViewById<TextView>(R.id.textViewScheduledTime).text.toString())
                        assertEquals(listOf("11:55", "12:05", "--:--"), stops.map { it.scheduledTime })
                        val comment = requireNotNull(binding.commentsRecyclerView.findViewHolderForAdapterPosition(0)).itemView
                            as android.view.ViewGroup
                        val username = comment.findViewById<TextView>(R.id.textViewUsername)
                        val date = comment.findViewById<TextView>(R.id.textViewCommentDate)
                        val body = comment.findViewById<TextView>(R.id.textViewCommentText)
                        assertTrue(bounds(comment, date).top >= bounds(comment, username).bottom)
                        assertTrue(bounds(comment, body).top >= bounds(comment, date).bottom)
                        binding.ratingsCard.visibility = View.GONE
                        binding.commentsRecyclerView.visibility = View.GONE
                        binding.textViewNoComments.visibility = View.VISIBLE
                        binding.recyclerViewBusStops.visibility = View.GONE
                        binding.textViewError.text = context.getText(R.string.msg_no_stops_found)
                        binding.textViewError.visibility = View.VISIBLE
                        layout()
                        assertEquals(View.VISIBLE, binding.buttonAddRating.visibility)
                        assertEquals(View.VISIBLE, binding.buttonAddComment.visibility)
                        val scroll = binding.detailsScrollView
                        scroll.scrollTo(0, scroll.getChildAt(0).height)
                        assertTrue("Last action must remain reachable",
                            scroll.scrollY + scroll.height >= scroll.getChildAt(0).height)
                    }
                }
            }
        }
    }
}
