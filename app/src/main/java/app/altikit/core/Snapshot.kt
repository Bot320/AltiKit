package app.altikit.core

import android.content.Context

/** One-shot reading for the tile, complications and background worker. */
data class Snapshot(val config: Config, val reading: Reading, val trend: Trend?) {
    companion object {
        suspend fun take(context: Context, log: Boolean = true): Snapshot? {
            val p = BaroSensor(context).readOnce() ?: return null
            if (log) TrendLog.appendIfStale(context, p)
            val cfg = Prefs.get(context).config.value
            val reading = Reading.from(BaroSample(p, p, 0.0), cfg)
            return Snapshot(cfg, reading, TrendLog.trend(TrendLog.read(context), p))
        }
    }
}
