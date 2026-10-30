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

    /** Pressure stream at ~10 Hz with a display filter and a smoothed rate of change. */
    fun readings(): Flow<BaroSample> = callbackFlow {
        val s = sensor
        if (s == null) {
            close()
            return@callbackFlow
        }
        var fast = Double.NaN
        var slow = 0.0
        var rate = 0.0
        var lastT = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val p = e.values[0].toDouble()
                val dt = (e.timestamp - lastT) / 1e9
                lastT = e.timestamp
                if (fast.isNaN() || dt <= 0 || dt > 5) {
                    fast = p; slow = p; rate = 0.0
                } else {
                    fast += (p - fast) * dt / (FAST_TAU + dt)
                    val prev = slow
                    slow += (p - slow) * dt / (SLOW_TAU + dt)
                    rate += ((slow - prev) / dt - rate) * dt / (RATE_TAU + dt)
                }
                trySend(BaroSample(p, fast, rate))
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
        const val SLOW_TAU = 3.0
        const val RATE_TAU = 6.0
    }
}
