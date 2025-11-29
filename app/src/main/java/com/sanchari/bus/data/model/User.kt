package com.sanchari.bus.data.model

/**
 * Data class to hold user information.
 *
 * @param uuid A unique identifier for this app installation.
 * @param name The user's full name.
 * @param email The user's email address.
 * @param phone The user's phone number.
 * @param place The user's city or place.
 */
data class User(
    val uuid: String,
    val name: String,
    val email: String,
    val phone: String,
    val place: String
)

