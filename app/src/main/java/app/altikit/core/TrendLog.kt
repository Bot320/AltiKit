package app.altikit.core

import android.content.Context
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * One logged reading. [hpa] is the raw station pressure, [msl] the weather pressure reduced to sea level
 * (see [Reading.mslHpa]) and [alt] the altitude at that moment. Rows from version 1.0 only had [hpa];
 * they read as NaN until [TrendLog.migrate] fills them in.
 */
data class Sample(val t: Long, val hpa: Double, val msl: Double = Double.NaN, val alt: Double = Double.NaN) {
    companion object {
        fun of(r: Reading, t: Long = System.currentTimeMillis()) = Sample(t, r.stationHpa, r.mslHpa, r.altM)
    }
}

enum class Tendency(val arrow: String, val label: String) {
    RISING_FAST("▲▲", "RISING FAST"),
    RISING("▲", "RISING"),
    STEADY("►", "STEADY"),
    FALLING("▼", "FALLING"),
    STORM("▼▼", "STORM RISK"),
}

data class Trend(val delta3h: Double, val tendency: Tendency)

/** Small CSV ring buffer of pressure samples (48 h). */
object TrendLog {
    private const val FILE = "trend.csv"
    private const val KEEP_MS = 48 * 3_600_000L
    private const val H3 = 3 * 3_600_000L

    @Synchronized
    fun read(context: Context): List<Sample> {
        val f = File(context.filesDir, FILE)
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull(::parse)
    }

    internal fun parse(line: String): Sample? {
        val parts = line.split(',')
        return runCatching {
            when (parts.size) {
                2 -> Sample(parts[0].toLong(), parts[1].toDouble())
                4 -> Sample(parts[0].toLong(), parts[1].toDouble(), parts[2].toDouble(), parts[3].toDouble())
                else -> null
            }
        }.getOrNull()
    }

    internal fun format(s: Sample) = String.format(Locale.US, "%d,%.2f,%.2f,%.1f", s.t, s.hpa, s.msl, s.alt)

    private fun write(context: Context, samples: List<Sample>) {
        File(context.filesDir, FILE).writeText(samples.joinToString("\n", postfix = "\n", transform = ::format))
    }

    @Synchronized
    fun append(context: Context, s: Sample) {
        write(context, read(context).filter { s.t - it.t < KEEP_MS } + s)
    }

    /** Appends a sample if the newest one is older than [minAgeMs]. Returns true if it logged. */
    @Synchronized
    fun appendIfStale(context: Context, s: Sample, minAgeMs: Long = 10 * 60_000L): Boolean {
        val last = read(context).lastOrNull()
        if (last != null && s.t - last.t < minAgeMs) return false
        append(context, s)
        return true
    }

    /**
     * Fills in 1.0 rows that only had station pressure, reducing them with [altM] (the altitude the
     * app assumed then), so the weather series doesn't jump at the upgrade.
     */
    @Synchronized
    fun migrate(context: Context, altM: Double) {
        val samples = read(context)
        if (samples.none { it.msl.isNaN() }) return
        write(context, migrated(samples, altM))
    }

    internal fun migrated(samples: List<Sample>, altM: Double) = samples.map {
        if (it.msl.isNaN()) it.copy(msl = Atmosphere.seaLevel(it.hpa, altM), alt = altM) else it
    }

    fun clear(context: Context) = File(context.filesDir, FILE).delete()

    /** 3-hour tendency of the sea-level weather pressure; needs a sample within ±45 min of 3 h ago. */
    fun trend(samples: List<Sample>, currentMsl: Double?, now: Long = System.currentTimeMillis()): Trend? {
        val target = now - H3
        val old = samples.filter { !it.msl.isNaN() && abs(it.t - target) <= 45 * 60_000L }
            .minByOrNull { abs(it.t - target) } ?: return null
        val cur = currentMsl ?: samples.lastOrNull()?.msl ?: return null
        val d = (cur - old.msl) * H3 / (now - old.t).coerceAtLeast(1)
        val tendency = when {
            d >= 3.6 -> Tendency.RISING_FAST
            d >= 0.8 -> Tendency.RISING
            d > -0.8 -> Tendency.STEADY
            d > -3.0 -> Tendency.FALLING
            else -> Tendency.STORM
        }
        return Trend(d, tendency)
    }

    /**
     * Value of a time-sorted series at time [t], linearly interpolated. Null if [t] is outside the
     * series or falls in a gap longer than [maxGapMs].
     */
    fun valueAt(series: List<Pair<Long, Double>>, t: Long, maxGapMs: Long = 3 * 3_600_000L): Double? {
        if (series.isEmpty() || t < series.first().first || t > series.last().first) return null
        var lo = 0
        var hi = series.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (series[mid].first <= t) lo = mid else hi = mid
        }
        val (t0, v0) = series[lo]
        val (t1, v1) = series[hi]
        if (t == t0) return v0
        if (t == t1) return v1
        if (t1 - t0 > maxGapMs) return null
        return v0 + (v1 - v0) * (t - t0) / (t1 - t0)
    }
}

/** Change of a quantity over a past window; [spanMs] is shorter than asked for when history is short. */
data class Change(val delta: Double, val spanMs: Long)

object Changes {
    /**
     * Change from [windowMs] ago to [now] (value [current]). Falls back to the oldest point when the
     * series is shorter than the window, and returns null with less than a minute of history.
     */
    fun over(series: List<Pair<Long, Double>>, current: Double, now: Long, windowMs: Long): Change? {
        if (current.isNaN() || series.isEmpty()) return null
        val first = series.first()
        val from = now - windowMs
        if (from < first.first) {
            val span = now - first.first
            return if (span < 60_000L || first.second.isNaN()) null else Change(current - first.second, span)
        }
        val old = TrendLog.valueAt(series, from) ?: return null
        return Change(current - old, windowMs)
    }
}
