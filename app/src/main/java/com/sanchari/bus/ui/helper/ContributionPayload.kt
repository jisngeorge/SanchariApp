package com.sanchari.bus.ui.helper

import com.sanchari.bus.data.model.User
import org.json.JSONObject

object ContributionPayload {
    fun wrap(suggestionJson: String, user: User): String {
        require(user.uuid.isNotBlank()) { "Contributor UUID is required" }
        val contributor = JSONObject().put("uuid", user.uuid)
        for ((key, value) in listOf(
            "name" to user.name, "email" to user.email, "phone" to user.phone, "place" to user.place
        )) {
            value.trim().takeIf { it.isNotEmpty() }?.let { contributor.put(key, it) }
        }
        val suggestion = JSONObject(suggestionJson)
        if (suggestion.getString("type") == "comment" && !contributor.has("name")) {
            suggestion.put("showUsername", false)
        }
        return JSONObject().put("submittedBy", contributor).put("suggestion", suggestion).toString(2)
    }
}
