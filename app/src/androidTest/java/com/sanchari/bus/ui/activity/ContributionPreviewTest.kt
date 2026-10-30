package com.sanchari.bus.ui.activity

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sanchari.bus.data.model.BusService
import com.sanchari.bus.data.model.BusStop
import com.sanchari.bus.ui.helper.ContributionPreview
import com.sanchari.bus.ui.helper.ContributionPreview.Change.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContributionPreviewTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val original = BusService("fixture", "Original", "Ordinary", true, 0, "", "")

    @Test
    fun scheduleDiffShowsChangedFieldsAndTimesWithoutLosingRepeatedVisits() {
        val before = listOf(stop(1, "A", "23:00"), stop(2, "B", "23:30"), stop(1, "A", "00:00"))
        val payload = schedule(listOf(
            stop(1, "A", "23:00"), stop(2, "B", "23:40"), stop(1, "A", "00:00"), stop(3, "C", "")
        )).apply { getJSONObject("service").put("name", "Renamed") }
        val untouched = payload.toString()
        val lines = ContributionPreview.build(context, payload, original, before)
        assertEquals(listOf(REMOVED, ADDED, UNCHANGED, UNCHANGED, REMOVED, ADDED, UNCHANGED, ADDED),
            lines.map { it.change })
        assertEquals("A - 11:00 PM", lines[3].text)
        assertEquals("B - 11:30 PM", lines[4].text)
        assertEquals("B - 11:40 PM", lines[5].text)
        assertEquals("A - 12:00 AM", lines[6].text)
        assertEquals("C - --:--", lines[7].text)
        assertEquals(untouched, payload.toString())
    }

    @Test
    fun removalInsertionAndReorderingRetainUnchangedRowsInRouteOrder() {
        val before = listOf(stop(1, "A"), stop(2, "B"), stop(3, "C"), stop(4, "D"))
        val after = listOf(before[0], before[3], before[2], stop(5, "E"))
        val lines = ContributionPreview.build(context, schedule(after), original, before).drop(2)
        assertEquals(before.map { "${it.locationName} - 9:00 AM" },
            lines.filter { it.change != ADDED }.map { it.text })
        assertEquals(after.map { "${it.locationName} - 9:00 AM" },
            lines.filter { it.change != REMOVED }.map { it.text })
        assertTrue(lines.any { it.change == REMOVED })
        assertTrue(lines.any { it.change == ADDED })
    }

    @Test
    fun unchangedSchedulesStayNeutralAndNewSchedulesAreAllAdditions() {
        val stops = listOf(stop(1, "A"), stop(1, "A"), stop(2, "B"))
        assertTrue(ContributionPreview.build(context, schedule(stops), original, stops).all { it.change == UNCHANGED })
        assertTrue(ContributionPreview.build(context,
            schedule(stops).put("type", "new_schedule")).all { it.change == ADDED })
    }

    @Test
    fun missingOriginalDoesNotPretendEveryRowWasAdded() {
        val lines = ContributionPreview.build(context, schedule(listOf(stop(1, "A"))))
        assertTrue(lines.first().text.contains("unavailable"))
        assertTrue(lines.all { it.change == UNCHANGED })
    }

    @Test
    fun ratingCommentStatusAndMessageHavePlainPreviews() {
        val rating = ContributionPreview.build(context, JSONObject()
            .put("type", "rating").put("serviceId", "fixture")
            .put("punctuality_5", 4.5).put("drive_5", 3).put("behaviour_5", 5), original)
        assertEquals(5, rating.size)
        assertTrue(rating[2].text.endsWith("4.5 / 5"))
        val comment = JSONObject().put("type", "comment").put("serviceId", "fixture")
            .put("commentText", "A \"quoted\" comment\nSecond line").put("showUsername", false)
        val hidden = ContributionPreview.build(context, comment, original)
        assertEquals(comment.getString("commentText"), hidden[2].text)
        assertTrue(hidden.last().text.startsWith("Hide"))
        assertTrue(ContributionPreview.build(context, comment.put("showUsername", true), original)
            .last().text.startsWith("Show"))
        val status = ContributionPreview.build(context, JSONObject().put("type", "running_status_suggestion")
            .put("suggestedStatus", "Not Running"))
        assertEquals("Not Running", status.single().text)
        val message = ContributionPreview.build(context, JSONObject().put("type", "admin_message").put("messageText", "Help"))
        assertEquals("Help", message.single().text)
        assertTrue((rating + hidden + status + message).all { it.change == UNCHANGED })
    }

    private fun stop(id: Int, name: String, time: String = "09:00") =
        BusStop(id, "fixture", name, 0.0, 0.0, time, 1)

    private fun schedule(stops: List<BusStop>) = JSONObject().apply {
        put("type", "edit_schedule")
        put("service", JSONObject().put("serviceId", "fixture").put("name", "Original").put("type", "Ordinary"))
        put("stops", JSONArray().apply {
            stops.forEachIndexed { index, stop ->
                put(JSONObject().put("stopId", stop.stopId).put("locationName", stop.locationName)
                    .put("scheduledTime", stop.scheduledTime).put("stopOrder", index + 1))
            }
        })
        put("editNotes", "")
    }
}
