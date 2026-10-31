package com.sanchari.bus.ui.helper

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.sanchari.bus.R
import com.sanchari.bus.data.model.GeoPoint

object MapNavigationHelper {
    private fun coordinates(point: GeoPoint) = "${point.latitude},${point.longitude}"

    fun markerUrl(point: GeoPoint): String =
        "https://www.google.com/maps/search/?api=1&query=${coordinates(point)}"

    fun directionsUrl(from: GeoPoint, to: GeoPoint): String =
        "https://www.google.com/maps/dir/?api=1&origin=${coordinates(from)}" +
            "&destination=${coordinates(to)}&travelmode=driving"

    fun showStop(context: Context, point: GeoPoint) = open(context, markerUrl(point))

    fun showDirections(context: Context, from: GeoPoint, to: GeoPoint) =
        open(context, directionsUrl(from, to))

    private fun open(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        try {
            context.startActivity(Intent(intent).setPackage("com.google.android.apps.maps"))
        } catch (e: ActivityNotFoundException) {
            try {
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(context, R.string.location_no_map_app, Toast.LENGTH_LONG).show()
            }
        }
    }
}
