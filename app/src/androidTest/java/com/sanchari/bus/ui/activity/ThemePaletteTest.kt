package com.sanchari.bus.ui.activity

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.use
import androidx.core.graphics.ColorUtils
import androidx.core.view.children
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.MaterialShapeDrawable
import com.sanchari.bus.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemePaletteTest {
    @Test
    fun allScreensUseBlueBrandingAndAccessibleGreenPrimaryActionsInBothThemes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
                val config = Configuration(instrumentation.targetContext.resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
                }
                val context = ContextThemeWrapper(
                    instrumentation.targetContext.createConfigurationContext(config),
                    R.style.Theme_SanchariApp_NoActionBar
                )
                val parent = FrameLayout(context)
                val primary = ContextCompat.getColor(context, R.color.brand_primary)
                val header = ContextCompat.getColor(context, R.color.brand_header)
                val green = ContextCompat.getColor(context, R.color.action_green)
                val onGreen = ContextCompat.getColor(context, R.color.action_on_green)
                val hsv = FloatArray(3)
                Color.colorToHSV(primary, hsv)
                assertTrue("Primary branding must be blue", hsv[0] in 200f..240f)
                Color.colorToHSV(green, hsv)
                assertTrue("Primary actions must be green", hsv[0] in 100f..150f)
                assertTrue(ColorUtils.calculateContrast(Color.WHITE, header) >= 4.5)
                assertTrue(ColorUtils.calculateContrast(onGreen, green) >= 4.5)
                val surface = MaterialColors.getColor(parent, com.google.android.material.R.attr.colorSurface)
                assertTrue("Blue text must remain readable on surfaces",
                    ColorUtils.calculateContrast(primary, surface) >= 4.5)

                for ((layout, buttonId) in listOf(
                    R.layout.activity_main to R.id.searchButton,
                    R.layout.activity_bus_details to R.id.buttonSuggestEdit,
                    R.layout.activity_introduction to R.id.buttonGetStarted,
                    R.layout.activity_suggest_edit to R.id.buttonApplyChanges,
                    R.layout.activity_confirm to R.id.buttonApplySuggestion
                )) {
                    val view = LayoutInflater.from(context).inflate(layout, parent, false)
                    val button = view.findViewById<MaterialButton>(buttonId)
                    assertEquals(green, button.backgroundTintList?.getColorForState(button.drawableState, 0))
                    assertEquals(onGreen, button.currentTextColor)
                    button.isPressed = true
                    assertEquals(green, button.backgroundTintList?.getColorForState(button.drawableState, 0))
                    button.isPressed = false
                    button.isEnabled = false
                    assertNotEquals("Disabled action must not look enabled", green,
                        button.backgroundTintList?.getColorForState(button.drawableState, 0))
                    assertNotEquals(onGreen, button.currentTextColor)
                    val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
                    if (toolbar != null) {
                        assertEquals(header, backgroundColor(toolbar.background))
                        assertToolbarTitleIsWhite(toolbar)
                        val appBar = toolbar.parent as? AppBarLayout
                        if (appBar != null) assertEquals(header, backgroundColor(appBar.background))
                    }
                }
                for (layout in listOf(R.layout.activity_search_results, R.layout.activity_bus_details)) {
                    val view = LayoutInflater.from(context).inflate(layout, parent, false)
                    val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
                    assertEquals(header, backgroundColor(toolbar.background))
                    assertToolbarTitleIsWhite(toolbar)
                    if (layout == R.layout.activity_search_results) {
                        val title = view.findViewById<TextView>(R.id.tvToolbarTitle)
                        assertEquals(header, backgroundColor(title.background))
                        assertEquals(Color.WHITE, title.currentTextColor)
                    }
                }
                context.obtainStyledAttributes(intArrayOf(android.R.attr.statusBarColor, android.R.attr.windowLightStatusBar)).use {
                    assertEquals(header, it.getColor(0, 0))
                    assertFalse(it.getBoolean(1, true))
                }
                val baseContext = ContextThemeWrapper(context, R.style.Theme_SanchariApp)
                val actionBarStyle = baseContext.obtainStyledAttributes(intArrayOf(androidx.appcompat.R.attr.actionBarStyle)).use {
                    it.getResourceId(0, 0)
                }
                baseContext.obtainStyledAttributes(actionBarStyle, intArrayOf(androidx.appcompat.R.attr.background)).use {
                    assertEquals(header, it.getColor(0, 0))
                }
                assertEquals(Color.rgb(211, 47, 47), ContextCompat.getColor(context, R.color.service_not_running))
                assertEquals(if (night == Configuration.UI_MODE_NIGHT_YES) Color.rgb(255, 213, 79)
                    else Color.rgb(148, 105, 0), ContextCompat.getColor(context, R.color.service_slow))
            }
        }
    }

    private fun assertToolbarTitleIsWhite(toolbar: MaterialToolbar) {
        toolbar.title = "Theme"
        val title = toolbar.children.filterIsInstance<TextView>().single { it.text == "Theme" }
        assertEquals(Color.WHITE, title.currentTextColor)
    }

    private fun backgroundColor(drawable: Drawable): Int = when (drawable) {
        is ColorDrawable -> drawable.color
        is MaterialShapeDrawable -> requireNotNull(drawable.fillColor).defaultColor
        else -> error("Unexpected header background: ${drawable.javaClass.name}")
    }
}
