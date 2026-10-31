package com.sanchari.bus.data.manager

import com.sanchari.bus.data.model.EditableStop
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object MissingStopAnalysis {
    private const val MAX_DETOUR_RATIO = 1.5
    private const val DISTANCE_EPSILON_KM = 1e-9
    data class Stop(val id: Int, val name: String, val latitude: Double? = null, val longitude: Double? = null)
    data class Route(val serviceId: String, val type: String, val stops: List<Stop>)
    private data class EndpointPair(val fromId: Int, val toId: Int)
    data class Alternative(val stops: List<Stop>, val sameTypeCount: Int, val serviceCount: Int)
    data class Gap(val position: Int, val alternatives: List<Alternative>)

    fun analyse(stops: List<EditableStop>, routes: List<Route>, type: String, excludedService: String?): List<Gap> {
        val pairs = stops.zipWithNext().map { (from, to) ->
            if (from.originalStopId < 0 || to.originalStopId < 0 ||
                from.originalStopId == to.originalStopId || from.stopName == to.stopName) null
            else EndpointPair(from.originalStopId, to.originalStopId)
        }
        val candidates = pairs.filterNotNull().distinct().associateWith {
            linkedMapOf<List<Stop>, MutableMap<String, Boolean>>()
        }
        val pairsByEndpoint = candidates.keys.flatMap { pair ->
            listOf(pair.fromId to pair, pair.toId to pair)
        }.groupBy({ it.first }, { it.second })
        val typeKey = RouteResultsManager.busTypeKey(type)
        for (route in routes) {
            if (route.serviceId == excludedService) continue
            val visits = mutableListOf<MutableList<Stop>>()
            route.stops.forEach { stop ->
                if (visits.lastOrNull()?.first()?.name == stop.name) visits.last().add(stop)
                else visits.add(mutableListOf(stop))
            }
            val sameType = typeKey.isNotEmpty() && RouteResultsManager.busTypeKey(route.type) == typeKey
            val departures = mutableMapOf<EndpointPair, Int>()
            visits.forEachIndexed { index, visit ->
                val ids = visit.map { it.id }.toSet()
                val affected = ids.flatMap { pairsByEndpoint[it].orEmpty() }.distinct()
                for (pair in affected) {
                    when {
                        pair.fromId in ids -> departures[pair] = index
                        pair.toId in ids -> {
                            val departure = departures.remove(pair)
                            if (departure != null && index > departure + 1) {
                                val intermediate = visits.subList(departure + 1, index).map { it.first() }
                                candidates.getValue(pair).getOrPut(intermediate) {
                                    linkedMapOf()
                                }[route.serviceId] = sameType
                            }
                        }
                    }
                }
            }
        }
        val locations = routes.asSequence().flatMap { it.stops.asSequence() }.associateBy { it.id }
        val ranked = candidates.mapValues { (pair, sequences) ->
            // Check each distinct sequence once, rather than once per supporting service.
            sequences.filterKeys { sequence ->
                geographicallyPlausible(locations[pair.fromId], locations[pair.toId], sequence)
            }.map { (sequence, evidence) ->
                Alternative(sequence, evidence.values.count { it }, evidence.size)
            }.sortedWith(compareByDescending<Alternative> { it.sameTypeCount }
                .thenByDescending { it.serviceCount }
                .thenBy { it.stops.joinToString("\u0000") { stop -> "${stop.name}#${stop.id}" } })
        }
        return pairs.mapIndexedNotNull { index, pair ->
            ranked[pair]?.takeIf { it.isNotEmpty() }?.let { Gap(index, it) }
        }
    }

    private data class Point(val latitude: Double, val longitude: Double)

    private fun coordinates(stop: Stop?): Point? {
        val latitude = stop?.latitude ?: return null
        val longitude = stop.longitude ?: return null
        if (!latitude.isFinite() || !longitude.isFinite() ||
            latitude !in -90.0..90.0 || longitude !in -180.0..180.0 ||
            (latitude == 0.0 && longitude == 0.0)) return null
        return Point(latitude, longitude)
    }

    private fun distanceKm(from: Point, to: Point): Double {
        val latitudeDelta = Math.toRadians(to.latitude - from.latitude)
        val longitudeDelta = Math.toRadians(to.longitude - from.longitude)
        val a = sin(latitudeDelta / 2).let { it * it } +
            cos(Math.toRadians(from.latitude)) * cos(Math.toRadians(to.latitude)) *
            sin(longitudeDelta / 2).let { it * it }
        return 2 * 6371.0088 * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    private fun geographicallyPlausible(from: Stop?, to: Stop?, stops: List<Stop>): Boolean {
        val origin = coordinates(from) ?: return false
        val destination = coordinates(to) ?: return false
        val direct = distanceKm(origin, destination)
        if (direct <= DISTANCE_EPSILON_KM) return false
        val limit = direct * MAX_DETOUR_RATIO + DISTANCE_EPSILON_KM
        var previous = origin
        var distance = 0.0
        for (stop in stops) {
            val point = coordinates(stop) ?: return false
            distance += distanceKm(previous, point)
            if (distance > limit) return false
            previous = point
        }
        return distance + distanceKm(previous, destination) <= limit
    }

    fun insert(stops: List<EditableStop>, selections: Map<Int, List<Stop>>): List<EditableStop> =
        buildList {
            stops.forEachIndexed { index, stop ->
                add(stop.copy(stopOrder = size + 1))
                selections[index].orEmpty().forEach {
                    add(EditableStop(it.name, "", size + 1, it.id))
                }
            }
        }
}
