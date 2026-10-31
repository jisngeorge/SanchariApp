package com.sanchari.bus.data.model

data class GeoPoint(val latitude: Double, val longitude: Double) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
    }

    companion object {
        fun parse(coordinates: String): GeoPoint? {
            val parts = coordinates.split(',')
            return if (parts.size == 2) parse(parts[0], parts[1]) else null
        }

        fun parse(latitude: String, longitude: String): GeoPoint? {
            val lat = latitude.trim().toDoubleOrNull() ?: return null
            val lon = longitude.trim().toDoubleOrNull() ?: return null
            return fromValues(lat, lon)
        }

        fun fromValues(latitude: Double, longitude: Double): GeoPoint? =
            if (latitude.isFinite() && latitude in -90.0..90.0 &&
                longitude.isFinite() && longitude in -180.0..180.0) {
                GeoPoint(latitude, longitude)
            } else null
    }
}

data class StopLocation(val stopId: Int, val name: String, val point: GeoPoint)

data class NearbyStop(val stop: StopLocation, val distanceMeters: Double)

data class SelectedStopIdentity(val stopId: Int, val name: String) {
    fun matches(text: String): Boolean = name.equals(text.trim(), ignoreCase = true)
}
