package com.sanchari.bus.data.model

import com.sanchari.bus.data.manager.StopLocationManager
import org.junit.Assert.*
import org.junit.Test

class StopLocationLogicTest {
    @Test
    fun pastedCoordinatePairsAcceptWhitespaceAndRequireExactlyTwoValidValues() {
        assertEquals(GeoPoint(-12.5, 76.75), GeoPoint.parse(" -12.5, 76.75 "))
        assertEquals(GeoPoint(0.0, 0.0), GeoPoint.parse("0,0"))
        assertEquals(GeoPoint(-90.0, 180.0), GeoPoint.parse("-90,180"))
        for (text in listOf("", "10 76", "10,", ",76", "10,76,20", "91,76", "0,181",
            "NaN,76", "10,Infinity", "north, east")) {
            assertNull(text, GeoPoint.parse(text))
        }
    }

    @Test
    fun coordinatesAcceptSignedDecimalDegreesAndBothRangeBoundaries() {
        assertEquals(GeoPoint(-12.5, 76.75), GeoPoint.parse(" -12.5 ", "76.75"))
        assertEquals(GeoPoint(0.0, 0.0), GeoPoint.parse("0", "0"))
        assertNotNull(GeoPoint.parse("-90", "-180"))
        assertNotNull(GeoPoint.parse("90", "180"))
    }

    @Test
    fun coordinatesRejectMalformedMissingNonFiniteAndOutOfRangeInput() {
        for ((lat, lon) in listOf("" to "76", "north" to "76", "91" to "76",
            "-90.1" to "0", "0" to "180.1", "NaN" to "0", "0" to "Infinity")) {
            assertNull(GeoPoint.parse(lat, lon))
        }
    }

    @Test
    fun radiusIsInclusiveAndOrderUsesUnroundedDistanceThenNameAndId() {
        fun nearby(id: Int, name: String, distance: Double) =
            NearbyStop(StopLocation(id, name, GeoPoint(10.0, 76.0)), distance)
        val results = StopLocationManager.rankNearby(listOf(
            nearby(1, "Boundary", 5_000.0), nearby(2, "Outside", 5_000.001),
            nearby(3, "B", 1.002), nearby(5, "A", 1.002), nearby(4, "A", 1.002),
            nearby(6, "Closest", 1.001), nearby(7, "Invalid", Double.NaN),
            nearby(8, "Invalid", -1.0)
        ))
        assertEquals(listOf(6, 4, 5, 3, 1), results.map { it.stop.stopId })
    }

    @Test
    fun adjustableRadiusAcceptsPositiveFractionalDistancesWithoutATwentyFiveKmCap() {
        assertEquals(5_000.0, StopLocationManager.DEFAULT_RADIUS_METERS, 0.0)
        assertEquals(150.0, StopLocationManager.parseRadiusMeters(" 0.15 ")!!, 0.0)
        assertEquals(40_000.0, StopLocationManager.parseRadiusMeters("40")!!, 0.0)
        val boundary = NearbyStop(StopLocation(1, "Farther", GeoPoint(10.0, 76.0)), 40_000.0)
        assertTrue(StopLocationManager.rankNearby(listOf(boundary)).isEmpty())
        assertEquals(listOf(boundary), StopLocationManager.rankNearby(listOf(boundary), 40_000.0))
        assertTrue(StopLocationManager.rankNearby(listOf(boundary), 39_999.999).isEmpty())
        for (text in listOf("", "0", "-1", "NaN", "Infinity", "abc", "1e309", "1e308")) {
            assertNull(text, StopLocationManager.parseRadiusMeters(text))
        }
    }

    @Test
    fun physicalStopIdentityAcceptsCaseAndOuterWhitespaceButNotEditsOrWildcards() {
        val selected = SelectedStopIdentity(42, "Palai")
        assertTrue(selected.matches(" pALAi "))
        assertFalse(selected.matches("Pal"))
        assertFalse(selected.matches("*"))
        assertFalse(selected.matches(""))
    }
}
