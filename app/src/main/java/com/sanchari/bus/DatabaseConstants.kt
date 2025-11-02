package com.sanchari.bus

/**
 * A central place to store all database table and column names.
 */
object DatabaseConstants {

    // --- Database File Names ---
    const val TIMETABLE_DATABASE_NAME = "TimetableDatabase.db"
    const val COMMUNITY_DATABASE_NAME = "CommunityDatabase.db"
    const val USER_DATABASE_NAME = "UserDatabase.db"

    // --- Version Info ---
    const val TIMETABLE_DB_VERSION_KEY = "timetable_db_version"
    const val COMMUNITY_DB_VERSION_KEY = "community_db_version"
    const val DEFAULT_DB_VERSION = 1 // The version bundled with the app
    const val USER_DATABASE_VERSION = 1 // This is the version for the local user DB schema

    /**
     * Constants for the UserDatabase.db
     */
    object UserTable {
        const val TABLE_NAME = "User"
        const val COLUMN_UUID = "uuid" // Added this missing constant
        const val COLUMN_NAME = "name"
        const val COLUMN_EMAIL = "email"
        const val COLUMN_PHONE = "phone"
        const val COLUMN_PLACE = "place"

        // Create table statement
        const val CREATE_TABLE = "CREATE TABLE $TABLE_NAME (" +
                "$COLUMN_UUID TEXT PRIMARY KEY, " +
                "$COLUMN_NAME TEXT, " +
                "$COLUMN_EMAIL TEXT, " +
                "$COLUMN_PHONE TEXT, " +
                "$COLUMN_PLACE TEXT)"
    }

    object RecentViewTable {
        const val TABLE_NAME = "RecentView"
        const val COLUMN_RECENT_ID = "recentId" // PK
        const val COLUMN_SERVICE_ID = "serviceId"
        const val COLUMN_SERVICE_NAME = "serviceName"
        const val COLUMN_VIEWED_TIMESTAMP = "viewedTimestamp"

        // Create table statement
        const val CREATE_TABLE = "CREATE TABLE $TABLE_NAME (" +
                "$COLUMN_RECENT_ID INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "$COLUMN_SERVICE_ID TEXT, " +
                "$COLUMN_SERVICE_NAME TEXT, " +
                "$COLUMN_VIEWED_TIMESTAMP INTEGER)"
    }

    /**
     * Constants for the TimetableDatabase.db
     */
    object BusServiceTable {
        const val TABLE_NAME = "BusService"
        const val COLUMN_SERVICE_ID = "serviceId" // PK
        const val COLUMN_NAME = "name"
        const val COLUMN_TYPE = "type"
        const val COLUMN_IS_RUNNING = "isRunning"
        const val COLUMN_LAST_REPORTED_TIME = "lastReportedTime"
    }

    object BusStopTable {
        const val TABLE_NAME = "BusStop"
        const val COLUMN_STOP_ID = "stopId" // PK
        const val COLUMN_SERVICE_ID = "serviceId" // FK
        const val COLUMN_LOCATION_NAME = "locationName"
        const val COLUMN_LATITUDE = "latitude"
        const val COLUMN_LONGITUDE = "longitude"
        const val COLUMN_SCHEDULED_TIME = "scheduledTime"
        const val COLUMN_STOP_ORDER = "stopOrder"
    }

    /**
     * Constants for the CommunityDatabase.db
     */
    object BusRatingTable {
        const val TABLE_NAME = "BusRating"
        const val COLUMN_SERVICE_ID = "serviceId" // PK
        const val COLUMN_AVG_PUNCTUALITY = "avgPunctuality"
        const val COLUMN_AVG_DRIVE = "avgDrive"
        const val COLUMN_AVG_BEHAVIOUR = "avgBehaviour"
        const val COLUMN_RATING_COUNT = "ratingCount"
    }

    object UserCommentTable {
        const val TABLE_NAME = "UserComment"
        const val COLUMN_COMMENT_ID = "commentId" // PK
        const val COLUMN_SERVICE_ID = "serviceId" // FK
        const val COLUMN_USERNAME = "username"
        const val COLUMN_COMMENT_TEXT = "commentText"
        const val COLUMN_COMMENT_DATE = "commentDate"
    }
}


