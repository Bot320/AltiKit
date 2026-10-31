package app.altikit.core

import android.content.Context
import app.altikit.core.forecast.Forecaster
import app.altikit.core.forecast.Forecasts

/** One-shot reading for the tile, complications and background worker. */
data class Snapshot(
    val config: Config,
    val reading: Reading,
    val trend: Trend?,
    val forecasts: Forecasts,
    val samples: List<Sample>,
) {
    companion object {
        /** Reads the sensor once, advances AUTO mode and logs if the newest sample is older than [logMinAgeMs]. */
        suspend fun take(context: Context, logMinAgeMs: Long? = 10 * 60_000L): Snapshot? {
            val p = BaroSensor(context).readOnce() ?: return null
            val now = System.currentTimeMillis()
            val prefs = Prefs.get(context)
            val auto = if (prefs.config.value.mode == Mode.AUTO) {
                AutoBaro.get(context).feedOnce(p, now, Motion(context).readSteps())
            } else null
            val cfg = prefs.config.value
            TrendLog.migrate(context, cfg.fixedAlt)
            val reading = Reading.from(BaroSample(p, p, 0.0), cfg, auto)
            if (logMinAgeMs != null) TrendLog.appendIfStale(context, Sample.of(reading, now), logMinAgeMs)
            val samples = TrendLog.read(context)
            return Snapshot(
                cfg, reading, TrendLog.trend(samples, reading.mslHpa, now),
                Forecaster.run(context, samples, reading, now), samples,
            )
        }
    }
}
