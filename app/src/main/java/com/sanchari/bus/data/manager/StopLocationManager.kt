package com.sanchari.bus.data.manager

import android.content.Context
import android.location.Location
import com.sanchari.bus.data.model.GeoPoint
import com.sanchari.bus.data.model.NearbyStop
import com.sanchari.bus.data.model.StopLocation

class StopLocationManager(private val context: Context) {
    fun nearbyStops(center: GeoPoint, radiusMeters: Double = DEFAULT_RADIUS_METERS): List<NearbyStop> {
        require(radiusMeters.isFinite() && radiusMeters > 0)
        val distances = FloatArray(1)
        return rankNearby(SearchManager.getStopLocations(context).map { stop ->
            Location.distanceBetween(
                center.latitude, center.longitude, stop.point.latitude, stop.point.longitude, distances
            )
            NearbyStop(stop, distances[0].toDouble())
        }, radiusMeters)
    }

    fun locationsForName(name: String): List<StopLocation> {
        val matches = SearchManager.getStopLocations(context).filter {
            it.name.equals(name.trim(), ignoreCase = true)
        }
        return matches.filter { it.name == name.trim() }.ifEmpty { matches }
    }

    fun locationById(id: Int): StopLocation? = SearchManager.getStopLocationById(context, id)

    companion object {
        const val DEFAULT_RADIUS_KM = 5
        const val DEFAULT_RADIUS_METERS = DEFAULT_RADIUS_KM * 1000.0

        fun parseRadiusMeters(kilometers: String): Double? =
            kilometers.trim().toDoubleOrNull()?.times(1000)?.takeIf { it.isFinite() && it > 0 }

        fun rankNearby(stops: List<NearbyStop>, radiusMeters: Double = DEFAULT_RADIUS_METERS): List<NearbyStop> {
            require(radiusMeters.isFinite() && radiusMeters > 0)
            return stops.filter { it.distanceMeters.isFinite() && it.distanceMeters in 0.0..radiusMeters }
                .sortedWith(compareBy<NearbyStop> { it.distanceMeters }
                    .thenBy { it.stop.name }.thenBy { it.stop.stopId })
        }
    }
}
