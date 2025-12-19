package com.sanchari.bus.data.manager

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteException
import android.util.Log
import com.sanchari.bus.data.local.DatabaseConstants.RecentViewTable
import com.sanchari.bus.data.local.DatabaseConstants.UserTable
import com.sanchari.bus.data.local.UserDatabaseHelper
import com.sanchari.bus.data.model.RecentSearch
import com.sanchari.bus.data.model.User
import java.util.UUID

object UserDataManager {

    private const val TAG = "UserDataManager"
    private const val RECENTS_LIMIT = 5 // Changed from 10 to 5

    /**
     * Saves user information. This will either insert a new user (if one doesn't exist)
     * or update the existing one based on the UUID.
     * @return true if the save was successful, false otherwise.
     */
    fun saveUser(context: Context, user: User): Boolean {
        val dbHelper = UserDatabaseHelper(context)
        val db = dbHelper.writableDatabase
        var success = false
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put(UserTable.COLUMN_UUID, user.uuid)
                put(UserTable.COLUMN_NAME, user.name)
                put(UserTable.COLUMN_EMAIL, user.email)
                put(UserTable.COLUMN_PHONE, user.phone)
                put(UserTable.COLUMN_PLACE, user.place)
            }

            var operationSuccessful = false

            // Try to update first based on UUID.
            val updatedRows = db.update(
                UserTable.TABLE_NAME,
                values,
                "${UserTable.COLUMN_UUID} = ?",
                arrayOf(user.uuid)
            )

            if (updatedRows > 0) {
                Log.i(TAG, "Existing user updated with UUID: ${user.uuid}")
                operationSuccessful = true
            } else {
                val newRowId = db.insert(UserTable.TABLE_NAME, null, values)
                if (newRowId != -1L) {
                    Log.i(TAG, "New user inserted with UUID: ${user.uuid}")
                    operationSuccessful = true
                } else {
                    Log.e(TAG, "Failed to insert new user with UUID: ${user.uuid}")
                }
            }

            if (operationSuccessful) {
                db.setTransactionSuccessful()
                success = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving user", e)
            success = false
        } finally {
            db.endTransaction()
            db.close()
        }
        return success
    }

    /**
     * Retrieves the current user.
     */
    @SuppressLint("Range")
    fun getUser(context: Context): User {
        val dbHelper = UserDatabaseHelper(context)
        val db = dbHelper.readableDatabase
        var user: User? = null

        val cursor = db.query(
            UserTable.TABLE_NAME,
            null, null, null, null, null, null, "1"
        )

        try {
            if (cursor.moveToFirst()) {
                val uuidIndex = cursor.getColumnIndex(UserTable.COLUMN_UUID)
                val nameIndex = cursor.getColumnIndex(UserTable.COLUMN_NAME)
                val emailIndex = cursor.getColumnIndex(UserTable.COLUMN_EMAIL)
                val phoneIndex = cursor.getColumnIndex(UserTable.COLUMN_PHONE)
                val placeIndex = cursor.getColumnIndex(UserTable.COLUMN_PLACE)

                user = User(
                    uuid = if (uuidIndex != -1) cursor.getString(uuidIndex) else "",
                    name = if (nameIndex != -1) cursor.getString(nameIndex) else "",
                    email = if (emailIndex != -1) cursor.getString(emailIndex) else "",
                    phone = if (phoneIndex != -1) cursor.getString(phoneIndex) else "",
                    place = if (placeIndex != -1) cursor.getString(placeIndex) else ""
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting user", e)
        } finally {
            cursor.close()
            db.close()
        }

        return user ?: User(
            uuid = UUID.randomUUID().toString(),
            name = "",
            email = "",
            phone = "",
            place = ""
        )
    }

    /**
     * Adds a service to the recent views and prunes the list to keep only the latest 5.
     */
    fun addRecentView(context: Context, serviceId: String, serviceName: String) {
        val dbHelper = UserDatabaseHelper(context)
        val db = dbHelper.writableDatabase

        try {
            db.beginTransaction()
            try {
                // 1. Delete any existing entry for this serviceId to prevent duplicates
                // (This brings it to the top when re-inserted with a new timestamp)
                db.delete(
                    RecentViewTable.TABLE_NAME,
                    "${RecentViewTable.COLUMN_SERVICE_ID} = ?",
                    arrayOf(serviceId)
                )

                // 2. Insert the new entry
                val values = ContentValues().apply {
                    put(RecentViewTable.COLUMN_SERVICE_ID, serviceId)
                    put(RecentViewTable.COLUMN_SERVICE_NAME, serviceName)
                    // The timestamp defaults to CURRENT_TIMESTAMP in the schema
                }
                db.insert(RecentViewTable.TABLE_NAME, null, values)

                // 3. Prune old entries: Keep only the latest 5
                // Logic: Delete rows where recentId is NOT IN the set of the top 5 recent IDs
                val pruneQuery = """
                    DELETE FROM ${RecentViewTable.TABLE_NAME} 
                    WHERE ${RecentViewTable.COLUMN_RECENT_ID} NOT IN (
                        SELECT ${RecentViewTable.COLUMN_RECENT_ID} 
                        FROM ${RecentViewTable.TABLE_NAME} 
                        ORDER BY ${RecentViewTable.COLUMN_VIEWED_TIMESTAMP} DESC 
                        LIMIT $RECENTS_LIMIT
                    )
                """
                db.execSQL(pruneQuery)

                db.setTransactionSuccessful()
                Log.i(TAG, "Added recent view: $serviceName and pruned list")
            } finally {
                db.endTransaction()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error adding recent view", e)
        } finally {
            db.close()
        }
    }

    /**
     * Retrieves the list of recent searches.
     */
    @SuppressLint("Range")
    fun getRecentViews(context: Context, limit: Int = RECENTS_LIMIT): List<RecentSearch> {
        val dbHelper = UserDatabaseHelper(context)
        val db = dbHelper.readableDatabase
        val recentSearches = mutableListOf<RecentSearch>()

        val cursor = db.query(
            RecentViewTable.TABLE_NAME,
            null, null, null, null, null,
            "${RecentViewTable.COLUMN_VIEWED_TIMESTAMP} DESC",
            limit.toString()
        )

        try {
            if (cursor.moveToFirst()) {
                val serviceIdIndex = cursor.getColumnIndex(RecentViewTable.COLUMN_SERVICE_ID)
                val serviceNameIndex = cursor.getColumnIndex(RecentViewTable.COLUMN_SERVICE_NAME)
                val timestampIndex = cursor.getColumnIndex(RecentViewTable.COLUMN_VIEWED_TIMESTAMP)

                do {
                    val search = RecentSearch(
                        serviceId = cursor.getString(serviceIdIndex),
                        serviceName = cursor.getString(serviceNameIndex),
                        viewedTimestamp = cursor.getLong(timestampIndex)
                    )
                    recentSearches.add(search)
                } while (cursor.moveToNext())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting recent views", e)
        } finally {
            cursor.close()
            db.close()
        }
        return recentSearches
    }
}