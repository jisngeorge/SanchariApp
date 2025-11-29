package com.sanchari.bus.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * A "helper" to open the read-only CommunityDatabase.db.
 *
 * This class DOES NOT create or upgrade the database.
 * Its only job is to provide a connection to the pre-packaged
 * database file that we will copy from /assets or download.
 */
class CommunityDatabaseHelper(context: Context) :
    SQLiteOpenHelper(
        context,
        DatabaseConstants.COMMUNITY_DATABASE_NAME,
        null,
        1 // The version number here is a dummy, as we don't manage it
    ) {

    /**
     * This is INTENTIONALLY blank.
     * The database is pre-packaged and copied.
     * We do not create tables manually.
     */
    override fun onCreate(db: SQLiteDatabase?) {
        // Do nothing. Database is pre-built.
    }

    /**
     * This is INTENTIONALLY blank.
     * We do not upgrade the schema. We replace the entire file.
     */
    override fun onUpgrade(db: SQLiteDatabase?, oldVersion: Int, newVersion: Int) {
        // Do nothing. Database is pre-built.
    }
}
