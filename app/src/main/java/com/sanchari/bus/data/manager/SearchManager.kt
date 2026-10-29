package com.sanchari.bus.data.manager

import android.content.Context
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.sanchari.bus.data.local.DatabaseConstants
import com.sanchari.bus.data.local.TimetableDatabaseHelper
import com.sanchari.bus.data.model.BusService
import com.sanchari.bus.data.model.BusStop
import com.sanchari.bus.data.model.EditorStopOption
import com.sanchari.bus.data.model.RouteSearchMode
import com.sanchari.bus.data.model.RouteSearchResult
import java.util.Locale

object SearchManager {

    private const val TAG = "SearchManager"

    private fun db(context: Context) =
        TimetableDatabaseHelper.getInstance(context).readableDatabase

    private fun editorDb(context: Context): SQLiteDatabase {
        if (!context.getDatabasePath(DatabaseConstants.TIMETABLE_DATABASE_NAME).isFile) {
            throw SQLiteException("Timetable database has not been installed")
        }
        return db(context)
    }

    fun getEditorStopOptions(context: Context): List<EditorStopOption> = getEditorStopOptions(editorDb(context))

    internal fun getEditorStopOptions(database: SQLiteDatabase): List<EditorStopOption> {
        val stop = DatabaseConstants.StopTable
        val options = mutableListOf<EditorStopOption>()
        database.rawQuery(
            "SELECT ${stop.COLUMN_STOP_ID}, ${stop.COLUMN_LOCATION_NAME} FROM ${stop.TABLE_NAME} " +
                "ORDER BY ${stop.COLUMN_LOCATION_NAME}, ${stop.COLUMN_STOP_ID}", null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                options.add(EditorStopOption(cursor.getInt(0), cursor.getString(1)))
            }
        }
        val duplicateNames = options.groupingBy { it.name.trim().uppercase(Locale.ROOT) }
            .eachCount().filterValues { it > 1 }.keys
        return options.map {
            if (it.name.trim().uppercase(Locale.ROOT) in duplicateNames) {
                it.copy(label = "${it.name} (#${it.id})")
            } else it
        }
    }

    fun getEditorBusTypes(context: Context): List<String> = getEditorBusTypes(editorDb(context))

    internal fun getEditorBusTypes(database: SQLiteDatabase): List<String> {
        val bus = DatabaseConstants.BusServiceTable
        val types = mutableListOf<String>()
        database.rawQuery(
            "SELECT DISTINCT ${bus.COLUMN_TYPE} FROM ${bus.TABLE_NAME} " +
                "WHERE ${bus.COLUMN_TYPE} IS NOT NULL ORDER BY ${bus.COLUMN_TYPE}", null
        ).use { cursor ->
            while (cursor.moveToNext()) cursor.getString(0).trim().takeIf { it.isNotEmpty() }?.let(types::add)
        }
        return types.distinctBy(RouteResultsManager::busTypeKey)
    }

    // Editor callers distinguish unavailable data from a valid empty result.
    fun getEditorRouteResults(context: Context, from: String, to: String): List<RouteSearchResult> =
        findFixedRouteResults(editorDb(context), from, to)

    /**
     * Finds bus services that travel from 'from' to 'to'.
     */
    fun findBusServices(context: Context, from: String, to: String): List<BusService> {
        return findRouteResults(context, from, to).map { it.service }
    }

    fun findRouteResults(context: Context, from: String, to: String): List<RouteSearchResult> {
        return try {
            findRouteResults(db(context), from, to)
        } catch (e: SQLiteException) {
            Log.e(TAG, "Error finding bus services", e)
            emptyList()
        }
    }

    internal fun findRouteResults(db: SQLiteDatabase, from: String, to: String): List<RouteSearchResult> {
        val mode = RouteSearchMode.fromStops(from, to)
        val results = if (mode == RouteSearchMode.FIXED_ROUTE) {
            findFixedRouteResults(db, from, to)
        } else {
            val incoming = mode == RouteSearchMode.ARRIVALS
            findWildcardRouteResults(db, if (incoming) to else from, incoming)
        }
        Log.i(TAG, "Found ${results.size} services from '$from' to '$to'")
        return results
    }

    private fun findFixedRouteResults(db: SQLiteDatabase, from: String, to: String): List<RouteSearchResult> {
        val results = mutableListOf<RouteSearchResult>()

        val fixedRouteQuery = """
            SELECT 
                bs.${DatabaseConstants.BusServiceTable.COLUMN_SERVICE_ID}, 
                bs.${DatabaseConstants.BusServiceTable.COLUMN_NAME}, 
                bs.${DatabaseConstants.BusServiceTable.COLUMN_TYPE}, 
                bs.${DatabaseConstants.BusServiceTable.COLUMN_IS_RUNNING},
                bs.${DatabaseConstants.BusServiceTable.COLUMN_LAST_REPORTED_TIME},
                rs_from.${DatabaseConstants.RouteStopTable.COLUMN_SCHEDULED_TIME} AS fromTime,
                rs_to.${DatabaseConstants.RouteStopTable.COLUMN_SCHEDULED_TIME} AS toTime,
                s_from.${DatabaseConstants.StopTable.COLUMN_LOCATION_NAME} AS fromPlace,
                s_to.${DatabaseConstants.StopTable.COLUMN_LOCATION_NAME} AS toPlace,
                rs_from.${DatabaseConstants.RouteStopTable.COLUMN_ROUTE_STOP_ID} AS fromRouteStopId,
                rs_to.${DatabaseConstants.RouteStopTable.COLUMN_ROUTE_STOP_ID} AS toRouteStopId
            FROM 
                ${DatabaseConstants.RouteStopTable.TABLE_NAME} AS rs_from
            INNER JOIN
                ${DatabaseConstants.StopTable.TABLE_NAME} AS s_from ON rs_from.${DatabaseConstants.RouteStopTable.COLUMN_STOP_ID} = s_from.${DatabaseConstants.StopTable.COLUMN_STOP_ID}
            INNER JOIN 
                ${DatabaseConstants.RouteStopTable.TABLE_NAME} AS rs_to ON rs_from.${DatabaseConstants.RouteStopTable.COLUMN_SERVICE_ID} = rs_to.${DatabaseConstants.RouteStopTable.COLUMN_SERVICE_ID}
            INNER JOIN
                ${DatabaseConstants.StopTable.TABLE_NAME} AS s_to ON rs_to.${DatabaseConstants.RouteStopTable.COLUMN_STOP_ID} = s_to.${DatabaseConstants.StopTable.COLUMN_STOP_ID}
            INNER JOIN
                ${DatabaseConstants.BusServiceTable.TABLE_NAME} AS bs ON rs_from.${DatabaseConstants.RouteStopTable.COLUMN_SERVICE_ID} = bs.${DatabaseConstants.BusServiceTable.COLUMN_SERVICE_ID}
            WHERE 
                s_from.${DatabaseConstants.StopTable.COLUMN_LOCATION_NAME} = ?
                AND s_to.${DatabaseConstants.StopTable.COLUMN_LOCATION_NAME} = ?
                AND rs_from.${DatabaseConstants.RouteStopTable.COLUMN_STOP_ORDER} < rs_to.${DatabaseConstants.RouteStopTable.COLUMN_STOP_ORDER}
                AND NOT EXISTS (
                    SELECT 1
                    FROM ${DatabaseConstants.RouteStopTable.TABLE_NAME} AS rs_mid
                    INNER JOIN ${DatabaseConstants.StopTable.TABLE_NAME} AS s_mid ON rs_mid.${DatabaseConstants.RouteStopTable.COLUMN_STOP_ID} = s_mid.${DatabaseConstants.StopTable.COLUMN_STOP_ID}
                    WHERE rs_mid.${DatabaseConstants.RouteStopTable.COLUMN_SERVICE_ID} = rs_from.${DatabaseConstants.RouteStopTable.COLUMN_SERVICE_ID}
                      AND s_mid.${DatabaseConstants.StopTable.COLUMN_LOCATION_NAME} = ?
                      AND rs_mid.${DatabaseConstants.RouteStopTable.COLUMN_STOP_ORDER} > rs_from.${DatabaseConstants.RouteStopTable.COLUMN_STOP_ORDER}
                      AND rs_mid.${DatabaseConstants.RouteStopTable.COLUMN_STOP_ORDER} < rs_to.${DatabaseConstants.RouteStopTable.COLUMN_STOP_ORDER}
                )
                AND NOT EXISTS (
                    SELECT 1
                    FROM ${DatabaseConstants.RouteStopTable.TABLE_NAME} AS rs_mid
                    INNER JOIN ${DatabaseConstants.StopTable.TABLE_NAME} AS s_mid ON rs_mid.${DatabaseConstants.RouteStopTable.COLUMN_STOP_ID} = s_mid.${DatabaseConstants.StopTable.COLUMN_STOP_ID}
                    WHERE rs_mid.${DatabaseConstants.RouteStopTable.COLUMN_SERVICE_ID} = rs_from.${DatabaseConstants.RouteStopTable.COLUMN_SERVICE_ID}
                      AND s_mid.${DatabaseConstants.StopTable.COLUMN_LOCATION_NAME} = ?
                      AND rs_mid.${DatabaseConstants.RouteStopTable.COLUMN_STOP_ORDER} > rs_from.${DatabaseConstants.RouteStopTable.COLUMN_STOP_ORDER}
                      AND rs_mid.${DatabaseConstants.RouteStopTable.COLUMN_STOP_ORDER} < rs_to.${DatabaseConstants.RouteStopTable.COLUMN_STOP_ORDER}
                )
            ORDER BY
                fromTime ASC;
        """.trimIndent()

        db.rawQuery(fixedRouteQuery, arrayOf(from, to, from, to)).use { cursor ->
            val serviceIdIndex = cursor.getColumnIndexOrThrow(DatabaseConstants.BusServiceTable.COLUMN_SERVICE_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DatabaseConstants.BusServiceTable.COLUMN_NAME)
            val typeIndex = cursor.getColumnIndexOrThrow(DatabaseConstants.BusServiceTable.COLUMN_TYPE)
            val fromTimeIndex = cursor.getColumnIndexOrThrow("fromTime")
            val toTimeIndex = cursor.getColumnIndexOrThrow("toTime")
            val isRunningIndex = cursor.getColumnIndexOrThrow(DatabaseConstants.BusServiceTable.COLUMN_IS_RUNNING)
            val lastReportedTimeIndex = cursor.getColumnIndexOrThrow(DatabaseConstants.BusServiceTable.COLUMN_LAST_REPORTED_TIME)
            val fromPlaceIndex = cursor.getColumnIndexOrThrow("fromPlace")
            val toPlaceIndex = cursor.getColumnIndexOrThrow("toPlace")
            val fromRouteStopIdIndex = cursor.getColumnIndexOrThrow("fromRouteStopId")
            val toRouteStopIdIndex = cursor.getColumnIndexOrThrow("toRouteStopId")

            while (cursor.moveToNext()) {
                results.add(
                    RouteSearchResult(
                        service = BusService(
                            serviceId = cursor.getString(serviceIdIndex),
                            name = cursor.getString(nameIndex),
                            type = cursor.getString(typeIndex),
                            isRunning = cursor.getInt(isRunningIndex) == 1,
                            lastReportedTime = cursor.getLong(lastReportedTimeIndex),
                            fromTime = if (cursor.isNull(fromTimeIndex)) "--:--" else cursor.getString(fromTimeIndex),
                            toTime = if (cursor.isNull(toTimeIndex)) "--:--" else cursor.getString(toTimeIndex)
                        ),
                        fromPlace = cursor.getString(fromPlaceIndex),
                        toPlace = cursor.getString(toPlaceIndex),
                        fromRouteStopId = cursor.getInt(fromRouteStopIdIndex),
                        toRouteStopId = cursor.getInt(toRouteStopIdIndex)
                    )
                )
            }
        }

        return results
    }

    private data class WildcardStop(val rowId: Int, val time: String?)

    private data class WildcardVisit(
        val place: String,
        val first: WildcardStop,
        var last: WildcardStop
    )

    private fun findWildcardRouteResults(
        db: SQLiteDatabase,
        place: String,
        incoming: Boolean
    ): List<RouteSearchResult> {
        val route = DatabaseConstants.RouteStopTable
        val stop = DatabaseConstants.StopTable
        val bus = DatabaseConstants.BusServiceTable
        val query = """
            SELECT bs.${bus.COLUMN_SERVICE_ID}, bs.${bus.COLUMN_NAME}, bs.${bus.COLUMN_TYPE},
                   bs.${bus.COLUMN_IS_RUNNING}, bs.${bus.COLUMN_LAST_REPORTED_TIME},
                   rs.${route.COLUMN_ROUTE_STOP_ID}, rs.${route.COLUMN_SCHEDULED_TIME},
                   s.${stop.COLUMN_LOCATION_NAME}
            FROM ${route.TABLE_NAME} AS rs
            JOIN ${stop.TABLE_NAME} AS s
              ON s.${stop.COLUMN_STOP_ID} = rs.${route.COLUMN_STOP_ID}
            JOIN ${bus.TABLE_NAME} AS bs
              ON bs.${bus.COLUMN_SERVICE_ID} = rs.${route.COLUMN_SERVICE_ID}
            WHERE rs.${route.COLUMN_SERVICE_ID} IN (
                SELECT matched.${route.COLUMN_SERVICE_ID}
                FROM ${route.TABLE_NAME} AS matched
                JOIN ${stop.TABLE_NAME} AS matched_stop
                  ON matched_stop.${stop.COLUMN_STOP_ID} = matched.${route.COLUMN_STOP_ID}
                WHERE matched_stop.${stop.COLUMN_LOCATION_NAME} = ?
            )
            ORDER BY rs.${route.COLUMN_SERVICE_ID}, rs.${route.COLUMN_STOP_ORDER},
                     rs.${route.COLUMN_ROUTE_STOP_ID}
        """.trimIndent()
        val matches = mutableListOf<Pair<String?, RouteSearchResult>>()
        val visits = mutableListOf<WildcardVisit>()
        var service: BusService? = null

        fun collectLegs() {
            val currentService = service ?: return
            val ordered = if (incoming) visits.asReversed() else visits
            val seen = HashSet<String>()
            var end = 0
            // A sliding unique-place window finds every leg boundary in linear time.
            for (start in ordered.indices) {
                while (end < ordered.size && seen.add(ordered[end].place)) end++
                val matched = ordered[start]
                if (matched.place == place && end > start + 1) {
                    val terminal = ordered[end - 1]
                    val from = if (incoming) terminal else matched
                    val to = if (incoming) matched else terminal
                    matches.add(
                        (if (incoming) to.first.time else from.last.time) to RouteSearchResult(
                            service = currentService.copy(
                                fromTime = from.last.time ?: "--:--",
                                toTime = to.first.time ?: "--:--"
                            ),
                            fromPlace = from.place,
                            toPlace = to.place,
                            fromRouteStopId = from.last.rowId,
                            toRouteStopId = to.first.rowId
                        )
                    )
                }
                seen.remove(matched.place)
            }
        }

        db.rawQuery(query, arrayOf(place)).use { cursor ->
            val serviceIdIndex = cursor.getColumnIndexOrThrow(bus.COLUMN_SERVICE_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(bus.COLUMN_NAME)
            val typeIndex = cursor.getColumnIndexOrThrow(bus.COLUMN_TYPE)
            val runningIndex = cursor.getColumnIndexOrThrow(bus.COLUMN_IS_RUNNING)
            val reportedIndex = cursor.getColumnIndexOrThrow(bus.COLUMN_LAST_REPORTED_TIME)
            val rowIdIndex = cursor.getColumnIndexOrThrow(route.COLUMN_ROUTE_STOP_ID)
            val timeIndex = cursor.getColumnIndexOrThrow(route.COLUMN_SCHEDULED_TIME)
            val placeIndex = cursor.getColumnIndexOrThrow(stop.COLUMN_LOCATION_NAME)
            while (cursor.moveToNext()) {
                val serviceId = cursor.getString(serviceIdIndex)
                if (service?.serviceId != serviceId) {
                    collectLegs()
                    visits.clear()
                    service = BusService(
                        serviceId, cursor.getString(nameIndex), cursor.getString(typeIndex),
                        cursor.getInt(runningIndex) == 1, cursor.getLong(reportedIndex), "--:--", "--:--"
                    )
                }
                val row = WildcardStop(
                    cursor.getInt(rowIdIndex),
                    if (cursor.isNull(timeIndex)) null else cursor.getString(timeIndex)
                )
                val rowPlace = cursor.getString(placeIndex)
                val previous = visits.lastOrNull()
                if (previous != null && previous.place == rowPlace) {
                    previous.last = row
                } else {
                    visits.add(WildcardVisit(rowPlace, row, row))
                }
            }
            collectLegs()
        }
        return matches.sortedWith(
            compareBy<Pair<String?, RouteSearchResult>> { it.first }
                .thenBy { if (incoming) it.second.toRouteStopId else it.second.fromRouteStopId }
        ).map { it.second }
    }

    /**
     * Retrieves a single BusService object by its ID.
     */
    fun getBusServiceById(context: Context, serviceId: String): BusService? {
        val db = db(context)
        var busService: BusService? = null

        val query = "SELECT * FROM ${DatabaseConstants.BusServiceTable.TABLE_NAME} WHERE ${DatabaseConstants.BusServiceTable.COLUMN_SERVICE_ID} = ? LIMIT 1"

        try {
            db.rawQuery(query, arrayOf(serviceId)).use { cursor ->
                if (cursor.moveToFirst()) {
                    val serviceIdIndex = cursor.getColumnIndex(DatabaseConstants.BusServiceTable.COLUMN_SERVICE_ID)
                    val nameIndex = cursor.getColumnIndex(DatabaseConstants.BusServiceTable.COLUMN_NAME)
                    val typeIndex = cursor.getColumnIndex(DatabaseConstants.BusServiceTable.COLUMN_TYPE)
                    val isRunningIndex = cursor.getColumnIndex(DatabaseConstants.BusServiceTable.COLUMN_IS_RUNNING)
                    val lastReportedTimeIndex = cursor.getColumnIndex(DatabaseConstants.BusServiceTable.COLUMN_LAST_REPORTED_TIME)

                    busService = BusService(
                        serviceId = if (serviceIdIndex != -1) cursor.getString(serviceIdIndex) else "",
                        name = if (nameIndex != -1) cursor.getString(nameIndex) else "Unknown",
                        type = if (typeIndex != -1) cursor.getString(typeIndex) else "Unknown",
                        isRunning = if (isRunningIndex != -1) cursor.getInt(isRunningIndex) == 1 else true,
                        lastReportedTime = if (lastReportedTimeIndex != -1) cursor.getLong(lastReportedTimeIndex) else 0L,
                        fromTime = "--:--",
                        toTime = "--:--"
                    )
                }
            }
        } catch (e: SQLiteException) {
            Log.e(TAG, "Error getting bus service by ID", e)
        }
        return busService
    }

    /**
     * Searches for buses by name (partial match).
     */
    fun searchBusServicesByName(context: Context, query: String): List<BusService> {
        val results = mutableListOf<BusService>()
        val db = db(context)

        val sql = """
            SELECT * FROM ${DatabaseConstants.BusServiceTable.TABLE_NAME}
            WHERE ${DatabaseConstants.BusServiceTable.COLUMN_NAME} LIKE ?
            LIMIT 20
        """.trimIndent()

        try {
            db.rawQuery(sql, arrayOf("%$query%")).use { cursor ->
                val serviceIdIndex = cursor.getColumnIndex(DatabaseConstants.BusServiceTable.COLUMN_SERVICE_ID)
                val nameIndex = cursor.getColumnIndex(DatabaseConstants.BusServiceTable.COLUMN_NAME)
                val typeIndex = cursor.getColumnIndex(DatabaseConstants.BusServiceTable.COLUMN_TYPE)
                val isRunningIndex = cursor.getColumnIndex(DatabaseConstants.BusServiceTable.COLUMN_IS_RUNNING)
                val lastReportedTimeIndex = cursor.getColumnIndex(DatabaseConstants.BusServiceTable.COLUMN_LAST_REPORTED_TIME)

                while (cursor.moveToNext()) {
                    results.add(
                        BusService(
                            serviceId = if (serviceIdIndex != -1) cursor.getString(serviceIdIndex) else "",
                            name = if (nameIndex != -1) cursor.getString(nameIndex) else "Unknown",
                            type = if (typeIndex != -1) cursor.getString(typeIndex) else "Unknown",
                            isRunning = if (isRunningIndex != -1) cursor.getInt(isRunningIndex) == 1 else true,
                            lastReportedTime = if (lastReportedTimeIndex != -1) cursor.getLong(lastReportedTimeIndex) else 0L,
                            fromTime = "--:--",
                            toTime = "--:--"
                        )
                    )
                }
            }
        } catch (e: SQLiteException) {
            Log.e(TAG, "Error searching buses by name", e)
        }
        return results
    }

    /**
     * Gets all stops for a specific bus service, ordered by stopOrder.
     */
    fun getBusStops(context: Context, serviceId: String): List<BusStop> {
        return try {
            getEditorBusStops(context, serviceId)
        } catch (e: SQLiteException) {
            Log.e(TAG, "Error getting bus stops", e)
            emptyList()
        }
    }

    fun getEditorBusStops(context: Context, serviceId: String): List<BusStop> {
        val stops = mutableListOf<BusStop>()
        val db = editorDb(context)

        val query = """
            SELECT 
                s.${DatabaseConstants.StopTable.COLUMN_STOP_ID},
                s.${DatabaseConstants.StopTable.COLUMN_LOCATION_NAME},
                s.${DatabaseConstants.StopTable.COLUMN_LATITUDE},
                s.${DatabaseConstants.StopTable.COLUMN_LONGITUDE},
                rs.${DatabaseConstants.RouteStopTable.COLUMN_SCHEDULED_TIME},
                rs.${DatabaseConstants.RouteStopTable.COLUMN_STOP_ORDER}
            FROM
                ${DatabaseConstants.RouteStopTable.TABLE_NAME} AS rs
            INNER JOIN
                ${DatabaseConstants.StopTable.TABLE_NAME} AS s ON rs.${DatabaseConstants.RouteStopTable.COLUMN_STOP_ID} = s.${DatabaseConstants.StopTable.COLUMN_STOP_ID}
            WHERE
                rs.${DatabaseConstants.RouteStopTable.COLUMN_SERVICE_ID} = ?
            ORDER BY
                rs.${DatabaseConstants.RouteStopTable.COLUMN_STOP_ORDER} ASC
        """.trimIndent()

        db.rawQuery(query, arrayOf(serviceId)).use { cursor ->
            val stopIdIndex = cursor.getColumnIndex(DatabaseConstants.StopTable.COLUMN_STOP_ID)
            val locationNameIndex = cursor.getColumnIndex(DatabaseConstants.StopTable.COLUMN_LOCATION_NAME)
            val latitudeIndex = cursor.getColumnIndex(DatabaseConstants.StopTable.COLUMN_LATITUDE)
            val longitudeIndex = cursor.getColumnIndex(DatabaseConstants.StopTable.COLUMN_LONGITUDE)
            val scheduledTimeIndex = cursor.getColumnIndex(DatabaseConstants.RouteStopTable.COLUMN_SCHEDULED_TIME)
            val stopOrderIndex = cursor.getColumnIndex(DatabaseConstants.RouteStopTable.COLUMN_STOP_ORDER)

            while (cursor.moveToNext()) {
                val scheduledTime = if (scheduledTimeIndex != -1 && !cursor.isNull(scheduledTimeIndex))
                    cursor.getString(scheduledTimeIndex) else null

                stops.add(
                    BusStop(
                        stopId = if (stopIdIndex != -1) cursor.getInt(stopIdIndex) else 0,
                        serviceId = serviceId,
                        locationName = if (locationNameIndex != -1) cursor.getString(locationNameIndex) else "Unknown Stop",
                        latitude = if (latitudeIndex != -1) cursor.getDouble(latitudeIndex) else 0.0,
                        longitude = if (longitudeIndex != -1) cursor.getDouble(longitudeIndex) else 0.0,
                        scheduledTime = scheduledTime ?: "--:--",
                        stopOrder = if (stopOrderIndex != -1) cursor.getInt(stopOrderIndex) else 0
                    )
                )
            }
        }

        Log.i(TAG, "Found ${stops.size} stops for serviceId $serviceId")
        return stops
    }

    /**
     * Gets a distinct list of all stop names for autocomplete.
     * Cached after first call since the underlying timetable DB only changes
     * via OTA update (which calls TimetableDatabaseHelper.resetInstance()).
     */
    @Volatile
    private var cachedStopSuggestions: List<String>? = null

    fun invalidateStopSuggestionsCache() {
        cachedStopSuggestions = null
    }

    fun getStopSuggestions(context: Context): List<String> {
        cachedStopSuggestions?.let { return it }

        val suggestions = mutableListOf<String>()
        val db = db(context)

        val query = "SELECT DISTINCT ${DatabaseConstants.StopTable.COLUMN_LOCATION_NAME} " +
                "FROM ${DatabaseConstants.StopTable.TABLE_NAME} " +
                "ORDER BY ${DatabaseConstants.StopTable.COLUMN_LOCATION_NAME} ASC"

        try {
            db.rawQuery(query, null).use { cursor ->
                val locationNameIndex = cursor.getColumnIndex(DatabaseConstants.StopTable.COLUMN_LOCATION_NAME)
                if (locationNameIndex == -1) return emptyList()
                while (cursor.moveToNext()) {
                    suggestions.add(cursor.getString(locationNameIndex))
                }
            }
        } catch (e: SQLiteException) {
            Log.e(TAG, "Error getting stop suggestions", e)
            return emptyList()
        }

        cachedStopSuggestions = suggestions
        return suggestions
    }
}