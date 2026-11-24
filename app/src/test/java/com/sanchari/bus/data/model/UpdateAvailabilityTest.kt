package com.sanchari.bus.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateAvailabilityTest {
    @Test
    fun showsOnlyUpdatesThatNeedAttention() {
        val states = mapOf(
            UpdateAvailability() to (false to false),
            UpdateAvailability(timetable = true) to (false to true),
            UpdateAvailability(community = true) to (false to true),
            UpdateAvailability(timetable = true, community = true) to (false to true),
            UpdateAvailability(app = true) to (true to true),
            UpdateAvailability(app = true, timetable = true) to (true to true),
            UpdateAvailability(app = true, community = true) to (true to true),
            UpdateAvailability(app = true, timetable = true, community = true) to (true to true)
        )
        states.forEach { (state, expected) ->
            assertEquals(state.toString(), expected,
                state.showHomeAction(autoDatabaseUpdates = true) to state.showHomeAction(autoDatabaseUpdates = false))
        }
    }
}
