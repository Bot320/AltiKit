package app.altikit.core

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeoutOrNull

class BaroSensor(context: Context) {
    private val sm = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_PRESSURE)

    val available get() = sensor != null

    /** Pressure stream at ~10 Hz with a display filter and the rate of change over the last few seconds. */
    fun readings(): Flow<BaroSample> = callbackFlow {
        val s = sensor
        if (s == null) {
            close()
            return@callbackFlow
        }
        var fast = Double.NaN
        var lastT = 0L
        val rate = RateWindow(RATE_WINDOW_MS)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val p = e.values[0].toDouble()
                val dt = (e.timestamp - lastT) / 1e9
                lastT = e.timestamp
                if (fast.isNaN() || dt <= 0 || dt > 5) {
                    fast = p
                    rate.clear()
                } else {
                    fast += (p - fast) * dt / (FAST_TAU + dt)
                }
                trySend(BaroSample(p, fast, rate.add(e.timestamp / 1_000_000, p)))
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sm.registerListener(listener, s, 100_000)
        awaitClose { sm.unregisterListener(listener) }
    }.conflate()

    /** Short burst read for background use; averages a few raw samples. */
    suspend fun readOnce(samples: Int = 8, timeoutMs: Long = 5_000): Double? =
        withTimeoutOrNull(timeoutMs) {
            readings().take(samples).toList().map { it.raw }.takeIf { it.isNotEmpty() }?.average()
        }

    private companion object {
        const val FAST_TAU = 0.8
        const val RATE_WINDOW_MS = 4_000L
    }
}

/**
 * Pressure rate as the least-squares slope over the last [windowMs]. Unlike a low-pass filter it is back at 0
 * one window after a climb ends instead of fading out slowly; 0 until the window is half full.
 */
class RateWindow(private val windowMs: Long) {
    private val points = ArrayDeque<Pair<Long, Double>>()

    fun clear() = points.clear()

    /** Adds pressure [p] at time [t] (ms) and returns the rate in hPa/s. */
    fun add(t: Long, p: Double): Double {
        points.addLast(t to p)
        while (t - points.first().first > windowMs) points.removeFirst()
        if (t - points.first().first < windowMs / 2) return 0.0
        return AutoMath.slopePerHour(points) / 3600
    }
}
