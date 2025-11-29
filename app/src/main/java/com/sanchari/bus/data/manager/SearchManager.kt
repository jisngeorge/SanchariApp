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

    /**
     * Finds bus services that travel from 'from' to 'to'.
     * ... (existing documentation) ...
     */
    fun findBusServices(context: Context, from: String, to: String): List<BusService> {
        // ... (existing code unchanged) ...
        val results = mutableListOf<BusService>()
        val dbHelper = TimetableDatabaseHelper(context)
        val db = dbHelper.readableDatabase

        // This is the new, more complex query for the normalized structure
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
                s_from.${DatabaseConstants.StopTable.COLUMN_LOCATION_NAME} = ? -- [param 1: from]
                AND s_to.${DatabaseConstants.StopTable.COLUMN_LOCATION_NAME} = ? -- [param 2: to]
                AND rs_from.${DatabaseConstants.RouteStopTable.COLUMN_STOP_ORDER} < rs_to.${DatabaseConstants.RouteStopTable.COLUMN_STOP_ORDER}
                -- This subquery handles SHUTTLES
                AND NOT EXISTS (
                    SELECT 1
                    FROM ${DatabaseConstants.RouteStopTable.TABLE_NAME} AS rs_mid
                    INNER JOIN ${DatabaseConstants.StopTable.TABLE_NAME} AS s_mid ON rs_mid.${DatabaseConstants.RouteStopTable.COLUMN_STOP_ID} = s_mid.${DatabaseConstants.StopTable.COLUMN_STOP_ID}
                    WHERE rs_mid.${DatabaseConstants.RouteStopTable.COLUMN_SERVICE_ID} = rs_from.${DatabaseConstants.RouteStopTable.COLUMN_SERVICE_ID}
                      AND s_mid.${DatabaseConstants.StopTable.COLUMN_LOCATION_NAME} = ? -- [param 3: from]
                      AND rs_mid.${DatabaseConstants.RouteStopTable.COLUMN_STOP_ORDER} > rs_from.${DatabaseConstants.RouteStopTable.COLUMN_STOP_ORDER}
                      AND rs_mid.${DatabaseConstants.RouteStopTable.COLUMN_STOP_ORDER} < rs_to.${DatabaseConstants.RouteStopTable.COLUMN_STOP_ORDER}
                )
                -- This subquery handles HALTS at the destination
                AND NOT EXISTS (
                    SELECT 1
                    FROM ${DatabaseConstants.RouteStopTable.TABLE_NAME} AS rs_mid
                    INNER JOIN ${DatabaseConstants.StopTable.TABLE_NAME} AS s_mid ON rs_mid.${DatabaseConstants.RouteStopTable.COLUMN_STOP_ID} = s_mid.${DatabaseConstants.StopTable.COLUMN_STOP_ID}
                    WHERE rs_mid.${DatabaseConstants.RouteStopTable.COLUMN_SERVICE_ID} = rs_from.${DatabaseConstants.RouteStopTable.COLUMN_SERVICE_ID}
                      AND s_mid.${DatabaseConstants.StopTable.COLUMN_LOCATION_NAME} = ? -- [param 4: to]
                      AND rs_mid.${DatabaseConstants.RouteStopTable.COLUMN_STOP_ORDER} > rs_from.${DatabaseConstants.RouteStopTable.COLUMN_STOP_ORDER}
                      AND rs_mid.${DatabaseConstants.RouteStopTable.COLUMN_STOP_ORDER} < rs_to.${DatabaseConstants.RouteStopTable.COLUMN_STOP_ORDER}
                )
            ORDER BY
                fromTime ASC;
        """.trimIndent()

        try {
            db.rawQuery(query, arrayOf(from, to, from, to)).use { cursor ->
                if (cursor.moveToFirst()) {
                    val serviceIdIndex = cursor.getColumnIndex(DatabaseConstants.BusServiceTable.COLUMN_SERVICE_ID)
                    val nameIndex = cursor.getColumnIndex(DatabaseConstants.BusServiceTable.COLUMN_NAME)
                    val typeIndex = cursor.getColumnIndex(DatabaseConstants.BusServiceTable.COLUMN_TYPE)
                    val fromTimeIndex = cursor.getColumnIndex("fromTime")
                    val toTimeIndex = cursor.getColumnIndex("toTime")
                    val isRunningIndex = cursor.getColumnIndex(DatabaseConstants.BusServiceTable.COLUMN_IS_RUNNING)
                    val lastReportedTimeIndex = cursor.getColumnIndex(DatabaseConstants.BusServiceTable.COLUMN_LAST_REPORTED_TIME)

                    do {
                        if (serviceIdIndex != -1 && nameIndex != -1 && typeIndex != -1 && fromTimeIndex != -1 && toTimeIndex != -1) {
                            val serviceId = cursor.getString(serviceIdIndex)
                            val name = cursor.getString(nameIndex)
                            val type = cursor.getString(typeIndex)
                            val fromTime = cursor.getString(fromTimeIndex)
                            val toTime = cursor.getString(toTimeIndex)
                            val isRunning = if (isRunningIndex != -1) cursor.getInt(isRunningIndex) == 1 else true
                            val lastReportedTime = if (lastReportedTimeIndex != -1) cursor.getLong(lastReportedTimeIndex) else 0L

                            results.add(
                                BusService(
                                    serviceId = serviceId,
                                    name = name,
                                    type = type,
                                    isRunning = isRunning,
                                    lastReportedTime = lastReportedTime,
                                    fromTime = fromTime,
                                    toTime = toTime
                                )
                            )
                        }
                    } while (cursor.moveToNext())
                }
            }
        } catch (e: SQLiteException) {
            Log.e(TAG, "Error finding bus services", e)
        } finally {
            db.close()
        }

        Log.i(TAG, "Found ${results.size} services from '$from' to '$to'")
        return results
    }

    /**
     * Retrieves a single BusService object by its ID.
     */
    fun getBusServiceById(context: Context, serviceId: String): BusService? {
        val dbHelper = TimetableDatabaseHelper(context)
        val db = dbHelper.readableDatabase
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
                        lastReportedTime = if (lastReportedTimeIndex != -1) cursor.getLong(
                            lastReportedTimeIndex
                        ) else 0L,
                        fromTime = "--:--", // Placeholder, not relevant here
                        toTime = "--:--"    // Placeholder, not relevant here
                    )
                }
            }
        } catch (e: SQLiteException) {
            Log.e(TAG, "Error getting bus service by ID", e)
        } finally {
            db.close()
        }
        return busService
    }

    // --- NEW FUNCTION ---
    /**
     * Searches for buses by name (partial match).
     */
    fun searchBusServicesByName(context: Context, query: String): List<BusService> {
        val results = mutableListOf<BusService>()
        val dbHelper = TimetableDatabaseHelper(context)
        val db = dbHelper.readableDatabase

        // Simple LIKE query
        val sql = """
            SELECT * FROM ${DatabaseConstants.BusServiceTable.TABLE_NAME}
            WHERE ${DatabaseConstants.BusServiceTable.COLUMN_NAME} LIKE ?
            LIMIT 20
        """.trimIndent()

        val selectionArgs = arrayOf("%$query%")

        try {
            db.rawQuery(sql, selectionArgs).use { cursor ->
                if (cursor.moveToFirst()) {
                    val serviceIdIndex = cursor.getColumnIndex(DatabaseConstants.BusServiceTable.COLUMN_SERVICE_ID)
                    val nameIndex = cursor.getColumnIndex(DatabaseConstants.BusServiceTable.COLUMN_NAME)
                    val typeIndex = cursor.getColumnIndex(DatabaseConstants.BusServiceTable.COLUMN_TYPE)
                    val isRunningIndex = cursor.getColumnIndex(DatabaseConstants.BusServiceTable.COLUMN_IS_RUNNING)
                    val lastReportedTimeIndex = cursor.getColumnIndex(DatabaseConstants.BusServiceTable.COLUMN_LAST_REPORTED_TIME)

                    do {
                        results.add(
                            BusService(
                                serviceId = if (serviceIdIndex != -1) cursor.getString(
                                    serviceIdIndex
                                ) else "",
                                name = if (nameIndex != -1) cursor.getString(nameIndex) else "Unknown",
                                type = if (typeIndex != -1) cursor.getString(typeIndex) else "Unknown",
                                isRunning = if (isRunningIndex != -1) cursor.getInt(isRunningIndex) == 1 else true,
                                lastReportedTime = if (lastReportedTimeIndex != -1) cursor.getLong(
                                    lastReportedTimeIndex
                                ) else 0L,
                                fromTime = "--:--",
                                toTime = "--:--"
                            )
                        )
                    } while (cursor.moveToNext())
                }
            }
        } catch (e: SQLiteException) {
            Log.e(TAG, "Error searching buses by name", e)
        } finally {
            db.close()
        }
        return results
    }
    // --- END NEW FUNCTION ---


    /**
     * Gets all stops for a specific bus service, ordered by stopOrder.
     */
    fun getBusStops(context: Context, serviceId: String): List<BusStop> {
        // ... (existing code unchanged) ...
        val stops = mutableListOf<BusStop>()
        val dbHelper = TimetableDatabaseHelper(context)
        val db = dbHelper.readableDatabase

        // This query now joins RouteStop and Stop
        val query = """
            SELECT 
                s.${DatabaseConstants.StopTable.COLUMN_STOP_ID},
                rs.${DatabaseConstants.RouteStopTable.COLUMN_SERVICE_ID},
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
                if (cursor.moveToFirst()) {
                    // Get column indices from the new joined query
                    val stopIdIndex = cursor.getColumnIndex(DatabaseConstants.StopTable.COLUMN_STOP_ID)
                    val locationNameIndex = cursor.getColumnIndex(DatabaseConstants.StopTable.COLUMN_LOCATION_NAME)
                    val latitudeIndex = cursor.getColumnIndex(DatabaseConstants.StopTable.COLUMN_LATITUDE)
                    val longitudeIndex = cursor.getColumnIndex(DatabaseConstants.StopTable.COLUMN_LONGITUDE)
                    val scheduledTimeIndex = cursor.getColumnIndex(DatabaseConstants.RouteStopTable.COLUMN_SCHEDULED_TIME)
                    val stopOrderIndex = cursor.getColumnIndex(DatabaseConstants.RouteStopTable.COLUMN_STOP_ORDER)

                    do {
                        // --- ADDED: Null check with default value ---
                        val scheduledTime = if (scheduledTimeIndex != -1) cursor.getString(scheduledTimeIndex) else null

                        stops.add(
                            BusStop(
                                stopId = if (stopIdIndex != -1) cursor.getInt(stopIdIndex) else 0,
                                serviceId = serviceId,
                                locationName = if (locationNameIndex != -1) cursor.getString(
                                    locationNameIndex
                                ) else "Unknown Stop",
                                latitude = if (latitudeIndex != -1) cursor.getDouble(latitudeIndex) else 0.0,
                                longitude = if (longitudeIndex != -1) cursor.getDouble(
                                    longitudeIndex
                                ) else 0.0,
                                scheduledTime = scheduledTime ?: "--:--", // Fixed
                                stopOrder = if (stopOrderIndex != -1) cursor.getInt(stopOrderIndex) else 0
                            )
                        )
                    } while (cursor.moveToNext())
                }
            }
        } catch (e: SQLiteException) {
            Log.e(TAG, "Error getting bus stops", e)
        } finally {
            db.close()
        }

        Log.i(TAG, "Found ${stops.size} stops for serviceId $serviceId")
        return stops
    }

    /**
     * Gets a distinct list of all stop names for autocomplete.
     */
    fun getStopSuggestions(context: Context): List<String> {
        // ... (existing code unchanged) ...
        val suggestions = mutableListOf<String>()
        val dbHelper = TimetableDatabaseHelper(context)
        val db = dbHelper.readableDatabase

        // This query is now simpler: just select from the new Stop table
        val query = "SELECT DISTINCT ${DatabaseConstants.StopTable.COLUMN_LOCATION_NAME} " +
                "FROM ${DatabaseConstants.StopTable.TABLE_NAME} " +
                "ORDER BY ${DatabaseConstants.StopTable.COLUMN_LOCATION_NAME} ASC"

        try {
            db.rawQuery(query, null).use { cursor ->
                if (cursor.moveToFirst()) {
                    val locationNameIndex = cursor.getColumnIndex(DatabaseConstants.StopTable.COLUMN_LOCATION_NAME)
                    do {
                        if (locationNameIndex != -1) {
                            suggestions.add(cursor.getString(locationNameIndex))
                        }
                    } while (cursor.moveToNext())
                }
            }
        } catch (e: SQLiteException) {
            Log.e(TAG, "Error getting stop suggestions", e)
        } finally {
            db.close()
        }
        return suggestions
    }
}