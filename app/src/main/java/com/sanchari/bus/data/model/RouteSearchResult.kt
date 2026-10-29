package com.sanchari.bus.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

enum class RouteSearchMode {
    FIXED_ROUTE,
    DEPARTURES,
    ARRIVALS;

    companion object {
        fun fromStops(from: String, to: String): RouteSearchMode {
            require(from.isNotBlank() && to.isNotBlank()) { "Both stop fields are required." }
            require(from != "*" || to != "*") { "Only one stop can be a wildcard." }
            return when {
                from == "*" -> ARRIVALS
                to == "*" -> DEPARTURES
                else -> FIXED_ROUTE
            }
        }
    }
}

@Parcelize
data class RouteSearchResult(
    val service: BusService,
    val fromPlace: String,
    val toPlace: String,
    val fromRouteStopId: Int,
    val toRouteStopId: Int
) : Parcelable
