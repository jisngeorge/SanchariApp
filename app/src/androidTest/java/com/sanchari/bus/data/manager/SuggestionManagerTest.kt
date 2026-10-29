package com.sanchari.bus.data.manager

import android.graphics.Typeface
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.text.Spanned
import android.text.style.StyleSpan
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sanchari.bus.R
import com.sanchari.bus.data.local.DatabaseConstants
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

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
        assertFalse(formatted.contains("40%"))
        assertFalse(formatted.contains("Ranked"))
        assertTrue(formatted.contains(context.getString(R.string.nearby_additional_note, "Start", "End")))
        assertFalse(formatted.contains("|"))
        assertTrue(text is Spanned)
        val spans = (text as Spanned).getSpans(0, text.length, StyleSpan::class.java)
        assertEquals(4, spans.count { it.style == Typeface.BOLD })
    }

    @Test
    fun ranksByWeightedServiceCountsAndProximityRatherThanDistanceAlone() {
        val stops = listOf(
            SuggestionManager.StopResult("Far", 3f, 5f, 8, 8),
            SuggestionManager.StopResult("Near fewer", 1f, 5f, 4, 1),
            SuggestionManager.StopResult("Near more", 1f, 5f, 5, 3)
        )
        val formatted = manager.formatSuggestions("Start", "End", stops, emptyList()).toString()

        assertTrue(formatted.indexOf("Far") < formatted.indexOf("Near more"))
        assertTrue(formatted.indexOf("Near more") < formatted.indexOf("Near fewer"))
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

    @Test
    fun balancedCandidateCanBeatBothTheNearestAndTheMostAdditionalServices() {
        val stops = listOf(
            SuggestionManager.StopResult("Most additional", 10f, 20f, 10, 10),
            SuggestionManager.StopResult("Balanced", 5f, 20f, 20, 6),
            SuggestionManager.StopResult("Nearest", 0f, 20f, 2, 2)
        )
        // Scores: 0.5333, 0.65, 0.3 after independent min-max normalization.
        assertEquals(listOf("Balanced", "Most additional", "Nearest"), manager.rankStops(stops).map { it.name })
        assertEquals("Most additional", stops.first().name)
    }

    @Test
    fun additionalServiceWeightOutweighsDistanceWeightWhenTotalCountsAreEqual() {
        val stops = listOf(
            SuggestionManager.StopResult("Nearest", 0f, 20f, 20, 2),
            SuggestionManager.StopResult("More additional", 10f, 20f, 20, 10)
        )
        assertEquals(listOf("More additional", "Nearest"), manager.rankStops(stops).map { it.name })
    }

    @Test
    fun totalServiceCountContributesWhenOtherFactorsAreEqual() {
        val stops = listOf(
            SuggestionManager.StopResult("Fewer total", 1f, 5f, 5, 2),
            SuggestionManager.StopResult("More total", 1f, 5f, 20, 2)
        )
        assertEquals(listOf("More total", "Fewer total"), manager.rankStops(stops).map { it.name })
    }

    @Test
    fun proximityUsesThisSidesOriginalStopNotTheOppositeEndpoint() {
        val stops = listOf(
            SuggestionManager.StopResult("Far from original", 3f, 0f, 5, 2),
            SuggestionManager.StopResult("Near original", 1f, 100f, 5, 2)
        )
        assertEquals(listOf("Near original", "Far from original"), manager.rankStops(stops).map { it.name })
    }

    @Test
    fun boardingAndAlightingNormalizeIndependentlyAndUseTheSameWeights() {
        val boarding = listOf(
            SuggestionManager.StopResult("Board far", 10f, 20f, 10, 10),
            SuggestionManager.StopResult("Board balanced", 5f, 20f, 20, 6),
            SuggestionManager.StopResult("Board nearest", 0f, 20f, 2, 2)
        )
        val alighting = boarding.map {
            it.copy(name = it.name.replace("Board", "Alight"), distanceFromOriginalKm = it.distanceFromOriginalKm / 100,
                totalServiceCount = it.totalServiceCount * 100, newOptionCount = it.newOptionCount * 100)
        }
        val formatted = manager.formatSuggestions("Start", "End", boarding, alighting).toString()
        val boardingText = formatted.substringBefore(context.getString(R.string.nearby_alighting_heading, "End"))
        val alightingText = formatted.substringAfter(context.getString(R.string.nearby_alighting_heading, "End"))
        assertTrue(boardingText.indexOf("Board balanced") < boardingText.indexOf("Board far"))
        assertTrue(boardingText.indexOf("Board far") < boardingText.indexOf("Board nearest"))
        assertTrue(alightingText.indexOf("Alight balanced") < alightingText.indexOf("Alight far"))
        assertTrue(alightingText.indexOf("Alight far") < alightingText.indexOf("Alight nearest"))
    }

    @Test
    fun equalScoresUseDeterministicDistanceAndNameTiesIncludingZeroDistance() {
        val stops = listOf(
            SuggestionManager.StopResult("Zulu", 0f, 8f, 4, 2),
            SuggestionManager.StopResult("Alpha", 0f, 5f, 4, 2)
        )
        assertEquals(listOf("Alpha", "Zulu"), manager.rankStops(stops).map { it.name })
        assertEquals(manager.rankStops(stops), manager.rankStops(stops.reversed()))
        val equalScore = listOf(
            SuggestionManager.StopResult("Far more total", 2f, 5f, 10, 2),
            SuggestionManager.StopResult("Near fewer total", 0f, 5f, 5, 2)
        )
        assertEquals("Near fewer total", manager.rankStops(equalScore).first().name)
    }

    @Test
    fun emptyAndSingleCandidateSectionsNeedNoNormalization() {
        assertEquals(emptyList<SuggestionManager.StopResult>(), manager.rankStops(emptyList()))
        val one = listOf(SuggestionManager.StopResult("Only", 0f, 5f, 2, 1))
        assertEquals(one, manager.rankStops(one))
    }

    @Test
    fun loopServicesCannotSuggestTheOppositeEndpointOnEitherSide() {
        withNearbyFixture(includeAlternatives = true) { fixtureManager ->
            val formatted = runBlocking { fixtureManager.getNearbySuggestionsText("Start", "End") }.toString()
            val boarding = formatted.substringAfter(context.getString(R.string.nearby_boarding_heading, "Start"))
                .substringBefore(context.getString(R.string.nearby_alighting_heading, "End"))
            val alighting = formatted.substringAfter(context.getString(R.string.nearby_alighting_heading, "End"))
            assertTrue(boarding.contains(context.getString(R.string.nearby_stop_name, 1, "Board alternative")))
            assertTrue(alighting.contains(context.getString(R.string.nearby_stop_name, 1, "Alight alternative")))
            for (section in listOf(boarding, alighting)) {
                assertFalse(Regex("(?m)^\\d+\\. Start$").containsMatchIn(section))
                assertFalse(Regex("(?m)^\\d+\\. End$").containsMatchIn(section))
            }
        }
    }

    @Test
    fun endpointOnlyLoopCandidatesProduceTheExplicitEmptyMessage() {
        withNearbyFixture(includeAlternatives = false) { fixtureManager ->
            val formatted = runBlocking { fixtureManager.getNearbySuggestionsText("Start", "End") }
            assertEquals(context.getString(R.string.nearby_no_suggestions, "Start", "End"), formatted)
        }
    }

    private fun withNearbyFixture(includeAlternatives: Boolean, check: (SuggestionManager) -> Unit) {
        val file = File(context.cacheDir, "nearby-fixture-${UUID.randomUUID()}.db")
        val fixtureContext = object : ContextWrapper(context) {
            override fun getDatabasePath(name: String): File = file
        }
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                db.execSQL(DatabaseConstants.StopTable.CREATE_TABLE)
                db.execSQL(DatabaseConstants.RouteStopTable.CREATE_TABLE)
                listOf("Start" to 76.0, "End" to 76.1,
                    "Board alternative" to 76.02, "Alight alternative" to 76.08
                ).forEachIndexed { index, (name, longitude) ->
                    db.execSQL("INSERT INTO Stop VALUES (?, ?, 10.0, ?)", arrayOf(index + 1, name, longitude))
                }
                fun route(id: String, vararg stops: Int) {
                    stops.forEachIndexed { index, stop ->
                        db.execSQL(
                            "INSERT INTO RouteStop (serviceId, stopId, scheduledTime, stopOrder) VALUES (?, ?, '08:00', ?)",
                            arrayOf(id, stop, index + 1)
                        )
                    }
                }
                route("source-loop", 1, 1)
                route("destination-loop", 2, 2)
                if (includeAlternatives) {
                    route("boarding", 3, 2)
                    route("alighting", 1, 4)
                }
            }
            check(SuggestionManager(fixtureContext))
        } finally {
            SQLiteDatabase.deleteDatabase(file)
        }
    }
}
