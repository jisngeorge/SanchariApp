package com.sanchari.bus.data.manager

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.location.Location
import com.sanchari.bus.data.local.DatabaseConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SuggestionManager(private val context: Context) {

    /**
     * Finds nearby stops and returns them as a formatted string ready for the popup.
     * Applies filtering to remove stops that do not offer any unique/new bus routes.
     */
    suspend fun getNearbySuggestionsText(
        fromLocation: String,
        toLocation: String
    ): String = withContext(Dispatchers.IO) {

        val dbFile = context.getDatabasePath(DatabaseConstants.TIMETABLE_DATABASE_NAME)
        if (!dbFile.exists()) {
            return@withContext "Database not found. Please ensure data is downloaded."
        }

        val db = SQLiteDatabase.openDatabase(
            dbFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        )

        // Updated data class to hold our new sorting metrics
        data class StopResult(
            val name: String,
            val distanceFromOriginalKm: Float, // Distance from Start (for source alts) or Dest (for dest alts)
            val distanceToOppositeKm: Float,   // Distance to the other end of the trip
            val totalServiceCount: Int,
            val newOptionCount: Int
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
                return@withContext "Could not find coordinates for $fromLocation."
            }
            if (destLat == 0.0 && destLng == 0.0) {
                return@withContext "Could not find coordinates for $toLocation."
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
            e.printStackTrace()
            return@withContext "Error calculating suggestions: ${e.message}"
        } finally {
            db.close()
        }

        // ----------------------------------------------------------------------
        // 6. Sort and Format the result
        // ----------------------------------------------------------------------

        // Sort primarily by how close it is to the original stop (ascending),
        // and secondarily by how many NEW bus services it provides (descending).
        val resultComparator = compareBy<StopResult> { it.distanceFromOriginalKm }
            .thenByDescending { it.newOptionCount }

        val stringBuilder = StringBuilder()

        stringBuilder.append("Try out these stops:\n\n")

        if (foundSourceStops.isNotEmpty() || foundDestStops.isNotEmpty()) {
            stringBuilder.append("Format: \n <Stop Name> [Dist. from Source | Dist. to Destination | Unique Buses Services | Total Buses Services]\n\n")
            stringBuilder.append("⚠️ Note: Distances are geometric (straight-line) and may not reflect actual road distances.\n\n\n")
        }

        if (foundSourceStops.isNotEmpty()) {
            stringBuilder.append("Alternative stops near $fromLocation:\n\n")
            foundSourceStops.sortedWith(resultComparator).forEachIndexed { index, stop ->
                // For source alternatives: original = source, opposite = destination
                val distFromSrc = String.format("%.1fkm", stop.distanceFromOriginalKm)
                val distToDest = String.format("%.1fkm", stop.distanceToOppositeKm)

                stringBuilder.append("${index + 1}. ${stop.name} [$distFromSrc | $distToDest | ${stop.newOptionCount} | ${stop.totalServiceCount}]\n")
            }
            stringBuilder.append("\n")
        }

        if (foundDestStops.isNotEmpty()) {
            stringBuilder.append("Alternative stops near $toLocation:\n\n")
            foundDestStops.sortedWith(resultComparator).forEachIndexed { index, stop ->
                // For destination alternatives: original = destination, opposite = source
                val distFromSrc = String.format("%.1fkm", stop.distanceToOppositeKm)
                val distToDest = String.format("%.1fkm", stop.distanceFromOriginalKm)

                stringBuilder.append("${index + 1}. ${stop.name} [$distFromSrc | $distToDest | ${stop.newOptionCount} | ${stop.totalServiceCount}]\n")
            }
        }

        if (stringBuilder.isEmpty()) {
            return@withContext "No useful alternative stops found.\nAll nearby buses already pass directly between $fromLocation and $toLocation."
        }

        return@withContext stringBuilder.toString().trimEnd()
    }
}