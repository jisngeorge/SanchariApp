package com.sanchari.bus.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * Local-only database helper for user data (UUID, preferences, recents).
 * This database is CREATED LOCALLY and NEVER replaced by the server.
 */
class UserDatabaseHelper(context: Context) :
    SQLiteOpenHelper(
        context,
        DatabaseConstants.USER_DATABASE_NAME,
        null,
        DatabaseConstants.USER_DATABASE_VERSION // Use the constant from DatabaseConstants
    ) {

    companion object {
        // This local version is no longer needed
        // private const val DATABASE_VERSION = 1
        private const val TAG = "UserDatabaseHelper"
    }

    override fun onCreate(db: SQLiteDatabase?) {
        try {
            db?.execSQL(DatabaseConstants.UserTable.CREATE_TABLE)
            db?.execSQL(DatabaseConstants.RecentViewTable.CREATE_TABLE)
            db?.execSQL(DatabaseConstants.SubmissionLogTable.CREATE_TABLE)
            Log.i(TAG, "UserDatabase tables created successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Error creating UserDatabase", e)
        }
    }

    override fun onUpgrade(db: SQLiteDatabase?, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db?.execSQL(DatabaseConstants.SubmissionLogTable.CREATE_TABLE)
            Log.i(TAG, "UserDatabase upgraded to version 2: added SubmissionLog table")
        }
    }
}