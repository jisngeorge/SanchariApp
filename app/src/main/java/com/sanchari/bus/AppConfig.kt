package com.sanchari.bus

import kotlinx.serialization.Serializable

/**
 * Represents the local configuration stored in assets/app_config.json.
 * This provides the initial links for the app.
 */
@Serializable
data class AppConfig(
    val remoteVersionsUrl: String,
    val communityDataUrl: String,
    val latestAppUrl: String? = null
)