package com.sanchari.bus

/**
 * Data class to hold bus rating information from the CommunityDatabase.
 */
data class BusRating(
    val serviceId: String,
    val avgPunctuality: Float,
    val avgDrive: Float,
    val avgBehaviour: Float,
    val ratingCount: Int
)
