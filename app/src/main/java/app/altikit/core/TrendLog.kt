package app.altikit.core

import android.content.Context
import java.io.File
import kotlin.math.abs

data class Sample(val t: Long, val hpa: Double)

enum class Tendency(val arrow: String, val label: String) {
    RISING_FAST("▲▲", "RISING FAST"),
    RISING("▲", "RISING"),
    STEADY("►", "STEADY"),
    FALLING("▼", "FALLING"),
    STORM("▼▼", "STORM RISK"),
}

data class Trend(val delta3h: Double, val tendency: Tendency)

/** Small CSV ring buffer of station pressure samples (48 h). */
object TrendLog {
    private const val FILE = "trend.csv"
    private const val KEEP_MS = 48 * 3_600_000L
    private const val H3 = 3 * 3_600_000L

    @Synchronized
    fun read(context: Context): List<Sample> {
        val f = File(context.filesDir, FILE)
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { line ->
            val parts = line.split(',')
            if (parts.size != 2) null
            else runCatching { Sample(parts[0].toLong(), parts[1].toDouble()) }.getOrNull()
        }
    }

    @Synchronized
    fun append(context: Context, hpa: Double, t: Long = System.currentTimeMillis()) {
        val keep = read(context).filter { t - it.t < KEEP_MS } + Sample(t, hpa)
        File(context.filesDir, FILE).writeText(keep.joinToString("\n", postfix = "\n") { "${it.t},${"%.2f".format(java.util.Locale.US, it.hpa)}" })
    }

    /** Appends a sample if the newest one is older than [minAgeMs]. Returns true if it logged. */
    fun appendIfStale(context: Context, hpa: Double, minAgeMs: Long = 10 * 60_000L): Boolean {
        val last = read(context).lastOrNull()
        if (last != null && System.currentTimeMillis() - last.t < minAgeMs) return false
        append(context, hpa)
        return true
    }

    fun clear(context: Context) = File(context.filesDir, FILE).delete()

    /** 3-hour station pressure tendency; needs a sample within ±45 min of 3 h ago. */
    fun trend(samples: List<Sample>, current: Double?, now: Long = System.currentTimeMillis()): Trend? {
        val target = now - H3
        val old = samples.filter { abs(it.t - target) <= 45 * 60_000L }.minByOrNull { abs(it.t - target) } ?: return null
        val cur = current ?: samples.lastOrNull()?.hpa ?: return null
        val d = (cur - old.hpa) * H3 / (now - old.t).coerceAtLeast(1)
        val tendency = when {
            d >= 3.6 -> Tendency.RISING_FAST
            d >= 0.8 -> Tendency.RISING
            d > -0.8 -> Tendency.STEADY
            d > -3.0 -> Tendency.FALLING
            else -> Tendency.STORM
        }
        return Trend(d, tendency)
    }
}
