package com.sanchari.bus

import kotlinx.serialization.Serializable

/**
 * A data class that represents the structure of the version.json file.
 * We use kotlinx.serialization to parse the JSON into this object.
 */
@Serializable
data class ServerVersionInfo(
    val timetable: DatabaseVersion,
    val community: DatabaseVersion,
    val app: AppVersion? = null,
    // If present, the app will update its local configuration to use these URLs
    val versions: String? = null,      // URL for future version.json checks
    val communityData: String? = null  // URL for uploading comments/ratings (Google Script)
)

@Serializable
data class DatabaseVersion(
    val version: Int,
    val url: String
)

@Serializable
data class AppVersion(
    val versionCode: Int,
    val url: String,
    val mandatory: Boolean = false
)