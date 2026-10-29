package com.sanchari.bus.data.manager

import android.database.sqlite.SQLiteDatabase
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sanchari.bus.data.local.DatabaseConstants
import com.sanchari.bus.data.model.BusService
import com.sanchari.bus.data.model.RouteSearchResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class WildcardSearchQueryTest {
    private lateinit var db: SQLiteDatabase

    @Before
    fun createFixture() {
        db = SQLiteDatabase.create(null)
        db.execSQL(DatabaseConstants.BusServiceTable.CREATE_TABLE)
        db.execSQL(DatabaseConstants.StopTable.CREATE_TABLE)
        db.execSQL(DatabaseConstants.RouteStopTable.CREATE_TABLE)
        listOf("Origin", "A", "B", "End", "O'Brien", "A*").forEachIndexed { index, name ->
            db.execSQL("INSERT INTO Stop VALUES (?, ?, 0, 0)", arrayOf(index + 1, name))
        }
        route("loop", listOf(1 to "23:00", 2 to "23:30", 3 to "23:45", 2 to "23:30", 4 to "00:30"))
        route("starts-at-A", listOf(2 to "08:00", 4 to "09:00"))
        route("ends-at-A", listOf(1 to "10:00", 2 to "11:00"))
        route("unknown", listOf(1 to "12:00", 2 to null, 4 to "13:00"))
        route("quoted", listOf(5 to "14:00", 4 to "15:00"))
        route("literal-star", listOf(6 to "14:00", 4 to "15:00"))
        route("alone", listOf(2 to "16:00"))
    }

    @After
    fun closeFixture() {
        db.close()
    }

    @Test
    fun outgoingVisitsStopBeforeRepetitionAndDoNotExpandToEveryLaterStop() {
        val results = SearchManager.findRouteResults(db, "A", "*")
        assertEquals(4, results.size)
        assertTrue(results.all { it.fromPlace == "A" })
        assertEquals(2, results.count { it.service.serviceId == "loop" })
        assertEquals("End", results.single { it.service.serviceId == "starts-at-A" }.toPlace)
        assertTrue(results.none { it.service.serviceId in setOf("ends-at-A", "alone") })
        assertEquals("--:--", results.single { it.service.serviceId == "unknown" }.service.fromTime)
        val loop = results.filter { it.service.serviceId == "loop" }
        assertEquals(listOf("B", "End"), loop.map { it.toPlace })
        assertEquals(listOf("23:45", "00:30"), loop.map { it.service.toTime })
        assertEquals(2, loop.map { it.fromRouteStopId }.distinct().size)
    }

    @Test
    fun incomingVisitsHaveIndependentOriginsAndExcludeInitialVisits() {
        val results = SearchManager.findRouteResults(db, "*", "A")
        assertEquals(4, results.size)
        assertTrue(results.all { it.toPlace == "A" })
        val loop = results.filter { it.service.serviceId == "loop" }
        assertEquals(listOf("Origin", "B"), loop.map { it.fromPlace })
        assertEquals(listOf("23:00", "23:45"), loop.map { it.service.fromTime })
        assertEquals(2, results.filter { it.service.serviceId == "loop" }.map { it.toRouteStopId }.distinct().size)
        assertTrue(results.none { it.service.serviceId in setOf("starts-at-A", "alone") })
        assertEquals("--:--", results.single { it.service.serviceId == "unknown" }.service.toTime)
    }

    @Test
    fun fixedRouteKeepsNearestRepeatedEndpointPairs() {
        val results = SearchManager.findRouteResults(db, "A", "B")
        assertEquals(1, results.size)
        assertEquals("23:30", results.single().service.fromTime)
        assertEquals("23:45", results.single().service.toTime)
        assertEquals(1, SearchManager.findRouteResults(db, "A", "End").count { it.service.serviceId == "loop" })
        route("repeat-destination", listOf(
            1 to "08:00", 2 to "09:00", 3 to "09:30", 3 to "09:40", 4 to "10:00"
        ))
        val matched = SearchManager.findRouteResults(db, "A", "B")
            .single { it.service.serviceId == "repeat-destination" }
        assertEquals("09:30", matched.service.toTime)
    }

    @Test
    fun usesBoundStopNamesAndDoesNotInterpretEmbeddedAsterisks() {
        assertEquals("quoted", SearchManager.findRouteResults(db, "O'Brien", "*").single().service.serviceId)
        assertEquals("literal-star", SearchManager.findRouteResults(db, "A*", "*").single().service.serviceId)
        assertTrue(SearchManager.findRouteResults(db, "' OR 1=1 --", "*").isEmpty())
        assertTrue(SearchManager.findRouteResults(db, "*", "Missing").isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnboundedSearchAtTheQueryBoundary() {
        SearchManager.findRouteResults(db, "*", "*")
    }

    @Test
    fun outgoingAndIncomingLoopsUseTheirRespectiveTurningPoints() {
        route("return", listOf(2 to "17:00", 3 to "17:30", 2 to "18:00"))
        val outgoing = SearchManager.findRouteResults(db, "A", "*").single { it.service.serviceId == "return" }
        val incoming = SearchManager.findRouteResults(db, "*", "A").single { it.service.serviceId == "return" }
        assertEquals("B", outgoing.toPlace)
        assertEquals("17:30", outgoing.service.toTime)
        assertEquals("B", incoming.fromPlace)
        assertEquals(outgoing.toRouteStopId, incoming.fromRouteStopId)
        assertTrue(outgoing.toRouteStopId != incoming.toRouteStopId)
    }

    @Test
    fun retracedStopsAndHaltChooseMArrivalAtOrderSix() {
        addTurnaroundStops()
        route(
            "turnaround",
            listOf(
                2 to "09:00", 7 to "09:30", 8 to "10:00",
                9 to "10:30", 9 to "10:45", 8 to "11:00", 7 to "11:30"
            ),
            listOf(3, 4, 5, 6, 7, 8, 9)
        )
        val result = SearchManager.findRouteResults(db, "A", "*")
            .single { it.service.serviceId == "turnaround" }
        assertEquals("M", result.toPlace)
        assertEquals("10:30", result.service.toTime)
        val stopOrder = db.rawQuery(
            "SELECT stopOrder FROM RouteStop WHERE routeStopId = ?",
            arrayOf(result.toRouteStopId.toString())
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }
        assertEquals(6, stopOrder)

        val incoming = SearchManager.findRouteResults(db, "*", "K")
            .filter { it.service.serviceId == "turnaround" }
        assertEquals(2, incoming.size)
        assertEquals(listOf("A", "M"), incoming.map { it.fromPlace })
        assertEquals(listOf("09:00", "10:45"), incoming.map { it.service.fromTime })
        assertEquals(listOf("09:30", "11:30"), incoming.map { it.service.toTime })
    }

    @Test
    fun laterDeparturesStartIndependentLegsEndingBeforeTheirOwnFirstReturn() {
        route("per-visit", listOf(
            2 to "08:00", 3 to "08:15", 2 to "08:30",
            4 to "08:45", 3 to "09:00", 2 to "09:15"
        ))
        val results = SearchManager.findRouteResults(db, "A", "*")
            .filter { it.service.serviceId == "per-visit" }
        assertEquals(2, results.size)
        assertEquals(listOf("B", "B"), results.map { it.toPlace })
        assertEquals(listOf("08:15", "09:00"), results.map { it.service.toTime })
        assertEquals(listOf("08:00", "08:30"), results.map { it.service.fromTime })
    }

    @Test
    fun retracingAfterAHaltStillEndsAtTheFirstFArrival() {
        listOf(7 to "C", 8 to "D", 9 to "F").forEach { (id, name) ->
            db.execSQL("INSERT INTO Stop VALUES (?, ?, 0, 0)", arrayOf(id, name))
        }
        route(
            "prefix-return",
            listOf(
                7 to "08:00", 2 to "08:15", 8 to "08:30", 9 to "08:45",
                9 to "09:00", 8 to "09:15", 7 to "09:30"
            ),
            listOf(1, 2, 3, 4, 5, 6, 7)
        )
        val result = SearchManager.findRouteResults(db, "A", "*")
            .single { it.service.serviceId == "prefix-return" }
        assertEquals("F", result.toPlace)
        assertEquals("08:45", result.service.toTime)
        db.rawQuery(
            "SELECT stopOrder FROM RouteStop WHERE routeStopId = ?",
            arrayOf(result.toRouteStopId.toString())
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(4, cursor.getInt(0))
        }

        val incoming = SearchManager.findRouteResults(db, "*", "A")
            .single { it.service.serviceId == "prefix-return" }
        assertEquals("C", incoming.fromPlace)
        assertEquals("08:00", incoming.service.fromTime)
        assertEquals("08:15", incoming.service.toTime)
    }

    @Test
    fun placesVisitedBeforeADoNotExcludeAnOnwardDestination() {
        route("all-seen", listOf(
            1 to "08:00", 3 to "08:15", 2 to "08:30", 3 to "08:45", 1 to "09:00"
        ))
        val result = SearchManager.findRouteResults(db, "A", "*")
            .single { it.service.serviceId == "all-seen" }
        assertEquals("Origin", result.toPlace)
        assertEquals("09:00", result.service.toTime)
    }

    @Test
    fun samePlaceNameBeforeADoesNotAffectTheDepartureLeg() {
        db.execSQL("INSERT INTO Stop VALUES (7, 'Origin', 0, 0)")
        route("same-name-prefix", listOf(
            1 to "08:00", 2 to "08:15", 3 to "08:30", 7 to "08:45"
        ))
        val result = SearchManager.findRouteResults(db, "A", "*")
            .single { it.service.serviceId == "same-name-prefix" }
        assertEquals("Origin", result.toPlace)
        assertEquals("08:45", result.service.toTime)
    }

    @Test
    fun comparesStopNamesAndPreservesUnknownTurnaroundTime() {
        addTurnaroundStops()
        db.execSQL("INSERT INTO Stop VALUES (10, 'M', 0, 0)")
        route("same-name", listOf(2 to "08:00", 9 to null, 10 to "09:00", 2 to "10:00"))
        val result = SearchManager.findRouteResults(db, "A", "*")
            .single { it.service.serviceId == "same-name" }
        assertEquals("M", result.toPlace)
        assertEquals("--:--", result.service.toTime)
    }

    @Test
    fun noNewPlaceAfterTheMatchedVisitIsNotAnOutboundJourney() {
        route("only-A", listOf(2 to "08:00", 2 to "08:05", 2 to "08:10"))
        assertTrue(SearchManager.findRouteResults(db, "A", "*").none { it.service.serviceId == "only-A" })
    }

    @Test
    fun oneWayHaltUsesFirstArrivalAndNewPlacesAfterRetracingAreExcluded() {
        route("halt", listOf(2 to "08:00", 3 to "08:30", 3 to "08:45"))
        val halt = SearchManager.findRouteResults(db, "A", "*").single { it.service.serviceId == "halt" }
        assertEquals("B", halt.toPlace)
        assertEquals("08:30", halt.service.toTime)

        route("later-new", listOf(
            2 to "08:00", 3 to "08:30", 2 to "09:00", 4 to "09:30", 3 to "10:00"
        ))
        val later = SearchManager.findRouteResults(db, "A", "*")
            .single { it.service.serviceId == "later-new" && it.service.fromTime == "08:00" }
        assertEquals("B", later.toPlace)
        assertEquals("08:30", later.service.toTime)
    }

    @Test
    fun keepsFirstLegEndingAtDAndAddsTheLaterDepartureToK() {
        listOf(7 to "C", 8 to "D", 9 to "K").forEach { (id, name) ->
            db.execSQL("INSERT INTO Stop VALUES (?, ?, 0, 0)", arrayOf(id, name))
        }
        route(
            "after-turnaround",
            listOf(
                2 to "08:00", 3 to "08:10", 7 to "08:20", 8 to "08:30",
                8 to "08:40", 7 to "08:50", 3 to "09:00", 2 to "09:10",
                9 to "09:20", 2 to "09:30"
            ),
            (1..10).toList()
        )
        val results = SearchManager.findRouteResults(db, "A", "*")
            .filter { it.service.serviceId == "after-turnaround" }
        assertEquals(2, results.size)
        assertEquals(listOf("D", "K"), results.map { it.toPlace })
        assertEquals(listOf("08:00", "09:10"), results.map { it.service.fromTime })
        assertEquals(listOf("08:30", "09:20"), results.map { it.service.toTime })
        val result = results.first()
        assertEquals("D", result.toPlace)
        assertEquals("08:30", result.service.toTime)
        db.rawQuery(
            "SELECT stopOrder FROM RouteStop WHERE routeStopId = ?",
            arrayOf(result.toRouteStopId.toString())
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(4, cursor.getInt(0))
        }
    }

    @Test
    fun consecutiveHaltDoesNotStopTravelToNewPlaces() {
        route("halt-before-new", listOf(
            2 to "08:00", 3 to "08:15", 3 to "08:30", 4 to "08:45"
        ))
        val result = SearchManager.findRouteResults(db, "A", "*")
            .single { it.service.serviceId == "halt-before-new" }
        assertEquals("End", result.toPlace)
        assertEquals("08:45", result.service.toTime)
    }

    @Test
    fun consecutiveHaltBeforeADoesNotExcludeTheLaterVisit() {
        route("prefix-repeat", listOf(
            1 to "08:00", 1 to "08:10", 2 to "08:20", 3 to "08:30", 4 to "08:40"
        ))
        val result = SearchManager.findRouteResults(db, "A", "*")
            .single { it.service.serviceId == "prefix-repeat" }
        assertEquals("End", result.toPlace)
        assertEquals("08:40", result.service.toTime)
    }

    @Test
    fun requestedConsecutiveStopExampleContinuesToD() {
        db.execSQL("INSERT INTO Stop VALUES (7, 'C', 0, 0)")
        db.execSQL("INSERT INTO Stop VALUES (8, 'D', 0, 0)")
        route("consecutive-C", listOf(
            2 to "08:00", 3 to "08:10", 7 to "08:20", 7 to "08:30", 8 to "08:40"
        ))
        val result = SearchManager.findRouteResults(db, "A", "*")
            .single { it.service.serviceId == "consecutive-C" }
        assertEquals("D", result.toPlace)
        assertEquals("08:40", result.service.toTime)
    }

    @Test
    fun nonConsecutiveRepetitionBeforeADoesNotExcludeTheDeparture() {
        route("prefix-return-before-A", listOf(
            1 to "08:00", 3 to "08:10", 1 to "08:20", 2 to "08:30", 4 to "08:40"
        ))
        val result = SearchManager.findRouteResults(db, "A", "*")
            .single { it.service.serviceId == "prefix-return-before-A" }
        assertEquals("End", result.toPlace)
        assertEquals("08:30", result.service.fromTime)
        assertEquals("08:40", result.service.toTime)
    }

    @Test
    fun consecutiveNamesWithDifferentStopIdsDoNotHideLaterPlaces() {
        db.execSQL("INSERT INTO Stop VALUES (7, 'B', 0, 0)")
        route("same-name-halt", listOf(
            2 to "08:00", 3 to "08:10", 7 to "08:20", 4 to "08:30", 4 to "08:40"
        ))
        val result = SearchManager.findRouteResults(db, "A", "*")
            .single { it.service.serviceId == "same-name-halt" }
        assertEquals("End", result.toPlace)
        assertEquals("08:30", result.service.toTime)
    }

    @Test
    fun outboundHaltUsesOnlyTheLastADepartureAndFirstDestinationArrival() {
        db.execSQL("INSERT INTO Stop VALUES (7, 'C', 0, 0)")
        db.execSQL("INSERT INTO Stop VALUES (8, 'D', 0, 0)")
        route("departure-halt", listOf(
            2 to "08:00", 2 to "08:10", 3 to "08:20", 7 to "08:30",
            8 to "08:40", 8 to "08:50"
        ))
        val result = SearchManager.findRouteResults(db, "A", "*")
            .single { it.service.serviceId == "departure-halt" }
        assertEquals("08:10", result.service.fromTime)
        assertEquals("D", result.toPlace)
        assertEquals("08:40", result.service.toTime)
        assertEquals(
            routeStopId("departure-halt", 20),
            result.fromRouteStopId
        )
    }

    @Test
    fun intermediateHaltWithDifferentStopIdsUsesLastRowEvenWhenTimesAreEqual() {
        db.execSQL("INSERT INTO Stop VALUES (7, 'A', 0, 0)")
        route("intermediate-halt", listOf(
            1 to "08:00", 2 to "08:10", 7 to "08:15", 2 to "08:15", 4 to "08:40"
        ))
        val result = SearchManager.findRouteResults(db, "A", "*")
            .single { it.service.serviceId == "intermediate-halt" }
        assertEquals("08:15", result.service.fromTime)
        assertEquals(routeStopId("intermediate-halt", 40), result.fromRouteStopId)
        assertEquals("End", result.toPlace)
    }

    @Test
    fun departureHaltAndLaterReturnEachProduceTheirOwnLeg() {
        route("halt-and-return", listOf(
            2 to "08:00", 2 to "08:10", 3 to "08:20",
            2 to "08:30", 2 to "08:40", 4 to "08:50"
        ))
        val results = SearchManager.findRouteResults(db, "A", "*")
            .filter { it.service.serviceId == "halt-and-return" }
        assertEquals(listOf("08:10", "08:40"), results.map { it.service.fromTime })
        assertEquals(listOf("B", "End"), results.map { it.toPlace })
        assertEquals(listOf("08:20", "08:50"), results.map { it.service.toTime })
    }

    @Test
    fun arrivalWildcardUsesFirstHaltRowWhileFixedRouteKeepsItsDepartureMatching() {
        route("halt-modes", listOf(
            1 to "08:00", 2 to "08:10", 2 to "08:20", 4 to "08:40"
        ))
        val incoming = SearchManager.findRouteResults(db, "*", "A")
            .filter { it.service.serviceId == "halt-modes" }
        assertEquals(listOf("08:10"), incoming.map { it.service.toTime })
        val fixed = SearchManager.findRouteResults(db, "A", "End")
            .single { it.service.serviceId == "halt-modes" }
        assertEquals("08:20", fixed.service.fromTime)
    }

    @Test
    fun requestedThreeLegServiceUsesEachDepartureAndFirstTurnaroundArrival() {
        listOf(7 to "C", 8 to "D", 9 to "E").forEach { (id, name) ->
            db.execSQL("INSERT INTO Stop VALUES (?, ?, 0, 0)", arrayOf(id, name))
        }
        val stops = listOf(2, 3, 7, 7, 3, 2, 2, 8, 9, 9, 8, 2, 2, 3, 7, 7, 3, 2)
        val times = listOf(
            "08:00", "08:10", "08:20", "08:30", "08:40", "08:50",
            "09:00", "09:10", "09:20", "09:30", "09:40", "09:50",
            "10:00", "10:10", "10:20", "10:30", "10:40", "10:50"
        )
        route("three-legs", stops.zip(times), (1..18).toList())
        val results = SearchManager.findRouteResults(db, "A", "*")
            .filter { it.service.serviceId == "three-legs" }
        assertEquals(listOf("C", "E", "C"), results.map { it.toPlace })
        assertEquals(listOf("08:00", "09:00", "10:00"), results.map { it.service.fromTime })
        assertEquals(listOf("08:20", "09:20", "10:20"), results.map { it.service.toTime })
        assertEquals(
            listOf(1 to 3, 7 to 9, 13 to 15).map { (from, to) ->
                routeStopId("three-legs", from) to routeStopId("three-legs", to)
            },
            results.map { it.fromRouteStopId to it.toRouteStopId }
        )
        val incoming = SearchManager.findRouteResults(db, "*", "A")
            .filter { it.service.serviceId == "three-legs" }
        assertEquals(listOf("C", "E", "C"), incoming.map { it.fromPlace })
        assertEquals(listOf("08:30", "09:30", "10:30"), incoming.map { it.service.fromTime })
        assertEquals(listOf("08:50", "09:50", "10:50"), incoming.map { it.service.toTime })
        assertEquals(
            listOf(4 to 6, 10 to 12, 16 to 18).map { (from, to) ->
                routeStopId("three-legs", from) to routeStopId("three-legs", to)
            },
            incoming.map { it.fromRouteStopId to it.toRouteStopId }
        )
    }

    @Test
    fun incomingHaltsUseNamesLastOriginDepartureAndFirstArrivalEvenWithEqualTimes() {
        db.execSQL("INSERT INTO Stop VALUES (7, 'A', 0, 0)")
        db.execSQL("INSERT INTO Stop VALUES (8, 'B', 0, 0)")
        route("incoming-halts", listOf(
            3 to "23:40", 8 to "23:50", 2 to "00:10", 7 to "00:10",
            3 to null, 8 to "23:55", 2 to "00:10", 7 to "00:20"
        ))
        val results = SearchManager.findRouteResults(db, "*", "A")
            .filter { it.service.serviceId == "incoming-halts" }
        assertEquals(listOf("B", "B"), results.map { it.fromPlace })
        assertEquals(listOf("23:50", "23:55"), results.map { it.service.fromTime })
        assertEquals(listOf("00:10", "00:10"), results.map { it.service.toTime })
        assertEquals(
            listOf(20 to 30, 60 to 70).map { (from, to) ->
                routeStopId("incoming-halts", from) to routeStopId("incoming-halts", to)
            },
            results.map { it.fromRouteStopId to it.toRouteStopId }
        )
    }

    @Test
    fun incomingStopsAtFirstReverseReturnAndIgnoresHistoryAfterTheArrival() {
        db.execSQL("INSERT INTO Stop VALUES (7, 'C', 0, 0)")
        db.execSQL("INSERT INTO Stop VALUES (8, 'D', 0, 0)")
        route("incoming-boundary", listOf(
            1 to "08:00", 3 to "08:10", 7 to "08:20", 3 to "08:30",
            8 to "08:40", 2 to "08:50", 8 to "09:00"
        ))
        val result = SearchManager.findRouteResults(db, "*", "A")
            .single { it.service.serviceId == "incoming-boundary" }
        assertEquals("C", result.fromPlace)
        assertEquals("08:20", result.service.fromTime)
        assertEquals("08:50", result.service.toTime)
        assertEquals(routeStopId("incoming-boundary", 30), result.fromRouteStopId)
    }

    @Test
    fun incomingContinuesPastConsecutiveHaltsButExcludesOnlyAHalts() {
        route("incoming-one-way", listOf(
            1 to null, 3 to "08:10", 3 to "08:20", 2 to "08:30", 2 to "08:40"
        ))
        route("incoming-only-A", listOf(2 to "09:00", 2 to "09:10", 2 to "09:20"))
        val results = SearchManager.findRouteResults(db, "*", "A")
        val result = results.single { it.service.serviceId == "incoming-one-way" }
        assertEquals("Origin", result.fromPlace)
        assertEquals("--:--", result.service.fromTime)
        assertEquals("08:30", result.service.toTime)
        assertTrue(results.none { it.service.serviceId == "incoming-only-A" })
    }

    @Test
    fun laterLegCanReuseNamesWithEqualDepartureTimesAcrossMidnightAndMissingArrival() {
        db.execSQL("INSERT INTO Stop VALUES (7, 'A', 0, 0)")
        db.execSQL("INSERT INTO Stop VALUES (8, 'B', 0, 0)")
        route("reused-names", listOf(
            2 to "23:40", 7 to "23:50", 3 to "00:05", 8 to "00:10",
            2 to "00:20", 7 to "23:50", 3 to null, 8 to "00:40", 2 to "01:00"
        ))
        val results = SearchManager.findRouteResults(db, "A", "*")
            .filter { it.service.serviceId == "reused-names" }
        assertEquals(listOf("B", "B"), results.map { it.toPlace })
        assertEquals(listOf("23:50", "23:50"), results.map { it.service.fromTime })
        assertEquals(listOf("00:05", "--:--"), results.map { it.service.toTime })
        assertEquals(
            listOf(20 to 30, 60 to 70).map { (from, to) ->
                routeStopId("reused-names", from) to routeStopId("reused-names", to)
            },
            results.map { it.fromRouteStopId to it.toRouteStopId }
        )
    }

    @Test
    fun eachLegStopsAtItsFirstReturnEvenWhenNewPlacesFollowBeforeTheNextA() {
        listOf(7 to "C", 8 to "D", 9 to "E", 10 to "F", 11 to "G").forEach { (id, name) ->
            db.execSQL("INSERT INTO Stop VALUES (?, ?, 0, 0)", arrayOf(id, name))
        }
        route("independent-boundaries", listOf(
            2 to "08:00", 3 to "08:10", 7 to "08:20", 3 to "08:30", 8 to "08:40",
            2 to "08:50", 9 to "09:00", 9 to "09:10", 10 to "09:20",
            9 to "09:30", 11 to "09:40"
        ))
        val results = SearchManager.findRouteResults(db, "A", "*")
            .filter { it.service.serviceId == "independent-boundaries" }
        assertEquals(listOf("C", "F"), results.map { it.toPlace })
        assertEquals(listOf("08:00", "08:50"), results.map { it.service.fromTime })
        assertEquals(listOf("08:20", "09:20"), results.map { it.service.toTime })
    }

    @Test
    fun generatedRoutesMatchIndependentPerVisitScansInBothDirections() {
        data class Row(val id: Int, val place: String, val time: String?)

        listOf(7 to "A", 8 to "B", 9 to "End").forEach { (id, name) ->
            db.execSQL("INSERT INTO Stop VALUES (?, ?, 0, 0)", arrayOf(id, name))
        }
        val random = Random(472)
        val stopIds = listOf(2, 3, 4, 7, 8, 9)
        val times = listOf(null, "--:--", "invalid", "00:05", "08:00", "08:00", "23:55")
        db.beginTransaction()
        try {
            repeat(150) { index ->
                val count = random.nextInt(1, 61)
                route(
                    "generated-$index",
                    List(count) { stopIds.random(random) to times.random(random) },
                    List(count) { random.nextInt(0, 30) }
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        val routes = linkedMapOf<String, MutableList<Row>>()
        db.rawQuery(
            """SELECT r.serviceId, r.routeStopId, s.locationName, r.scheduledTime
               FROM RouteStop r JOIN Stop s ON s.stopId = r.stopId
               WHERE r.serviceId LIKE 'generated-%'
               ORDER BY r.serviceId, r.stopOrder, r.routeStopId""", null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                routes.getOrPut(cursor.getString(0)) { mutableListOf() }.add(
                    Row(cursor.getInt(1), cursor.getString(2),
                        if (cursor.isNull(3)) null else cursor.getString(3))
                )
            }
        }
        for (incoming in listOf(false, true)) {
            for (place in listOf("A", "B", "End")) {
                val expected = mutableListOf<Pair<String?, RouteSearchResult>>()
                for ((serviceId, rows) in routes) {
                    for (anchor in rows.indices) {
                        val matched = rows[anchor]
                        val adjacent = if (incoming) anchor - 1 else anchor + 1
                        if (matched.place != place || rows.getOrNull(adjacent)?.place == place) continue
                        val seen = mutableSetOf(place)
                        var previousPlace = place
                        var terminal: Row? = null
                        var index = adjacent
                        while (index in rows.indices) {
                            val row = rows[index]
                            if (row.place != previousPlace) {
                                if (!seen.add(row.place)) break
                                terminal = row
                            }
                            previousPlace = row.place
                            index += if (incoming) -1 else 1
                        }
                        val endpoint = terminal ?: continue
                        val from = if (incoming) endpoint else matched
                        val to = if (incoming) matched else endpoint
                        expected.add(
                            matched.time to RouteSearchResult(
                                BusService(serviceId, serviceId, "ORDINARY", false, 0L,
                                    from.time ?: "--:--", to.time ?: "--:--"),
                                from.place, to.place, from.id, to.id
                            )
                        )
                    }
                }
                val sorted = expected.sortedWith(
                    compareBy<Pair<String?, RouteSearchResult>> { it.first }
                        .thenBy { if (incoming) it.second.toRouteStopId else it.second.fromRouteStopId }
                ).map { it.second }
                val actual = SearchManager.findRouteResults(
                    db, if (incoming) "*" else place, if (incoming) place else "*"
                ).filter { it.service.serviceId.startsWith("generated-") }
                assertEquals("incoming=$incoming place=$place", sorted, actual)
            }
        }
    }

    @Test
    fun busiestBundledStopFitsTheResultTransportBudget() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("wildcard-fixture-", ".db", context.cacheDir)
        try {
            context.assets.open(DatabaseConstants.TIMETABLE_DATABASE_NAME).use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { snapshot ->
                val name = snapshot.rawQuery(
                    """SELECT s.locationName FROM RouteStop r JOIN Stop s ON r.stopId = s.stopId
                       GROUP BY s.locationName ORDER BY COUNT(*) DESC LIMIT 1""", null
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    cursor.getString(0)
                }
                for ((from, to) in listOf(name to "*", "*" to name)) {
                    val results = SearchManager.findRouteResults(snapshot, from, to)
                    val parcel = Parcel.obtain()
                    try {
                        parcel.writeTypedList(results)
                        assertTrue("Result parcel is ${parcel.dataSize()} bytes", parcel.dataSize() < 500_000)
                    } finally {
                        parcel.recycle()
                    }
                }
            }
        } finally {
            assertTrue(file.delete())
        }
    }

    private fun routeStopId(serviceId: String, stopOrder: Int): Int =
        db.rawQuery(
            "SELECT routeStopId FROM RouteStop WHERE serviceId = ? AND stopOrder = ?",
            arrayOf(serviceId, stopOrder.toString())
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun addTurnaroundStops() {
        listOf(7 to "K", 8 to "L", 9 to "M").forEach { (id, name) ->
            db.execSQL("INSERT INTO Stop VALUES (?, ?, 0, 0)", arrayOf(id, name))
        }
    }

    private fun route(
        id: String,
        stops: List<Pair<Int, String?>>,
        orders: List<Int> = stops.indices.map { (it + 1) * 10 }
    ) {
        db.execSQL(
            "INSERT INTO BusService (serviceId, name, type, isRunning, lastReportedTime) VALUES (?, ?, 'ORDINARY', 0, 0)",
            arrayOf(id, id)
        )
        stops.forEachIndexed { index, (stopId, time) ->
            db.execSQL(
                "INSERT INTO RouteStop (serviceId, stopId, scheduledTime, stopOrder) VALUES (?, ?, ?, ?)",
                arrayOf(id, stopId, time, orders[index])
            )
        }
    }
}
