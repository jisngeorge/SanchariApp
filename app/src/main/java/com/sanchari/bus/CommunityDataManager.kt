package com.sanchari.bus

import android.annotation.SuppressLint
import android.content.Context
import android.database.sqlite.SQLiteException
import android.util.Log

/**
 * Manages all read operations from the CommunityDatabase.
 */
object CommunityDataManager {

    private const val TAG = "CommunityDataManager"

    /**
     * Fetches the bus rating for a specific service.
     * Returns null if no rating is found.
     */
    @SuppressLint("Range")
    fun getBusRating(context: Context, serviceId: String): BusRating? {
        val dbHelper = CommunityDatabaseHelper(context)
        val db = dbHelper.readableDatabase
        var busRating: BusRating? = null

        try {
            db.query(
                DatabaseConstants.BusRatingTable.TABLE_NAME,
                null, // all columns
                "${DatabaseConstants.BusRatingTable.COLUMN_SERVICE_ID} = ?", // selection
                arrayOf(serviceId), // selectionArgs
                null, null, null, "1"
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    busRating = BusRating(
                        serviceId = cursor.getString(cursor.getColumnIndex(DatabaseConstants.BusRatingTable.COLUMN_SERVICE_ID)),
                        avgPunctuality = cursor.getFloat(cursor.getColumnIndex(DatabaseConstants.BusRatingTable.COLUMN_AVG_PUNCTUALITY)),
                        avgDrive = cursor.getFloat(cursor.getColumnIndex(DatabaseConstants.BusRatingTable.COLUMN_AVG_DRIVE)),
                        avgBehaviour = cursor.getFloat(cursor.getColumnIndex(DatabaseConstants.BusRatingTable.COLUMN_AVG_BEHAVIOUR)),
                        ratingCount = cursor.getInt(cursor.getColumnIndex(DatabaseConstants.BusRatingTable.COLUMN_RATING_COUNT))
                    )
                }
            }
        } catch (e: SQLiteException) {
            Log.e(TAG, "Error getting bus rating for $serviceId", e)
        } finally {
            db.close()
        }
        return busRating
    }

    /**
     * Fetches all comments for a specific service.
     * Returns an empty list if no comments are found.
     */
    @SuppressLint("Range")
    fun getComments(context: Context, serviceId: String): List<UserComment> {
        val dbHelper = CommunityDatabaseHelper(context)
        val db = dbHelper.readableDatabase
        val comments = mutableListOf<UserComment>()

        try {
            db.query(
                DatabaseConstants.UserCommentTable.TABLE_NAME,
                null, // all columns
                "${DatabaseConstants.UserCommentTable.COLUMN_SERVICE_ID} = ?", // selection
                arrayOf(serviceId), // selectionArgs
                null, null,
                "${DatabaseConstants.UserCommentTable.COLUMN_COMMENT_DATE} DESC" // Order by newest first
            ).use { cursor ->
                // Get column indices once
                val commentIdIndex = cursor.getColumnIndex(DatabaseConstants.UserCommentTable.COLUMN_COMMENT_ID)
                val serviceIdIndex = cursor.getColumnIndex(DatabaseConstants.UserCommentTable.COLUMN_SERVICE_ID)
                val usernameIndex = cursor.getColumnIndex(DatabaseConstants.UserCommentTable.COLUMN_USERNAME)
                val commentTextIndex = cursor.getColumnIndex(DatabaseConstants.UserCommentTable.COLUMN_COMMENT_TEXT)
                val commentDateIndex = cursor.getColumnIndex(DatabaseConstants.UserCommentTable.COLUMN_COMMENT_DATE)
                val showUsernameIndex = cursor.getColumnIndex(DatabaseConstants.UserCommentTable.COLUMN_SHOW_USERNAME) // NEW

                while (cursor.moveToNext()) {
                    val comment = UserComment(
                        commentId = cursor.getInt(commentIdIndex),
                        serviceId = cursor.getString(serviceIdIndex),
                        username = cursor.getString(usernameIndex),
                        commentText = cursor.getString(commentTextIndex),
                        commentDate = cursor.getString(commentDateIndex),
                        showUsername = cursor.getInt(showUsernameIndex) == 1 // NEW
                    )
                    comments.add(comment)
                }
            }
        } catch (e: SQLiteException) {
            Log.e(TAG, "Error getting comments for $serviceId", e)
        } finally {
            db.close()
        }
        return comments
    }
}