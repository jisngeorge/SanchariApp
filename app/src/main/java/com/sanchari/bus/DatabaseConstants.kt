package com.sanchari.bus

import android.provider.BaseColumns

object DatabaseConstants {

    // --- Database File Names ---
    const val TIMETABLE_DATABASE_NAME = "TimetableDatabase.db"
    const val COMMUNITY_DATABASE_NAME = "CommunityDatabase.db"
    const val USER_DATABASE_NAME = "UserDatabase.db"

    // --- Database Version Constants ---
    // For UserDatabaseHelper
    const val USER_DATABASE_VERSION = 1

    // For SharedPreferences version tracking of downloaded DBs
    const val DEFAULT_DB_VERSION = 1 // The version bundled with the app
    const val TIMETABLE_DB_VERSION_KEY = "timetable_db_version"
    const val COMMUNITY_DB_VERSION_KEY = "community_db_version"


    // --- Table: BusService (TimetableDatabase) ---
    object BusServiceTable {
        const val TABLE_NAME = "BusService"
        const val COLUMN_SERVICE_ID = "serviceId" // PK, TEXT
        const val COLUMN_NAME = "name"
        const val COLUMN_TYPE = "type" // e.g., "Fast", "Superfast"
        const val COLUMN_IS_RUNNING = "isRunning" // 0 or 1
        const val COLUMN_LAST_REPORTED_TIME = "lastReportedTime" // Long (timestamp)

        const val CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS $TABLE_NAME (
                $COLUMN_SERVICE_ID TEXT PRIMARY KEY,
                $COLUMN_NAME TEXT NOT NULL,
                $COLUMN_TYPE TEXT,
                $COLUMN_IS_RUNNING INTEGER DEFAULT 1,
                $COLUMN_LAST_REPORTED_TIME INTEGER DEFAULT 0
            )
        """
    }

    // --- Table: BusStop (TimetableDatabase) ---
    object BusStopTable {
        const val TABLE_NAME = "BusStop"
        const val COLUMN_STOP_ID = "stopId" // PK, INTEGER
        const val COLUMN_SERVICE_ID = "serviceId" // FK, TEXT
        const val COLUMN_LOCATION_NAME = "locationName"
        const val COLUMN_LATITUDE = "latitude"
        const val COLUMN_LONGITUDE = "longitude"
        const val COLUMN_SCHEDULED_TIME = "scheduledTime" // TEXT, e.g., "08:30"
        const val COLUMN_STOP_ORDER = "stopOrder" // INTEGER

        const val CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS $TABLE_NAME (
                $COLUMN_STOP_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_SERVICE_ID TEXT NOT NULL,
                $COLUMN_LOCATION_NAME TEXT NOT NULL,
                $COLUMN_LATITUDE REAL NOT NULL,
                $COLUMN_LONGITUDE REAL NOT NULL,
                $COLUMN_SCHEDULED_TIME TEXT,
                $COLUMN_STOP_ORDER INTEGER NOT NULL
            )
        """
    }

    // --- Table: BusRating (CommunityDatabase) ---
    object BusRatingTable {
        const val TABLE_NAME = "BusRating"
        const val COLUMN_SERVICE_ID = "serviceId" // PK, TEXT
        const val COLUMN_AVG_PUNCTUALITY = "avgPunctuality"
        const val COLUMN_AVG_DRIVE = "avgDrive"
        const val COLUMN_AVG_BEHAVIOUR = "avgBehaviour"
        const val COLUMN_RATING_COUNT = "ratingCount"

        const val CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS $TABLE_NAME (
                $COLUMN_SERVICE_ID TEXT PRIMARY KEY,
                $COLUMN_AVG_PUNCTUALITY REAL DEFAULT 0,
                $COLUMN_AVG_DRIVE REAL DEFAULT 0,
                $COLUMN_AVG_BEHAVIOUR REAL DEFAULT 0,
                $COLUMN_RATING_COUNT INTEGER DEFAULT 0
            )
        """
    }

    // --- Table: UserComment (CommunityDatabase) ---
    object UserCommentTable {
        const val TABLE_NAME = "UserComment"
        const val COLUMN_COMMENT_ID = "commentId" // PK, INTEGER
        const val COLUMN_SERVICE_ID = "serviceId" // FK, TEXT
        const val COLUMN_USERNAME = "username"
        const val COLUMN_COMMENT_TEXT = "commentText"
        const val COLUMN_COMMENT_DATE = "commentDate" // TEXT (ISO 8601)
        const val COLUMN_SHOW_USERNAME = "showUsername" // NEW: INTEGER (0 or 1)

        const val CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS $TABLE_NAME (
                $COLUMN_COMMENT_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_SERVICE_ID TEXT NOT NULL,
                $COLUMN_USERNAME TEXT,
                $COLUMN_COMMENT_TEXT TEXT,
                $COLUMN_COMMENT_DATE TEXT,
                $COLUMN_SHOW_USERNAME INTEGER DEFAULT 1
            )
        """
    }

    // --- Table: User (UserDatabase) ---
    object UserTable {
        const val TABLE_NAME = "User"
        const val COLUMN_USER_ID = "userId" // PK, INTEGER, always 1 for this singleton
        const val COLUMN_NAME = "name"
        const val COLUMN_EMAIL = "email"
        const val COLUMN_PHONE = "phone"
        const val COLUMN_PLACE = "place"
        const val COLUMN_UUID = "uuid" // TEXT, unique app install ID

        const val CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS $TABLE_NAME (
                $COLUMN_USER_ID INTEGER PRIMARY KEY,
                $COLUMN_NAME TEXT,
                $COLUMN_EMAIL TEXT,
                $COLUMN_PHONE TEXT,
                $COLUMN_PLACE TEXT,
                $COLUMN_UUID TEXT NOT NULL
            )
        """
    }

    // --- Table: RecentView (UserDatabase) ---
    object RecentViewTable {
        const val TABLE_NAME = "RecentView"
        const val COLUMN_RECENT_ID = "recentId" // PK, INTEGER
        const val COLUMN_SERVICE_ID = "serviceId" // FK, TEXT
        const val COLUMN_SERVICE_NAME = "serviceName"
        const val COLUMN_VIEWED_TIMESTAMP = "viewedTimestamp" // Long

        const val CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS $TABLE_NAME (
                $COLUMN_RECENT_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_SERVICE_ID TEXT NOT NULL,
                $COLUMN_SERVICE_NAME TEXT,
                $COLUMN_VIEWED_TIMESTAMP INTEGER NOT NULL
            )
        """
    }
}