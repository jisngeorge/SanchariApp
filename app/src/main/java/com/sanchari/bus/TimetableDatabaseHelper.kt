package com.sanchari.bus

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * This is a read-only helper for the Timetable Database.
 * It does NOT create or upgrade the database. Its only job is to open
 * the database file that is downloaded or copied from assets.
 */
class TimetableDatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, DatabaseConstants.TIMETABLE_DATABASE_NAME, null, 1) {

    companion object {
        private const val TAG = "TimetableDbHelper"
    }

    override fun onCreate(db: SQLiteDatabase?) {
        // Not used, as this is a pre-packaged database.
        // We can add the schema here for reference, but it won't be executed
        // unless the database file is missing AND this helper is asked to create it.
        Log.w(TAG, "onCreate called, but this should be a pre-packaged database.")

        // --- UPDATED: Added all tables from the new schema ---
        try {
            db?.execSQL(DatabaseConstants.BusServiceTable.CREATE_TABLE)
            db?.execSQL(DatabaseConstants.StopTable.CREATE_TABLE)
            db?.execSQL(DatabaseConstants.RouteStopTable.CREATE_TABLE)
            Log.i(TAG, "TimetableDatabase safety tables created.")
        } catch (e: Exception) {
            Log.e(TAG, "Error creating safety tables for TimetableDatabase", e)
        }
        // --- END OF UPDATE ---
    }

    override fun onUpgrade(db: SQLiteDatabase?, oldVersion: Int, newVersion: Int) {
        // Not used. The database is replaced by DatabaseManager.
        Log.w(TAG, "onUpgrade called, but database should be replaced, not upgraded.")
    }

    override fun onDowngrade(db: SQLiteDatabase?, oldVersion: Int, newVersion: Int) {
        // Not used.
        Log.w(TAG, "onDowngrade called, but database should be replaced, not upgraded.")
    }
}