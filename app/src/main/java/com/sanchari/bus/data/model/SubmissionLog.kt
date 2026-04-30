package com.sanchari.bus.data.model

data class SubmissionLog(
    val logId: Int,
    val busName: String,
    val busType: String,
    val startingPlace: String,
    val submittedAt: Long // epoch millis
)

