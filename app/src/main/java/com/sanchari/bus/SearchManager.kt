package com.sanchari.bus

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log

object SearchManager {

    private const val TAG = "SearchManager"

    /**
     * Searches the TimetableDatabase for bus services connecting two locations.
     * This is a simplified search and a real-world one would be much more complex,
     * likely involving joins and subqueries.
     */
    fun findBusServices(context: Context, from: String, to: String): List<BusService> {
        val dbHelper = TimetableDatabaseHelper(context)
        val db: SQLiteDatabase
        try {
            db = dbHelper.readableDatabase
        } catch (e: Exception) {
            Log.e(TAG, "Error opening readable database", e)
            return emptyList()
        }

        val results = mutableListOf<BusService>()

        // This is a highly simplified query.
        // A real query would need to:
        // 1. Find all serviceIds that have a stop matching 'from'.
        // 2. Find all serviceIds that have a stop matching 'to'.
        // 3. Find the intersection of these two sets.
        // 4. For each resulting serviceId, ensure the 'from' stopOrder < 'to' stopOrder.
        //
        // For now, we will use a very simple query that just finds services that
        // have stops in *both* locations, and then get the service details.
        // This won't be correct for routes, but it's a start.

        val query = """
            SELECT T1.serviceId, T2.name, T2.type
            FROM ${DatabaseConstants.BusStopTable.TABLE_NAME} AS T1
            JOIN ${DatabaseConstants.BusServiceTable.TABLE_NAME} AS T2 ON T1.serviceId = T2.serviceId
            WHERE T1.locationName LIKE ?
            INTERSECT
            SELECT T1.serviceId, T2.name, T2.type
            FROM ${DatabaseConstants.BusStopTable.TABLE_NAME} AS T1
            JOIN ${DatabaseConstants.BusServiceTable.TABLE_NAME} AS T2 ON T1.serviceId = T2.serviceId
            WHERE T1.locationName LIKE ?
        """

        var cursor: Cursor? = null
        try {
            cursor = db.rawQuery(query, arrayOf("%$from%", "%$to%"))

            if (cursor.moveToFirst()) {
                do {
                    // Reverted to getString
                    val serviceId = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseConstants.BusServiceTable.COLUMN_SERVICE_ID))
                    val name = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseConstants.BusServiceTable.COLUMN_NAME))
                    val type = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseConstants.BusServiceTable.COLUMN_TYPE))

                    results.add(
                        BusService(
                            serviceId = serviceId, // Now passing a String
                            name = name,
                            type = type,
                            isRunning = true, // Placeholder
                            lastReportedTime = 0L // Placeholder updated to Long
                        )
                    )
                } while (cursor.moveToNext())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing search query", e)
        } finally {
            cursor?.close()
            db.close()
            dbHelper.close()
        }

        return results
    }

    /**
     * Gets all bus stops for a specific service ID, ordered by stopOrder.
     */
    // Reverted parameter to accept String
    fun getBusStops(context: Context, serviceId: String): List<BusStop> {
        val dbHelper = TimetableDatabaseHelper(context)
        val db: SQLiteDatabase
        try {
            db = dbHelper.readableDatabase
        } catch (e: Exception) {
            Log.e(TAG, "Error opening readable database", e)
            return emptyList()
        }

        val results = mutableListOf<BusStop>()
        val query = """
            SELECT * FROM ${DatabaseConstants.BusStopTable.TABLE_NAME}
            WHERE ${DatabaseConstants.BusStopTable.COLUMN_SERVICE_ID} = ?
            ORDER BY ${DatabaseConstants.BusStopTable.COLUMN_STOP_ORDER} ASC
        """

        var cursor: Cursor? = null
        try {
            // Reverted to pass serviceId directly as String
            cursor = db.rawQuery(query, arrayOf(serviceId))

            if (cursor.moveToFirst()) {
                do {
                    results.add(
                        BusStop(
                            stopId = cursor.getInt(cursor.getColumnIndexOrThrow(DatabaseConstants.BusStopTable.COLUMN_STOP_ID)),
                            // Reverted to getString
                            serviceId = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseConstants.BusStopTable.COLUMN_SERVICE_ID)),
                            locationName = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseConstants.BusStopTable.COLUMN_LOCATION_NAME)),
                            latitude = cursor.getDouble(cursor.getColumnIndexOrThrow(DatabaseConstants.BusStopTable.COLUMN_LATITUDE)),
                            longitude = cursor.getDouble(cursor.getColumnIndexOrThrow(DatabaseConstants.BusStopTable.COLUMN_LONGITUDE)),
                            scheduledTime = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseConstants.BusStopTable.COLUMN_SCHEDULED_TIME)),
                            stopOrder = cursor.getInt(cursor.getColumnIndexOrThrow(DatabaseConstants.BusStopTable.COLUMN_STOP_ORDER))
                        )
                    )
                } while (cursor.moveToNext())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting bus stops", e)
        } finally {
            cursor?.close()
            db.close()
            dbHelper.close()
        }

        return results
    }
}

