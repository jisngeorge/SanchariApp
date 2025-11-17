package com.sanchari.bus

/**
 * Data class to hold a user comment from the CommunityDatabase.
 */
data class UserComment(
    val commentId: Int,
    val serviceId: String,
    val username: String,
    val commentText: String,
    val commentDate: Long
)