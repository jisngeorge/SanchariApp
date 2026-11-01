package com.sanchari.bus.ui.helper

import android.content.Context
import androidx.recyclerview.widget.DiffUtil
import com.sanchari.bus.R
import com.sanchari.bus.data.model.BusService
import com.sanchari.bus.data.model.BusStop
import org.json.JSONObject

object ContributionPreview {
    enum class Change { UNCHANGED, ADDED, REMOVED }
    enum class Field { DETAIL, BUS_NAME, BUS_TYPE }
    data class Line(
        val text: String,
        val change: Change = Change.UNCHANGED,
        val field: Field = Field.DETAIL
    )
    private data class Stop(val id: String, val name: String, val time: String)

    fun build(
        context: Context,
        suggestion: JSONObject,
        original: BusService? = null,
        originalStops: List<BusStop> = emptyList(),
        includeUsername: Boolean = true
    ): List<Line> {
        fun label(id: Int, value: String) = context.getString(R.string.preview_field, context.getString(id), value)
        val type = suggestion.getString("type")
        if (type == "new_schedule" || type == "edit_schedule") {
            val service = suggestion.getJSONObject("service")
            val isNew = type == "new_schedule"
            val lines = mutableListOf<Line>()
            if (!isNew && original == null) lines += Line(context.getString(R.string.preview_original_unavailable))
            fun field(id: Int, old: String?, value: String, field: Field) {
                if (isNew) {
                    lines += Line(label(id, value), Change.ADDED, field)
                } else if (old != null && old != value) {
                    lines += Line(label(id, old), Change.REMOVED, field)
                    lines += Line(label(id, value), Change.ADDED, field)
                } else {
                    lines += Line(label(id, value), field = field)
                }
            }
            field(R.string.hint_bus_service_name, original?.name, service.getString("name"), Field.BUS_NAME)
            field(R.string.preview_bus_type, original?.type, service.getString("type"), Field.BUS_TYPE)
            val array = suggestion.getJSONArray("stops")
            val stops = List(array.length()) { index ->
                val stop = array.getJSONObject(index)
                Stop(stop.getString("stopId"), stop.getString("locationName"), stop.getString("scheduledTime"))
            }
            val before = originalStops.map { Stop(it.stopId.toString(), it.locationName, it.scheduledTime) }
            fun stopLine(stop: Stop, change: Change) = Line(
                context.getString(R.string.preview_stop, stop.name,
                    TimetableTimeFormatter.format(stop.time).ifBlank { "--:--" }), change
            )
            if (isNew || original == null) {
                lines += stops.map { stopLine(it, if (isNew) Change.ADDED else Change.UNCHANGED) }
            } else {
                // Myers diff preserves repeated visits and treats reordering as deletion/addition.
                val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                    override fun getOldListSize() = before.size
                    override fun getNewListSize() = stops.size
                    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                        before[oldItemPosition] == stops[newItemPosition]
                    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) = true
                }, false)
                var next = 0
                before.forEachIndexed { index, stop ->
                    val match = diff.convertOldPositionToNew(index)
                    if (match == DiffUtil.DiffResult.NO_POSITION) {
                        lines += stopLine(stop, Change.REMOVED)
                    } else {
                        while (next < match) lines += stopLine(stops[next++], Change.ADDED)
                        lines += stopLine(stop, Change.UNCHANGED)
                        next = match + 1
                    }
                }
                while (next < stops.size) lines += stopLine(stops[next++], Change.ADDED)
            }
            suggestion.optString("editNotes").takeIf { it.isNotBlank() }?.let {
                lines += Line(label(R.string.preview_notes, it))
            }
            return lines
        }
        val lines = mutableListOf<Line>()
        if (suggestion.has("serviceId")) {
            lines += Line(label(R.string.hint_bus_service_name, original?.name
                ?: suggestion.getString("serviceId")), field = Field.BUS_NAME)
            if (original != null) {
                lines += Line(label(R.string.preview_bus_type, original.type), field = Field.BUS_TYPE)
            }
        }
        when (type) {
            "rating" -> {
                for ((key, title) in listOf(
                    "punctuality_5" to R.string.label_punctuality_5,
                    "drive_5" to R.string.label_driving_comfort_5,
                    "behaviour_5" to R.string.label_behaviour_5
                )) lines += Line(label(title, context.getString(R.string.preview_rating, suggestion.getString(key))))
            }
            "comment" -> {
                lines += Line(suggestion.getString("commentText"))
                val showUsername = suggestion.getBoolean("showUsername")
                if (includeUsername) {
                    lines += Line(context.getString(if (showUsername)
                        R.string.preview_username_shown else R.string.preview_username_hidden))
                }
            }
            "running_status_suggestion" -> lines += Line(suggestion.getString("suggestedStatus"))
            "admin_message" -> lines += Line(suggestion.getString("messageText"))
            else -> throw IllegalArgumentException("Unsupported contribution type")
        }
        return lines
    }
}
