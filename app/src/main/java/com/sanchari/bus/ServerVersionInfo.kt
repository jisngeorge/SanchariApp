package com.sanchari.bus

import kotlinx.serialization.Serializable

/**
 * A data class that represents the structure of the version.json file.
 * We use kotlinx.serialization to parse the JSON into this object.
 */
@Serializable
data class ServerVersionInfo(
    val timetable: DatabaseVersion,
    val community: DatabaseVersion
)

@Serializable
data class DatabaseVersion(
    val version: Int,
    val url: String
)
