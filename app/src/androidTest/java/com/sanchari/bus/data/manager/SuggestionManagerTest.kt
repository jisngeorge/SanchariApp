package com.sanchari.bus.data.manager

import android.graphics.Typeface
import android.text.Spanned
import android.text.style.StyleSpan
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sanchari.bus.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SuggestionManagerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager = SuggestionManager(context)

    @Test
    fun separatesBoardingAndAlightingWithCorrectlyLabelledDistancesAndCounts() {
        val text = manager.formatSuggestions(
            "Start", "End",
            listOf(SuggestionManager.StopResult("Boarding stop", 1.2f, 8.5f, 7, 3)),
            listOf(SuggestionManager.StopResult("Alighting stop", 2.3f, 9.6f, 4, 1))
        )
        val formatted = text.toString()

        assertTrue(formatted.contains(context.getString(R.string.nearby_boarding_heading, "Start")))
        assertTrue(formatted.contains(context.getString(R.string.nearby_alighting_heading, "End")))
        assertTrue(formatted.contains(context.getString(
            R.string.nearby_stop_distances, "Start", 1.2f, "End", 8.5f
        )))
        assertTrue(formatted.contains(context.getString(
            R.string.nearby_stop_distances, "Start", 9.6f, "End", 2.3f
        )))
        assertTrue(formatted.contains(context.getString(R.string.nearby_stop_services, 3, 7)))
        assertTrue(formatted.contains(context.getString(R.string.nearby_stop_services, 1, 4)))
        assertTrue(formatted.contains(context.getString(R.string.nearby_distance_note)))
        assertTrue(formatted.contains(context.getString(R.string.nearby_additional_note, "Start", "End")))
        assertFalse(formatted.contains("|"))
        assertTrue(text is Spanned)
        val spans = (text as Spanned).getSpans(0, text.length, StyleSpan::class.java)
        assertEquals(4, spans.count { it.style == Typeface.BOLD })
    }

    @Test
    fun preservesNearestStopOrderingAndUsesAdditionalServicesToBreakTies() {
        val stops = listOf(
            SuggestionManager.StopResult("Far", 3f, 5f, 8, 8),
            SuggestionManager.StopResult("Near fewer", 1f, 5f, 4, 1),
            SuggestionManager.StopResult("Near more", 1f, 5f, 5, 3)
        )
        val formatted = manager.formatSuggestions("Start", "End", stops, emptyList()).toString()

        assertTrue(formatted.indexOf("Near more") < formatted.indexOf("Near fewer"))
        assertTrue(formatted.indexOf("Near fewer") < formatted.indexOf("Far"))
        assertFalse(formatted.contains(context.getString(R.string.nearby_alighting_heading, "End")))
    }

    @Test
    fun noAlternativesShowsAnExplicitMessageInsteadOfEmptyHeadings() {
        val formatted = manager.formatSuggestions("Start", "End", emptyList(), emptyList())

        assertEquals(context.getString(R.string.nearby_no_suggestions, "Start", "End"), formatted)
    }

    @Test
    fun destinationOnlyResultsDoNotShowAnEmptyBoardingSection() {
        val formatted = manager.formatSuggestions(
            "Start", "End", emptyList(),
            listOf(SuggestionManager.StopResult("Alighting stop", 1f, 9f, 4, 2))
        ).toString()

        assertFalse(formatted.contains(context.getString(R.string.nearby_boarding_heading, "Start")))
        assertTrue(formatted.contains(context.getString(R.string.nearby_alighting_heading, "End")))
    }
}
