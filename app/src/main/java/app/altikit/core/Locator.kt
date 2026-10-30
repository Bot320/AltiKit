package app.altikit.core

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.altitude.AltitudeConverter
import android.os.Build
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.sqrt

data class GpsFix(val altM: Double, val accM: Double, val fixes: Int, val msl: Boolean)

@SuppressLint("MissingPermission") // callers check ACCESS_FINE_LOCATION first
class Locator(private val context: Context) {
    private val client = LocationServices.getFusedLocationProviderClient(context)

    /**
     * Averages GPS altitude for [durationMs], weighting each fix by 1/σ².
     * Altitude is converted from the WGS84 ellipsoid to mean sea level where the platform supports it (API 34+).
     */
    suspend fun averageAltitude(
        durationMs: Long,
        onProgress: (fixes: Int, estimate: Double?, accM: Double?) -> Unit,
    ): GpsFix? {
        var sumW = 0.0
        var sumWA = 0.0
        var n = 0
        var allMsl = true
        val accs = mutableListOf<Double>()
        withTimeoutOrNull(durationMs) {
            callbackFlow {
                val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000).build()
                val cb = object : LocationCallback() {
                    override fun onLocationResult(r: LocationResult) {
                        r.locations.forEach { trySend(it) }
                    }
                }
                client.requestLocationUpdates(req, cb, Looper.getMainLooper())
                awaitClose { client.removeLocationUpdates(cb) }
            }.collect { loc ->
                if (!loc.hasAltitude()) return@collect
                val sigma = if (loc.hasVerticalAccuracy()) loc.verticalAccuracyMeters.toDouble() else 30.0
                if (sigma > 60) return@collect
                val (alt, msl) = mslAltitude(loc)
                allMsl = allMsl && msl
                val w = 1 / (sigma * sigma).coerceAtLeast(1.0)
                sumW += w
                sumWA += w * alt
                n++
                accs += sigma
                onProgress(n, sumWA / sumW, estimateAcc(accs))
            }
        }
        if (n < 3) return null
        return GpsFix(sumWA / sumW, estimateAcc(accs)!!, n, allMsl)
    }

    /** GPS errors are strongly correlated between fixes, so only credit a few independent samples. */
    private fun estimateAcc(accs: List<Double>): Double? {
        if (accs.isEmpty()) return null
        val median = accs.sorted()[accs.size / 2]
        return median / sqrt(accs.size.coerceAtMost(4).toDouble())
    }

    private suspend fun mslAltitude(loc: Location): Pair<Double, Boolean> {
        if (Build.VERSION.SDK_INT < 34) return loc.altitude to false
        if (loc.hasMslAltitude()) return loc.mslAltitudeMeters to true
        return try {
            withContext(Dispatchers.IO) { AltitudeConverter().addMslAltitudeToLocation(context, loc) }
            loc.mslAltitudeMeters to true
        } catch (e: Exception) {
            loc.altitude to false
        }
    }

    /** Any reasonably fresh position, good enough to look up the local weather. */
    suspend fun quickFix(): Location? {
        val cts = CancellationTokenSource()
        return withTimeoutOrNull(20_000) {
            client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token).await()
        } ?: client.lastLocation.await()
    }
}
