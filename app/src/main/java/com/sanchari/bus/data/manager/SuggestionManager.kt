package com.sanchari.bus.data.manager

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.location.Location
import android.util.Log
import androidx.core.text.bold
import androidx.core.text.buildSpannedString
import com.sanchari.bus.R
import com.sanchari.bus.data.local.DatabaseConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SuggestionManager(private val context: Context) {

    internal data class StopResult(
        val name: String,
        val distanceFromOriginalKm: Float,
        val distanceToOppositeKm: Float,
        val totalServiceCount: Int,
        val newOptionCount: Int
    )

    /**
     * Finds nearby stops and returns them as a formatted string ready for the popup.
     * Applies filtering to remove stops that do not offer any unique/new bus routes.
     */
    suspend fun getNearbySuggestionsText(
        fromLocation: String,
        toLocation: String
    ): CharSequence = withContext(Dispatchers.IO) {

        val dbFile = context.getDatabasePath(DatabaseConstants.TIMETABLE_DATABASE_NAME)
        if (!dbFile.exists()) {
            return@withContext context.getString(R.string.nearby_database_missing)
        }

        val db = SQLiteDatabase.openDatabase(
            dbFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        )

        val foundSourceStops = mutableListOf<StopResult>()
        val foundDestStops = mutableListOf<StopResult>()

        try {
            // 1. Get Lat/Lng of the SOURCE stop
            var sourceLat = 0.0
            var sourceLng = 0.0
            val sourceCursor = db.rawQuery(
                "SELECT latitude, longitude FROM Stop WHERE locationName = ? LIMIT 1",
                arrayOf(fromLocation)
            )

            if (sourceCursor.moveToFirst()) {
                sourceLat = sourceCursor.getDouble(0)
                sourceLng = sourceCursor.getDouble(1)
            }
            sourceCursor.close()

            // 1b. Get Lat/Lng of the DESTINATION stop
            var destLat = 0.0
            var destLng = 0.0
            val destCursor = db.rawQuery(
                "SELECT latitude, longitude FROM Stop WHERE locationName = ? LIMIT 1",
                arrayOf(toLocation)
            )

            if (destCursor.moveToFirst()) {
                destLat = destCursor.getDouble(0)
                destLng = destCursor.getDouble(1)
            }
            destCursor.close()

            if (sourceLat == 0.0 && sourceLng == 0.0) {
                return@withContext context.getString(R.string.nearby_coordinates_missing, fromLocation)
            }
            if (destLat == 0.0 && destLng == 0.0) {
                return@withContext context.getString(R.string.nearby_coordinates_missing, toLocation)
            }

            // 2. Calculate dynamic bounds based on actual trip distance
            val distanceResults = FloatArray(1)
            Location.distanceBetween(sourceLat, sourceLng, destLat, destLng, distanceResults)
            val sourceToDestDistanceKm = distanceResults[0] / 1000.0f

            // Enforce a minimum radius (e.g., 5km) so short trips still get suggestions
            val dynamicRadiusKm = maxOf(sourceToDestDistanceKm, 5.0f)
            val maxAllowedTripDistanceKm = sourceToDestDistanceKm * 1.5f

            // 3. Get DIRECT services from fromLocation to toLocation
            val directServiceIds = mutableSetOf<String>()
            val directQuery = """
                SELECT rs1.serviceId
                FROM RouteStop rs1
                INNER JOIN Stop s1 ON rs1.stopId = s1.stopId
                INNER JOIN RouteStop rs2 ON rs1.serviceId = rs2.serviceId
                INNER JOIN Stop s2 ON rs2.stopId = s2.stopId
                WHERE s1.locationName = ? 
                  AND s2.locationName = ?
                  AND rs1.stopOrder < rs2.stopOrder
            """.trimIndent()

            val directCursor = db.rawQuery(directQuery, arrayOf(fromLocation, toLocation))
            while (directCursor.moveToNext()) {
                directServiceIds.add(directCursor.getString(0))
            }
            directCursor.close()

            // Helper class to cache distances so we don't recalculate for every bus at the same stop
            data class CachedDistances(val distToSource: Float, val distToDest: Float)

            // ----------------------------------------------------------------------
            // 4. Find candidate SOURCE stops (Alternatives near Start)
            // ----------------------------------------------------------------------
            val sourceCandidateQuery = """
                SELECT s1.locationName, s1.latitude, s1.longitude, rs1.serviceId
                FROM RouteStop rs1
                INNER JOIN Stop s1 ON rs1.stopId = s1.stopId
                INNER JOIN RouteStop rs2 ON rs1.serviceId = rs2.serviceId
                INNER JOIN Stop s2 ON rs2.stopId = s2.stopId
                WHERE s2.locationName = ? 
                  AND rs1.stopOrder < rs2.stopOrder
                  AND s1.locationName != ?
            """.trimIndent()

            val sourceCandidateCursor = db.rawQuery(sourceCandidateQuery, arrayOf(toLocation, fromLocation))

            val sourceDistCache = mutableMapOf<String, CachedDistances>()
            val validSourceMap = mutableMapOf<String, MutableSet<String>>()

            while (sourceCandidateCursor.moveToNext()) {
                val candidateName = sourceCandidateCursor.getString(0)
                val candidateLat = sourceCandidateCursor.getDouble(1)
                val candidateLng = sourceCandidateCursor.getDouble(2)
                val serviceId = sourceCandidateCursor.getString(3)

                val distances = sourceDistCache.getOrPut(candidateName) {
                    Location.distanceBetween(candidateLat, candidateLng, sourceLat, sourceLng, distanceResults)
                    val distToSource = distanceResults[0] / 1000.0f

                    Location.distanceBetween(candidateLat, candidateLng, destLat, destLng, distanceResults)
                    val distToDest = distanceResults[0] / 1000.0f

                    CachedDistances(distToSource, distToDest)
                }

                // Rule 1: Alternate stop must be within radius (trip distance) of the original start
                // Rule 2: Distance from alternate stop to destination must be <= 1.5x original trip distance
                if (distances.distToSource <= dynamicRadiusKm && distances.distToDest <= maxAllowedTripDistanceKm) {
                    val services = validSourceMap.getOrPut(candidateName) { mutableSetOf() }
                    services.add(serviceId)
                }
            }
            sourceCandidateCursor.close()

            for ((candidateName, candidateServices) in validSourceMap) {
                val newOptionCount = candidateServices.count { it !in directServiceIds }
                if (newOptionCount > 0) {
                    val cache = sourceDistCache[candidateName]!!
                    foundSourceStops.add(StopResult(
                        name = candidateName,
                        distanceFromOriginalKm = cache.distToSource,
                        distanceToOppositeKm = cache.distToDest,
                        totalServiceCount = candidateServices.size,
                        newOptionCount = newOptionCount
                    ))
                }
            }

            // ----------------------------------------------------------------------
            // 5. Find candidate DESTINATION stops (Alternatives near Destination)
            // ----------------------------------------------------------------------
            val destCandidateQuery = """
                SELECT s2.locationName, s2.latitude, s2.longitude, rs2.serviceId
                FROM RouteStop rs1
                INNER JOIN Stop s1 ON rs1.stopId = s1.stopId
                INNER JOIN RouteStop rs2 ON rs1.serviceId = rs2.serviceId
                INNER JOIN Stop s2 ON rs2.stopId = s2.stopId
                WHERE s1.locationName = ? 
                  AND rs1.stopOrder < rs2.stopOrder
                  AND s2.locationName != ?
            """.trimIndent()

            val destCandidateCursor = db.rawQuery(destCandidateQuery, arrayOf(fromLocation, toLocation))

            val destDistCache = mutableMapOf<String, CachedDistances>()
            val validDestMap = mutableMapOf<String, MutableSet<String>>()

            while (destCandidateCursor.moveToNext()) {
                val candidateName = destCandidateCursor.getString(0)
                val candidateLat = destCandidateCursor.getDouble(1)
                val candidateLng = destCandidateCursor.getDouble(2)
                val serviceId = destCandidateCursor.getString(3)

                val distances = destDistCache.getOrPut(candidateName) {
                    Location.distanceBetween(candidateLat, candidateLng, sourceLat, sourceLng, distanceResults)
                    val distToSource = distanceResults[0] / 1000.0f

                    Location.distanceBetween(candidateLat, candidateLng, destLat, destLng, distanceResults)
                    val distToDest = distanceResults[0] / 1000.0f

                    CachedDistances(distToSource, distToDest)
                }

                // Rule 1: Alternate stop must be within radius (trip distance) of the original destination
                // Rule 2: Distance from start to alternate destination must be <= 1.5x original trip distance
                if (distances.distToDest <= dynamicRadiusKm && distances.distToSource <= maxAllowedTripDistanceKm) {
                    val services = validDestMap.getOrPut(candidateName) { mutableSetOf() }
                    services.add(serviceId)
                }
            }
            destCandidateCursor.close()

            for ((candidateName, candidateServices) in validDestMap) {
                val newOptionCount = candidateServices.count { it !in directServiceIds }
                if (newOptionCount > 0) {
                    val cache = destDistCache[candidateName]!!
                    foundDestStops.add(StopResult(
                        name = candidateName,
                        distanceFromOriginalKm = cache.distToDest,
                        distanceToOppositeKm = cache.distToSource,
                        totalServiceCount = candidateServices.size,
                        newOptionCount = newOptionCount
                    ))
                }
            }

        } catch (e: Exception) {
            Log.e("SuggestionManager", "Error calculating alternative stops", e)
            return@withContext context.getString(R.string.nearby_suggestions_error)
        } finally {
            db.close()
        }

        formatSuggestions(fromLocation, toLocation, foundSourceStops, foundDestStops)
    }

    internal fun formatSuggestions(
        fromLocation: String,
        toLocation: String,
        sourceStops: List<StopResult>,
        destinationStops: List<StopResult>
    ): CharSequence {
        if (sourceStops.isEmpty() && destinationStops.isEmpty()) {
            return context.getString(R.string.nearby_no_suggestions, fromLocation, toLocation)
        }
        val resultComparator = compareBy<StopResult> { it.distanceFromOriginalKm }
            .thenByDescending { it.newOptionCount }

        return buildSpannedString {
            append(context.getString(R.string.nearby_distance_note))
            append("\n\n")
            for ((isSource, stops) in listOf(true to sourceStops, false to destinationStops)) {
                if (stops.isEmpty()) continue
                bold {
                    append(context.getString(
                        if (isSource) R.string.nearby_boarding_heading else R.string.nearby_alighting_heading,
                        if (isSource) fromLocation else toLocation
                    ))
                }
                append("\n\n")
                stops.sortedWith(resultComparator).forEachIndexed { index, stop ->
                    bold { append(context.getString(R.string.nearby_stop_name, index + 1, stop.name)) }
                    append("\n")
                    append(context.getString(
                        R.string.nearby_stop_distances,
                        fromLocation,
                        if (isSource) stop.distanceFromOriginalKm else stop.distanceToOppositeKm,
                        toLocation,
                        if (isSource) stop.distanceToOppositeKm else stop.distanceFromOriginalKm
                    ))
                    append("\n")
                    append(context.getString(
                        R.string.nearby_stop_services, stop.newOptionCount, stop.totalServiceCount
                    ))
                    append("\n\n")
                }
            }
            append(context.getString(R.string.nearby_additional_note, fromLocation, toLocation))
        }
    }
}