package com.sanchari.bus

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
            Log.i(TAG, "UserDatabase tables created successfully.")

            // We can also insert the initial User row with a UUID here
            // (Or we can do it from the MainActivity on first launch)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating UserDatabase", e)
        }
    }

    /**
     * Called when the database needs to be upgraded.
     * (Not needed for v1)
     */
    override fun onUpgrade(db: SQLiteDatabase?, oldVersion: Int, newVersion: Int) {
        // For now, we can just drop and recreate if needed,
        // but a real app would need a migration strategy.
        // db?.execSQL("DROP TABLE IF EXISTS ${DatabaseConstants.UserTable.TABLE_NAME}")
        // db?.execSQL("DROP TABLE IF EXISTS ${DatabaseConstants.RecentViewTable.TABLE_NAME}")
        // onCreate(db)
    }
}

