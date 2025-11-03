package com.sanchari.bus

import android.content.Context
import android.database.sqlite.SQLiteException
import android.util.Log

object SearchManager {

    private const val TAG = "SearchManager"

    /**
     * Finds bus services that have stops at both 'from' and 'to' locations.
     */
    fun findBusServices(context: Context, from: String, to: String): List<BusService> {
        val results = mutableListOf<BusService>()
        val dbHelper = TimetableDatabaseHelper(context)
        val db = dbHelper.readableDatabase

        // This query finds serviceId, name, and type for services that have
        // stops at both the 'from' and 'to' locations.
        val query = """
            SELECT T1.serviceId, T1.name, T1.type
            FROM ${DatabaseConstants.BusServiceTable.TABLE_NAME} AS T1
            INNER JOIN ${DatabaseConstants.BusStopTable.TABLE_NAME} AS T2 ON T1.serviceId = T2.serviceId
            INNER JOIN ${DatabaseConstants.BusStopTable.TABLE_NAME} AS T3 ON T1.serviceId = T3.serviceId
            WHERE T2.locationName = ? AND T3.locationName = ?
            GROUP BY T1.serviceId
        """.trimIndent()

        try {
            db.rawQuery(query, arrayOf(from, to)).use { cursor ->
                if (cursor.moveToFirst()) {
                    val serviceIdIndex = cursor.getColumnIndex("serviceId")
                    val nameIndex = cursor.getColumnIndex("name")
                    val typeIndex = cursor.getColumnIndex("type")

                    do {
                        if (serviceIdIndex != -1 && nameIndex != -1 && typeIndex != -1) {
                            val serviceId = cursor.getString(serviceIdIndex)
                            val name = cursor.getString(nameIndex)
                            val type = cursor.getString(typeIndex)

                            results.add(
                                BusService(
                                    serviceId = serviceId,
                                    name = name,
                                    type = type,
                                    isRunning = true, // Placeholder
                                    lastReportedTime = 0L // Placeholder
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
                    // Add isRunning and lastReportedTime if they are in the DB, otherwise use placeholders
                    val isRunningIndex = cursor.getColumnIndex(DatabaseConstants.BusServiceTable.COLUMN_IS_RUNNING)
                    val lastReportedTimeIndex = cursor.getColumnIndex(DatabaseConstants.BusServiceTable.COLUMN_LAST_REPORTED_TIME)

                    busService = BusService(
                        serviceId = if (serviceIdIndex != -1) cursor.getString(serviceIdIndex) else "",
                        name = if (nameIndex != -1) cursor.getString(nameIndex) else "Unknown",
                        type = if (typeIndex != -1) cursor.getString(typeIndex) else "Unknown",
                        isRunning = if (isRunningIndex != -1) cursor.getInt(isRunningIndex) == 1 else true,
                        lastReportedTime = if (lastReportedTimeIndex != -1) cursor.getLong(lastReportedTimeIndex) else 0L
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

