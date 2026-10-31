package com.sanchari.bus.ui.helper

import com.sanchari.bus.data.model.GeoPoint
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class MapNavigationHelperTest {
    @Test
    fun mapsUsesExplicitCoordinatesAndDrivingModeWithoutDeviceLocationOrGeocoding() {
        val from = GeoPoint(10.123456789, 76.234567891)
        val to = GeoPoint(-12.5, -77.25)
        assertEquals(
            "https://www.google.com/maps/dir/?api=1&origin=10.123456789,76.234567891" +
                "&destination=-12.5,-77.25&travelmode=driving",
            MapNavigationHelper.directionsUrl(from, to)
        )
        assertEquals("https://www.google.com/maps/search/?api=1&query=-12.5,-77.25",
            MapNavigationHelper.markerUrl(to))
    }

    @Test
    fun coordinateFormattingDoesNotUseTheDeviceDecimalSeparator() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertTrue(MapNavigationHelper.markerUrl(GeoPoint(10.25, 76.5)).endsWith("query=10.25,76.5"))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
