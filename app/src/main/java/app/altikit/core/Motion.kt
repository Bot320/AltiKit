package app.altikit.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Step counter for AUTO mode. The count is kept by the low-power sensor hub whether or not anyone listens,
 * so reading it costs next to nothing. Needs ACTIVITY_RECOGNITION; without it AUTO works on the rate alone.
 */
class Motion(private val context: Context) {
    private val sm = context.getSystemService(SensorManager::class.java)

    private fun sensor(): Sensor? {
        val granted = context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        return if (granted) sm?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) else null
    }

    /** Steps since boot; empty when there's no sensor or permission. */
    fun steps(): Flow<Long> {
        val s = sensor() ?: return emptyFlow()
        return callbackFlow {
            val listener = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    trySend(e.values[0].toLong())
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            sm.registerListener(listener, s, SensorManager.SENSOR_DELAY_NORMAL)
            awaitClose { sm.unregisterListener(listener) }
        }.conflate()
    }

    /** One-shot read for background use; null if the sensor doesn't answer in time. */
    suspend fun readSteps(timeoutMs: Long = 2_000): Long? =
        if (sensor() == null) null else withTimeoutOrNull(timeoutMs) { steps().first() }
}
