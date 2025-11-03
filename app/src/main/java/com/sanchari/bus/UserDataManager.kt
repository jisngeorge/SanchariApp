package com.sanchari.bus

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.util.Log
import com.sanchari.bus.DatabaseConstants.RecentViewTable
import com.sanchari.bus.DatabaseConstants.UserTable
import java.util.UUID

object UserDataManager {

    private const val TAG = "UserDataManager"

    /**
     * Saves user information. This will either insert a new user (if one doesn't exist)
     * or update the existing one based on the UUID.
     * @return true if the save was successful, false otherwise.
     */
    fun saveUser(context: Context, user: User): Boolean {
        val dbHelper = UserDatabaseHelper(context)
        val db = dbHelper.writableDatabase
        var success = false // Initialize success flag
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
            // The WHERE clause is "uuid = ?"
            val updatedRows = db.update(
                UserTable.TABLE_NAME,
                values,
                "${UserTable.COLUMN_UUID} = ?",
                arrayOf(user.uuid)
            )

            if (updatedRows > 0) {
                // Update was successful
                Log.i(TAG, "Existing user updated with UUID: ${user.uuid}")
                operationSuccessful = true
            } else {
                // No rows were updated, so this is a new user. Insert them.
                val newRowId = db.insert(UserTable.TABLE_NAME, null, values)
                if (newRowId != -1L) {
                    Log.i(TAG, "New user inserted with UUID: ${user.uuid}")
                    operationSuccessful = true
                } else {
                    Log.e(TAG, "Failed to insert new user with UUID: ${user.uuid}")
                    // operationSuccessful remains false
                }
            }

            if (operationSuccessful) {
                db.setTransactionSuccessful()
                success = true // Mark as successful only if transaction is committed
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving user", e)
            success = false // Ensure success is false on exception
        } finally {
            db.endTransaction()
            db.close()
        }
        return success // Return the success status
    }

    /**
     * Retrieves the current user. Since there's only one user, it fetches the first one.
     * If no user exists, it creates and returns a default, empty User object
     * with a new UUID.
     */
    @SuppressLint("Range")
    fun getUser(context: Context): User {
        val dbHelper = UserDatabaseHelper(context)
        val db = dbHelper.readableDatabase
        var user: User? = null

        // We just fetch the first user, as there should only be one
        val cursor = db.query(
            UserTable.TABLE_NAME,
            null, // all columns
            null, // no WHERE clause
            null, // no WHERE args
            null, // no grouping
            null, // no filter
            null, // no sort order
            "1"   // LIMIT 1
        )

        try {
            if (cursor.moveToFirst()) {
                val uuidIndex = cursor.getColumnIndex(UserTable.COLUMN_UUID)
                val nameIndex = cursor.getColumnIndex(UserTable.COLUMN_NAME)
                val emailIndex = cursor.getColumnIndex(UserTable.COLUMN_EMAIL)
                val phoneIndex = cursor.getColumnIndex(UserTable.COLUMN_PHONE)
                val placeIndex = cursor.getColumnIndex(UserTable.COLUMN_PLACE)

                // Construct the User object *without* the internal userId
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

        // Return the found user, or a new blank User object with a fresh UUID
        return user ?: User(
            uuid = UUID.randomUUID().toString(),
            name = "",
            email = "",
            phone = "",
            place = ""
        )
    }

    /**
     * Adds a service to the recent views.
     * It removes any existing entry for this serviceId to avoid duplicates and
     * re-inserts it at the top (most recent).
     */
    fun addRecentView(context: Context, serviceId: String, serviceName: String) {
        val dbHelper = UserDatabaseHelper(context)
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            // 1. Delete any existing entry for this serviceId to prevent duplicates
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

            db.setTransactionSuccessful()
            Log.i(TAG, "Added recent view: $serviceName")
        } catch (e: Exception) {
            Log.e(TAG, "Error adding recent view", e)
        } finally {
            db.endTransaction()
            db.close()
        }
    }

    /**
     * Retrieves the list of recent searches, ordered by most recent first.
     */
    @SuppressLint("Range")
    fun getRecentViews(context: Context, limit: Int = 10): List<RecentSearch> {
        val dbHelper = UserDatabaseHelper(context)
        val db = dbHelper.readableDatabase
        val recentSearches = mutableListOf<RecentSearch>()

        // Corrected the typo: RecentData -> RecentViewTable
        val cursor = db.query(
            RecentViewTable.TABLE_NAME,
            null, // all columns
            null, // no WHERE
            null, // no WHERE args
            null, // no grouping
            null, // no filter
            "${RecentViewTable.COLUMN_VIEWED_TIMESTAMP} DESC", // Order by most recent
            limit.toString() // Use the limit parameter
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
                        // Corrected data type: getString -> getLong
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

