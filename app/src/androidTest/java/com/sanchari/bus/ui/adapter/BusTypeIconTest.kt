package com.sanchari.bus.ui.adapter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sanchari.bus.R
import com.sanchari.bus.data.model.BusService
import com.sanchari.bus.data.model.RouteSearchResult
import com.sanchari.bus.databinding.ActivityMainBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BusTypeIconTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun busIconsKeepTheirTwoPartColoursInBothThemes() {
        instrumentation.runOnMainSync {
            for (night in listOf(false, true)) {
                val configuration = android.content.res.Configuration(instrumentation.targetContext.resources.configuration)
                configuration.uiMode = (configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (night) android.content.res.Configuration.UI_MODE_NIGHT_YES else android.content.res.Configuration.UI_MODE_NIGHT_NO
                val context = ContextThemeWrapper(
                    instrumentation.targetContext.createConfigurationContext(configuration), R.style.Theme_SanchariApp
                )
                val cream = Color.parseColor("#E6D9B8")
                for ((resource, upper, lower) in listOf(
                    Triple(R.drawable.ic_bus_placeholder, Color.parseColor("#808080"), Color.parseColor("#303030")),
                    Triple(R.drawable.ic_bus_deluxe, cream, Color.parseColor("#a01360")),
                    Triple(R.drawable.ic_bus_minnal, cream, Color.parseColor("#5f42a8")),
                    Triple(R.drawable.ic_bus_town_to_town, Color.parseColor("#64B5E3"), cream)
                )) {
                    val bitmap = render(requireNotNull(AppCompatResources.getDrawable(context, resource)))
                    assertEquals(upper, bitmap.getPixel(120, 60))
                    assertEquals(lower, bitmap.getPixel(120, 140))
                    assertEquals(Color.TRANSPARENT, bitmap.getPixel(0, 0))
                }
                val home = ActivityMainBinding.inflate(LayoutInflater.from(context))
                val searchIcon = home.busNameInputLayout.findViewById<ImageView>(
                    com.google.android.material.R.id.text_input_start_icon
                )
                val searchBitmap = render(searchIcon.drawable)
                assertEquals(Color.parseColor("#808080"), searchBitmap.getPixel(120, 60))
                assertEquals(Color.parseColor("#303030"), searchBitmap.getPixel(120, 140))
                // Cream remains visibly different from white, rather than an almost-white fill.
                assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(cream, Color.WHITE) >= 1.4)
            }
        }
    }

    @Test
    fun recycledRowsRecognizeTownToTownAndRestoreFallbackForUnknownTypes() {
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_SanchariApp)
            val adapter = BusServiceAdapter {}
            val row = adapter.onCreateViewHolder(FrameLayout(context), 0)
            for (type in listOf("Town to Town", "TOWN TO TOWN", " town to town ", "Unknown")) {
                val service = BusService("test", "Test bus", type, true, 0L, "08:00", "09:00")
                row.bind(RouteSearchResult(service, "Start", "End", 1, 2))
                val unknown = type == "Unknown"
                assertEquals(context.getString(
                    if (unknown) R.string.label_bus_unspecified else R.string.label_bus_town_to_town
                ), row.itemView.findViewById<TextView>(R.id.serviceType).text.toString())
                val bitmap = render(row.itemView.findViewById<ImageView>(R.id.serviceIcon).drawable)
                assertEquals(Color.parseColor(if (unknown) "#808080" else "#64B5E3"), bitmap.getPixel(120, 60))
                assertEquals(type, service.type)
            }
        }
    }

    private fun render(drawable: Drawable): Bitmap {
        val bitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, bitmap.width, bitmap.height)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }
}
