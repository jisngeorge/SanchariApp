package com.sanchari.bus

import android.content.Context
import android.database.sqlite.SQLiteException
import android.util.Log

object SearchManager {

    private const val TAG = "SearchManager"

    /**
     * Finds bus services that travel from 'from' to 'to'.
     *
     * --- UPDATED LOGIC (Based on your feedback) ---
     * This query now correctly handles both SHUTTLES and HALTS.
     * It finds *every* valid A -> B segment, even for the same serviceId.
     *
     * 1. Joins BusStop T2 (From) with BusStop T3 (To) on the same serviceId.
     * 2. Ensures T2.stopOrder < T3.stopOrder (correct direction).
     * 3. CRITICAL: Uses a NOT EXISTS subquery to ensure there is no *other*
     * "From" stop (T_mid) between T2 and T3. This finds each distinct trip.
     * 4. This logic automatically finds the departure time from a halt.
     */
    fun findBusServices(context: Context, from: String, to: String): List<BusService> {
        val results = mutableListOf<BusService>()
        val dbHelper = TimetableDatabaseHelper(context)
        val db = dbHelper.readableDatabase

        // This is the new, more complex query
        val query = """
            SELECT 
                bs.${DatabaseConstants.BusServiceTable.COLUMN_SERVICE_ID}, 
                bs.${DatabaseConstants.BusServiceTable.COLUMN_NAME}, 
                bs.${DatabaseConstants.BusServiceTable.COLUMN_TYPE}, 
                T2.${DatabaseConstants.BusStopTable.COLUMN_SCHEDULED_TIME} AS fromTime,
                T3.${DatabaseConstants.BusStopTable.COLUMN_SCHEDULED_TIME} AS toTime
            FROM 
                ${DatabaseConstants.BusStopTable.TABLE_NAME} AS T2
            INNER JOIN 
                ${DatabaseConstants.BusStopTable.TABLE_NAME} AS T3 ON T2.serviceId = T3.serviceId
            INNER JOIN
                ${DatabaseConstants.BusServiceTable.TABLE_NAME} AS bs ON T2.serviceId = bs.serviceId
            WHERE 
                T2.locationName = ? 
                AND T3.locationName = ? 
                AND T2.stopOrder < T3.stopOrder
                AND NOT EXISTS (
                    SELECT 1
                    FROM ${DatabaseConstants.BusStopTable.TABLE_NAME} AS T_mid
                    WHERE T_mid.serviceId = T2.serviceId
                      AND T_mid.locationName = ? 
                      AND T_mid.stopOrder > T2.stopOrder
                      AND T_mid.stopOrder < T3.stopOrder
                )
            ORDER BY
                fromTime ASC;
        """.trimIndent()

        try {
            // Note: The "from" parameter is now used three times
            db.rawQuery(query, arrayOf(from, to, from)).use { cursor ->
                if (cursor.moveToFirst()) {
                    val serviceIdIndex = cursor.getColumnIndex(DatabaseConstants.BusServiceTable.COLUMN_SERVICE_ID)
                    val nameIndex = cursor.getColumnIndex(DatabaseConstants.BusServiceTable.COLUMN_NAME)
                    val typeIndex = cursor.getColumnIndex(DatabaseConstants.BusServiceTable.COLUMN_TYPE)
                    val fromTimeIndex = cursor.getColumnIndex("fromTime")
                    val toTimeIndex = cursor.getColumnIndex("toTime")

                    do {
                        if (serviceIdIndex != -1 && nameIndex != -1 && typeIndex != -1 && fromTimeIndex != -1 && toTimeIndex != -1) {
                            val serviceId = cursor.getString(serviceIdIndex)
                            val name = cursor.getString(nameIndex)
                            val type = cursor.getString(typeIndex)
                            val fromTime = cursor.getString(fromTimeIndex)
                            val toTime = cursor.getString(toTimeIndex)

                            results.add(
                                BusService(
                                    serviceId = serviceId,
                                    name = name,
                                    type = type,
                                    isRunning = true, // Placeholder
                                    lastReportedTime = 0L, // Placeholder
                                    fromTime = fromTime, // Added new field
                                    toTime = toTime      // Added new field
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
     * This is needed to launch BusDetailsActivity from a recent search.
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
                        lastReportedTime = if (lastReportedTimeIndex != -1) cursor.getLong(lastReportedTimeIndex) else 0L,
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


    /**
     * Gets all stops for a specific bus service, ordered by stopOrder.
     */
    fun getBusStops(context: Context, serviceId: String): List<BusStop> {
        val stops = mutableListOf<BusStop>()
        val dbHelper = TimetableDatabaseHelper(context)
        val db = dbHelper.readableDatabase

        val query = "SELECT * FROM ${DatabaseConstants.BusStopTable.TABLE_NAME} " +
                "WHERE ${DatabaseConstants.BusStopTable.COLUMN_SERVICE_ID} = ? " +
                "ORDER BY ${DatabaseConstants.BusStopTable.COLUMN_STOP_ORDER} ASC"

        try {
            db.rawQuery(query, arrayOf(serviceId)).use { cursor ->
                if (cursor.moveToFirst()) {
                    val stopIdIndex = cursor.getColumnIndex(DatabaseConstants.BusStopTable.COLUMN_STOP_ID)
                    val locationNameIndex = cursor.getColumnIndex(DatabaseConstants.BusStopTable.COLUMN_LOCATION_NAME)
                    val latitudeIndex = cursor.getColumnIndex(DatabaseConstants.BusStopTable.COLUMN_LATITUDE)
                    val longitudeIndex = cursor.getColumnIndex(DatabaseConstants.BusStopTable.COLUMN_LONGITUDE)
                    val scheduledTimeIndex = cursor.getColumnIndex(DatabaseConstants.BusStopTable.COLUMN_SCHEDULED_TIME)
                    val stopOrderIndex = cursor.getColumnIndex(DatabaseConstants.BusStopTable.COLUMN_STOP_ORDER)

                    do {
                        stops.add(
                            BusStop(
                                stopId = if (stopIdIndex != -1) cursor.getInt(stopIdIndex) else 0,
                                serviceId = serviceId,
                                locationName = if (locationNameIndex != -1) cursor.getString(locationNameIndex) else "Unknown Stop",
                                latitude = if (latitudeIndex != -1) cursor.getDouble(latitudeIndex) else 0.0,
                                longitude = if (longitudeIndex != -1) cursor.getDouble(longitudeIndex) else 0.0,
                                scheduledTime = if (scheduledTimeIndex != -1) cursor.getString(scheduledTimeIndex) else "--:--",
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
        val suggestions = mutableListOf<String>()
        val dbHelper = TimetableDatabaseHelper(context)
        val db = dbHelper.readableDatabase

        val query = "SELECT DISTINCT ${DatabaseConstants.BusStopTable.COLUMN_LOCATION_NAME} " +
                "FROM ${DatabaseConstants.BusStopTable.TABLE_NAME} " +
                "ORDER BY ${DatabaseConstants.BusStopTable.COLUMN_LOCATION_NAME} ASC"

        try {
            db.rawQuery(query, null).use { cursor ->
                if (cursor.moveToFirst()) {
                    val locationNameIndex = cursor.getColumnIndex(DatabaseConstants.BusStopTable.COLUMN_LOCATION_NAME)
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