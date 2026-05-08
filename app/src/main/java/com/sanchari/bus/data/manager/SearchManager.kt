package com.sanchari.bus.data.manager

import android.content.Context
import android.database.sqlite.SQLiteException
import android.util.Log
import com.sanchari.bus.data.local.DatabaseConstants
import com.sanchari.bus.data.local.TimetableDatabaseHelper
import com.sanchari.bus.data.model.BusService
import com.sanchari.bus.data.model.BusStop

object SearchManager {

    private const val TAG = "SearchManager"

    private fun db(context: Context) =
        TimetableDatabaseHelper.getInstance(context).readableDatabase

    /**
     * Finds bus services that travel from 'from' to 'to'.
     */
    fun findBusServices(context: Context, from: String, to: String): List<BusService> {
        val results = mutableListOf<BusService>()
        val db = db(context)

        val query = """
            SELECT 
                bs.${DatabaseConstants.BusServiceTable.COLUMN_SERVICE_ID}, 
                bs.${DatabaseConstants.BusServiceTable.COLUMN_NAME}, 
                bs.${DatabaseConstants.BusServiceTable.COLUMN_TYPE}, 
                bs.${DatabaseConstants.BusServiceTable.COLUMN_IS_RUNNING},
                bs.${DatabaseConstants.BusServiceTable.COLUMN_LAST_REPORTED_TIME},
                rs_from.${DatabaseConstants.RouteStopTable.COLUMN_SCHEDULED_TIME} AS fromTime,
                rs_to.${DatabaseConstants.RouteStopTable.COLUMN_SCHEDULED_TIME} AS toTime
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

        try {
            db.rawQuery(query, arrayOf(from, to, from, to)).use { cursor ->
                // Resolve column indices once outside the loop
                val serviceIdIndex = cursor.getColumnIndex(DatabaseConstants.BusServiceTable.COLUMN_SERVICE_ID)
                val nameIndex = cursor.getColumnIndex(DatabaseConstants.BusServiceTable.COLUMN_NAME)
                val typeIndex = cursor.getColumnIndex(DatabaseConstants.BusServiceTable.COLUMN_TYPE)
                val fromTimeIndex = cursor.getColumnIndex("fromTime")
                val toTimeIndex = cursor.getColumnIndex("toTime")
                val isRunningIndex = cursor.getColumnIndex(DatabaseConstants.BusServiceTable.COLUMN_IS_RUNNING)
                val lastReportedTimeIndex = cursor.getColumnIndex(DatabaseConstants.BusServiceTable.COLUMN_LAST_REPORTED_TIME)

                if (serviceIdIndex == -1 || nameIndex == -1 || typeIndex == -1 ||
                    fromTimeIndex == -1 || toTimeIndex == -1) {
                    Log.e(TAG, "Required columns missing in findBusServices result")
                    return emptyList()
                }

                while (cursor.moveToNext()) {
                    results.add(
                        BusService(
                            serviceId = cursor.getString(serviceIdIndex),
                            name = cursor.getString(nameIndex),
                            type = cursor.getString(typeIndex),
                            isRunning = if (isRunningIndex != -1) cursor.getInt(isRunningIndex) == 1 else true,
                            lastReportedTime = if (lastReportedTimeIndex != -1) cursor.getLong(lastReportedTimeIndex) else 0L,
                            fromTime = cursor.getString(fromTimeIndex),
                            toTime = cursor.getString(toTimeIndex)
                        )
                    )
                }
            }
        } catch (e: SQLiteException) {
            Log.e(TAG, "Error finding bus services", e)
        }

        Log.i(TAG, "Found ${results.size} services from '$from' to '$to'")
        return results
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
        val stops = mutableListOf<BusStop>()
        val db = db(context)

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

        try {
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
        } catch (e: SQLiteException) {
            Log.e(TAG, "Error getting bus stops", e)
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