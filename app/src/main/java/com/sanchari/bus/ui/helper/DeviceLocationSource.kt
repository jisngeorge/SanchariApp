package com.sanchari.bus.ui.helper

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.sanchari.bus.data.model.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class DeviceLocationFix(val point: GeoPoint, val approximate: Boolean, val accuracyMeters: Float?)

enum class LocationFailure { PERMISSION, DISABLED, UNAVAILABLE }

class LocationAccessException(val reason: LocationFailure) : Exception(reason.name)

fun interface DeviceLocationSource {
    suspend fun currentLocation(): DeviceLocationFix
}

class AndroidDeviceLocationSource(private val context: Context) : DeviceLocationSource {
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    override suspend fun currentLocation(): DeviceLocationFix = withContext(Dispatchers.Main.immediate) {
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        if (!fine && !granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            throw LocationAccessException(LocationFailure.PERMISSION)
        }
        try {
            if (!LocationManagerCompat.isLocationEnabled(manager)) {
                throw LocationAccessException(LocationFailure.DISABLED)
            }
            val gps = fine && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val network = manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            val location = withTimeoutOrNull(30_000L) {
                val satellite = if (gps) {
                    withTimeoutOrNull(if (network) 15_000L else 30_000L) {
                        awaitFreshFix(LocationManager.GPS_PROVIDER)
                    }
                } else null
                satellite ?: if (network) awaitFreshFix(LocationManager.NETWORK_PROVIDER) else null
            } ?: throw LocationAccessException(LocationFailure.UNAVAILABLE)
            val point = GeoPoint.fromValues(location.latitude, location.longitude)
                ?: throw LocationAccessException(LocationFailure.UNAVAILABLE)
            DeviceLocationFix(point, !fine, location.accuracy.takeIf {
                location.hasAccuracy() && it.isFinite() && it >= 0
            })
        } catch (e: SecurityException) {
            throw LocationAccessException(LocationFailure.PERMISSION)
        } catch (e: IllegalArgumentException) {
            throw LocationAccessException(LocationFailure.UNAVAILABLE)
        }
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private suspend fun awaitFreshFix(provider: String): Location? = suspendCancellableCoroutine { continuation ->
        val started = SystemClock.elapsedRealtimeNanos()
        val registrationLock = Any()
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (!continuation.isActive || location.elapsedRealtimeNanos < started ||
                    GeoPoint.fromValues(location.latitude, location.longitude) == null) return
                manager.removeUpdates(this)
                continuation.resume(Location(location))
            }

            override fun onProviderDisabled(provider: String) {
                if (continuation.isActive) {
                    manager.removeUpdates(this)
                    continuation.resume(null)
                }
            }

            override fun onProviderEnabled(provider: String) {}

            @Deprecated("Required by LocationListener on older Android versions")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        continuation.invokeOnCancellation {
            synchronized(registrationLock) { manager.removeUpdates(listener) }
        }
        try {
            synchronized(registrationLock) {
                if (continuation.isActive) {
                    manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                }
            }
        } catch (e: SecurityException) {
            manager.removeUpdates(listener)
            if (continuation.isActive) continuation.resumeWithException(LocationAccessException(LocationFailure.PERMISSION))
        } catch (e: IllegalArgumentException) {
            manager.removeUpdates(listener)
            if (continuation.isActive) continuation.resumeWithException(LocationAccessException(LocationFailure.UNAVAILABLE))
        }
    }
}
