package com.sanchari.bus.data.model

data class SavedDraft(
    val draftId: String,
    val fileName: String,
    val jsonPayload: String,
    val displayName: String,
    val lastModifiedTime: Long
)