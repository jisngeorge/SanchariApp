package com.sanchari.bus.ui.activity

import android.content.res.Configuration
import android.graphics.Rect
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.textfield.TextInputLayout
import com.sanchari.bus.R
import com.sanchari.bus.data.model.EditableStop
import com.sanchari.bus.ui.adapter.StopEditAdapter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class ScheduleEditorLayoutTest {
    @Test
    fun stopFieldsKeepLabelsSeparateFromValuesOnNarrowScreensInBothThemes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
                for (fontScale in listOf(1f, 1.3f)) {
                    val config = Configuration(instrumentation.targetContext.resources.configuration).apply {
                        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
                        this.fontScale = fontScale
                    }
                    val context = ContextThemeWrapper(
                        instrumentation.targetContext.createConfigurationContext(config),
                        R.style.Theme_SanchariApp_NoActionBar
                    )
                    val parent = FrameLayout(context)
                    val stops = mutableListOf(EditableStop("A long existing stop name", "14:30", 1, 21))
                    val adapter = StopEditAdapter(stops, {}, {})
                    val holder = adapter.onCreateViewHolder(parent, 0)
                    parent.addView(holder.itemView)
                    adapter.onBindViewHolder(holder, 0)
                    val width = (288 * context.resources.displayMetrics.density).roundToInt()
                    fun layout() {
                        parent.measure(
                            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                        )
                        parent.layout(0, 0, parent.measuredWidth, parent.measuredHeight)
                    }
                    layout()
                    val binding = holder.binding
                    assertEquals(TextInputLayout.BOX_BACKGROUND_OUTLINE, binding.stopNameLayout.boxBackgroundMode)
                    assertEquals("A long existing stop name", binding.editTextStopName.text.toString())
                    val inputBounds = Rect()
                    binding.editTextStopName.getDrawingRect(inputBounds)
                    binding.stopNameLayout.offsetDescendantRectToMyCoords(binding.editTextStopName, inputBounds)
                    val textTop = inputBounds.top + binding.editTextStopName.baseline +
                        binding.editTextStopName.paint.fontMetrics.top
                    val floatingLabelHeight = ceil(12 * context.resources.displayMetrics.scaledDensity).toInt()
                    assertTrue("Stop label must be above text (text top=$textTop, label height=$floatingLabelHeight)",
                        textTop >= floatingLabelHeight)
                    val horizontalInset = (16 * context.resources.displayMetrics.density).roundToInt()
                    assertTrue("Outlined name needs an inset from its border",
                        binding.editTextStopName.compoundPaddingLeft >= horizontalInset)
                    assertTrue("Full-width name must be below row controls",
                        binding.stopNameLayout.top >= binding.buttonRemoveStop.bottom)
                    assertEquals("2:30 PM", binding.editTextScheduledTime.text.toString())
                    for (button in listOf(binding.buttonMoveUp, binding.buttonMoveDown,
                        binding.buttonRemoveStop, binding.buttonSuggestTime)) {
                        val minimum = (48 * context.resources.displayMetrics.density).roundToInt()
                        assertTrue(button.measuredWidth >= minimum)
                        assertTrue(button.measuredHeight >= minimum)
                    }
                    binding.stopNameLayout.error = context.getString(R.string.editor_ambiguous_stop)
                    layout()
                    val timeBounds = Rect()
                    binding.editTextScheduledTime.getDrawingRect(timeBounds)
                    binding.root.offsetDescendantRectToMyCoords(binding.editTextScheduledTime, timeBounds)
                    assertTrue("Error text must not overlap the time input", timeBounds.top >= binding.stopNameLayout.bottom)
                    adapter.updateTime(0, "15:00")
                    adapter.onBindViewHolder(holder, 0)
                    assertTrue("Time highlight must preserve the card surface", binding.root.strokeWidth > 0)
                    assertEquals(listOf(21), stops.map { it.originalStopId })
                }
            }
        }
    }
}
