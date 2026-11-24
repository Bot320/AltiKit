package app.altikit.core.forecast

import app.altikit.core.TrendLog
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Model inputs built from the sea-level weather pressure history. Must match `features()` in
 * tools/forecast/train.py exactly; the golden-vector test checks that.
 */
object Features {
    const val MIN_HOURS = 3.0
    private const val H = 3_600_000L
    /** A lag may start this much before the oldest sample and still use it. */
    private const val SLACK_MS = 20 * 60_000L
    private val LAGS = intArrayOf(1, 3, 6, 12, 24)

    val NAMES = listOf(
        "msl", "d1", "d3", "d6", "d12", "d24", "m6", "m12", "m24",
        "alt_km", "hour_sin", "hour_cos", "doy_sin", "doy_cos",
    )

    /** Hours of usable history before [now]. */
    fun hoursOfData(series: List<Pair<Long, Double>>, now: Long): Double =
        series.firstOrNull { !it.second.isNaN() }?.let { (now - it.first) / H.toDouble() } ?: 0.0

    /**
     * [series] is (time, sea-level hPa) sorted by time, [current] the value at [now]. Returns null with
     * less than [MIN_HOURS] of history.
     */
    fun extract(series: List<Pair<Long, Double>>, current: Double, now: Long, altM: Double, zone: ZoneId): DoubleArray? {
        val s = series.filter { !it.second.isNaN() && it.first < now } + (now to current)
        fun lag(h: Int): Double? {
            val t = now - h * H
            return TrendLog.valueAt(s, t)
                ?: s.first().takeIf { t < it.first && it.first - t <= SLACK_MS }?.second
        }
        val p3 = lag(3) ?: return null
        val d3 = current - p3
        // Missing lags continue the 3 h trend; the mask tells the model it's a guess.
        val d1 = lag(1)?.let { current - it } ?: (d3 / 3)
        val longer = LAGS.drop(2).map { h -> lag(h)?.let { current - it to 1.0 } ?: (d3 * h / 3 to 0.0) }
        return doubleArrayOf(current, d1, d3, longer[0].first, longer[1].first, longer[2].first,
            longer[0].second, longer[1].second, longer[2].second, altM / 1000) + timeFeatures(now, zone)
    }

    fun timeFeatures(now: Long, zone: ZoneId): DoubleArray {
        val local = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
        val hour = local.hour + local.minute / 60.0
        val doy = local.dayOfYear - 1 + hour / 24
        val a = 2 * PI * hour / 24
        val b = 2 * PI * doy / 365.25
        return doubleArrayOf(sin(a), cos(a), sin(b), cos(b))
    }
}
