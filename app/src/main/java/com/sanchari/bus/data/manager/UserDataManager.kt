package com.sanchari.bus.data.manager

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteException
import android.util.Log
import com.sanchari.bus.data.local.DatabaseConstants.RecentViewTable
import com.sanchari.bus.data.local.DatabaseConstants.SubmissionLogTable
import com.sanchari.bus.data.local.DatabaseConstants.UserTable
import com.sanchari.bus.data.local.UserDatabaseHelper
import com.sanchari.bus.data.model.RecentSearch
import com.sanchari.bus.data.model.SubmissionLog
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
        require(user.uuid.isNotBlank()) { "Contributor UUID is required" }
        try {
            UserDatabaseHelper(context).use { helper ->
                val db = helper.writableDatabase
                db.beginTransaction()
                try {
                    val storedUuid = db.query(
                        UserTable.TABLE_NAME, arrayOf(UserTable.COLUMN_UUID),
                        null, null, null, null, null, "1"
                    ).use {
                        if (it.moveToFirst()) it.getString(0) else null
                    }
                    if (storedUuid != null && storedUuid != user.uuid) {
                        throw SQLiteException("Profile edits cannot replace the contributor identity")
                    }
                    val values = ContentValues().apply {
                        put(UserTable.COLUMN_NAME, user.name)
                        put(UserTable.COLUMN_EMAIL, user.email)
                        put(UserTable.COLUMN_PHONE, user.phone)
                        put(UserTable.COLUMN_PLACE, user.place)
                    }
                    if (storedUuid == null) {
                        values.put(UserTable.COLUMN_UUID, user.uuid)
                        db.insertOrThrow(UserTable.TABLE_NAME, null, values)
                    } else {
                        if (db.update(UserTable.TABLE_NAME, values, "${UserTable.COLUMN_UUID} = ?", arrayOf(user.uuid)) < 1) {
                            throw SQLiteException("Contributor profile could not be updated")
                        }
                    }
                    db.setTransactionSuccessful()
                    return true
                } finally {
                    db.endTransaction()
                }
            }
        } catch (e: SQLiteException) {
            Log.e(TAG, "Error saving user", e)
            return false
        }
    }

    /**
     * Retrieves the local profile, creating a persisted identity even without personal details.
     */
    fun getUser(context: Context): User {
        UserDatabaseHelper(context).use { helper ->
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                val user = db.query(
                    UserTable.TABLE_NAME, null, null, null, null, null, null, "1"
                ).use { cursor ->
                    if (cursor.moveToFirst()) {
                        fun text(column: String) = cursor.getString(cursor.getColumnIndexOrThrow(column)).orEmpty()
                        val storedUuid = text(UserTable.COLUMN_UUID)
                        val uuid = storedUuid.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
                        if (storedUuid.isBlank()) {
                            val updated = db.update(UserTable.TABLE_NAME, ContentValues().apply {
                                put(UserTable.COLUMN_UUID, uuid)
                            }, "${UserTable.COLUMN_USER_ID} = ?",
                                arrayOf(cursor.getLong(cursor.getColumnIndexOrThrow(UserTable.COLUMN_USER_ID)).toString()))
                            if (updated != 1) throw SQLiteException("Contributor identity could not be persisted")
                        }
                        User(uuid, text(UserTable.COLUMN_NAME), text(UserTable.COLUMN_EMAIL),
                            text(UserTable.COLUMN_PHONE), text(UserTable.COLUMN_PLACE))
                    } else {
                        val uuid = UUID.randomUUID().toString()
                        db.insertOrThrow(UserTable.TABLE_NAME, null, ContentValues().apply {
                            put(UserTable.COLUMN_UUID, uuid)
                        })
                        User(uuid, "", "", "", "")
                    }
                }
                db.setTransactionSuccessful()
                return user
            } catch (e: SQLiteException) {
                Log.e(TAG, "Failed to load or persist contributor identity", e)
                throw e
            } finally {
                db.endTransaction()
            }
        }
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

    /**
     * Adds a submission log entry after a successful suggestion upload.
     */
    fun addSubmissionLog(context: Context, busName: String, busType: String, startingPlace: String) {
        val dbHelper = UserDatabaseHelper(context)
        val db = dbHelper.writableDatabase
        try {
            val values = ContentValues().apply {
                put(SubmissionLogTable.COLUMN_BUS_NAME, busName)
                put(SubmissionLogTable.COLUMN_BUS_TYPE, busType)
                put(SubmissionLogTable.COLUMN_STARTING_PLACE, startingPlace)
                put(SubmissionLogTable.COLUMN_SUBMITTED_AT, System.currentTimeMillis())
            }
            db.insert(SubmissionLogTable.TABLE_NAME, null, values)
            Log.i(TAG, "Submission log added: $busName")
        } catch (e: Exception) {
            Log.e(TAG, "Error adding submission log", e)
        } finally {
            db.close()
        }
    }

    /**
     * Retrieves all submission logs, most recent first.
     */
    @SuppressLint("Range")
    fun getSubmissionLogs(context: Context): List<SubmissionLog> {
        val dbHelper = UserDatabaseHelper(context)
        val db = dbHelper.readableDatabase
        val logs = mutableListOf<SubmissionLog>()

        try {
            db.query(
                SubmissionLogTable.TABLE_NAME,
                null, null, null, null, null,
                "${SubmissionLogTable.COLUMN_SUBMITTED_AT} DESC"
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    val logIdIndex = cursor.getColumnIndex(SubmissionLogTable.COLUMN_LOG_ID)
                    val busNameIndex = cursor.getColumnIndex(SubmissionLogTable.COLUMN_BUS_NAME)
                    val busTypeIndex = cursor.getColumnIndex(SubmissionLogTable.COLUMN_BUS_TYPE)
                    val startingPlaceIndex = cursor.getColumnIndex(SubmissionLogTable.COLUMN_STARTING_PLACE)
                    val submittedAtIndex = cursor.getColumnIndex(SubmissionLogTable.COLUMN_SUBMITTED_AT)

                    do {
                        logs.add(
                            SubmissionLog(
                                logId = if (logIdIndex != -1) cursor.getInt(logIdIndex) else 0,
                                busName = if (busNameIndex != -1) cursor.getString(busNameIndex) else "",
                                busType = if (busTypeIndex != -1) cursor.getString(busTypeIndex) ?: "" else "",
                                startingPlace = if (startingPlaceIndex != -1) cursor.getString(startingPlaceIndex) ?: "" else "",
                                submittedAt = if (submittedAtIndex != -1) cursor.getLong(submittedAtIndex) else 0L
                            )
                        )
                    } while (cursor.moveToNext())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting submission logs", e)
        } finally {
            db.close()
        }
        return logs
    }
}