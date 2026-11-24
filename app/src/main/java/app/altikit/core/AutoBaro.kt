package app.altikit.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.max

/** AUTO split: the altitude and QNH that belong to station pressure [p] at time [t]. */
data class AutoState(
    val alt: Double,
    val qnh: Double,
    val p: Double,
    val t: Long,
    /** Step counter at [t], -1 if unknown. */
    val steps: Long,
    /** Share of the last change that went to altitude (0 = still, 1 = moving). */
    val moving: Float,
) {
    /** Values for a pressure that hasn't been fed yet; the pending change is treated as weather. */
    fun altAt(p: Double) = if (this.p.isNaN()) Atmosphere.altitude(p, qnh) else alt
    fun qnhAt(p: Double) = if (this.p.isNaN() || p == this.p) qnh else Atmosphere.seaLevel(p, alt)

    companion object {
        fun fromConfig(c: Config) = AutoState(c.fixedAlt, c.qnh, c.autoP, c.autoT, c.autoSteps, 0f)
    }
}

/** The maths behind AUTO mode, kept free of Android so it can be unit tested. */
object AutoMath {
    /**
     * Probability that a pressure change comes from moving rather than weather.
     *
     * Without a step sensor only the rate counts: weather rarely changes faster than 1.5–4 hPa/h (12–33 m/h).
     * With steps known, standing still needs a vehicle-like rate (lift, car, cable car, bike) so that the
     * pressure jumps of a passing storm aren't read as height, while walking needs only a gentle rate.
     */
    fun movingWeight(stepsPerMin: Double?, rateHpaPerH: Double): Double {
        val r = abs(rateHpaPerH)
        if (stepsPerMin == null) return ramp(r, 1.5, 4.0)
        val walking = ramp(stepsPerMin, 5.0, 30.0)
        return max(ramp(r, 6.0, 15.0), walking * ramp(r, 0.5, 1.5))
    }

    /** Splits the change from [pPrev] to [p]: the share [w] goes to altitude, the rest to QNH. */
    fun split(alt: Double, qnh: Double, pPrev: Double, p: Double, w: Double): Pair<Double, Double> {
        val dh = Atmosphere.altitude(p, qnh) - Atmosphere.altitude(pPrev, qnh)
        val newAlt = alt + w * dh
        return newAlt to Atmosphere.seaLevel(p, newAlt)
    }

    /** Least-squares slope of (time ms, value) points, per hour; 0 with fewer than 3 points. */
    fun slopePerHour(points: List<Pair<Long, Double>>): Double {
        if (points.size < 3) return 0.0
        val t0 = points[0].first
        val mx = points.sumOf { (it.first - t0).toDouble() } / points.size
        val my = points.sumOf { it.second } / points.size
        var sxy = 0.0
        var sxx = 0.0
        for ((t, v) in points) {
            val dx = (t - t0) - mx
            sxy += dx * (v - my)
            sxx += dx * dx
        }
        return if (sxx == 0.0) 0.0 else sxy / sxx * 3_600_000
    }

    private fun ramp(x: Double, a: Double, b: Double) = ((x - a) / (b - a)).coerceIn(0.0, 1.0)
}

/**
 * AUTO mode: splits each pressure change between altitude and weather by how likely it is that the watch
 * is moving. One instance per process; the app, tile, complications and worker all advance the same
 * anchor, so no change is counted twice.
 */
class AutoBaro private constructor(context: Context) {
    private val prefs = Prefs.get(context)
    private val _state = MutableStateFlow(AutoState.fromConfig(prefs.config.value))
    val state: StateFlow<AutoState> = _state

    // Live history (app in the foreground) for the pressure rate and step cadence.
    private val press = ArrayDeque<Pair<Long, Double>>()
    private val stepLog = ArrayDeque<Pair<Long, Long>>()
    private var lastCommit = 0L

    /** Live feed from the app with the filtered sensor pressure (~10 Hz). */
    @Synchronized
    fun feedLive(p: Double, t: Long, steps: Long?): AutoState {
        val s = _state.value
        // After a gap (process restart, screen off) catch up over the whole gap first.
        if (s.p.isNaN() || t - s.t > GAP_MS) {
            press.clear()
            stepLog.clear()
            return feedOnce(p, t, steps).also { record(p, t, steps) }
        }
        record(p, t, steps)
        val recent = press.filter { t - it.first <= SHORT_MS }
        val rate = max(abs(AutoMath.slopePerHour(recent)), abs(AutoMath.slopePerHour(press)))
        val w = AutoMath.movingWeight(cadence(t, steps), rate)
        return advance(p, t, steps, w, commit = t - lastCommit >= COMMIT_MS)
    }

    /** One-shot feed from the worker, tile or complications: rate and cadence are taken over the gap. */
    @Synchronized
    fun feedOnce(p: Double, t: Long, steps: Long?): AutoState {
        val s = _state.value
        val dtH = (t - s.t) / 3_600_000.0
        val rate = if (!s.p.isNaN() && dtH > 0) (p - s.p) / dtH else 0.0
        val spm = when {
            steps == null -> null
            s.steps < 0 || steps < s.steps || dtH <= 0 -> 0.0 // unknown start or reboot: assume still
            else -> (steps - s.steps) / (dtH * 60)
        }
        return advance(p, t, steps, AutoMath.movingWeight(spm, rate), commit = true)
    }

    /** Sets the split after calibration or a mode change. */
    @Synchronized
    fun reanchor(p: Double, alt: Double, qnh: Double, t: Long = System.currentTimeMillis()) {
        press.clear()
        stepLog.clear()
        val next = AutoState(alt, qnh, p, t, _state.value.steps, 0f)
        _state.value = next
        commit(next)
    }

    /** Persists the current split now (e.g. when the app closes). */
    @Synchronized
    fun flush() {
        if (!_state.value.p.isNaN()) commit(_state.value)
    }

    private fun advance(p: Double, t: Long, steps: Long?, w: Double, commit: Boolean): AutoState {
        val s = _state.value
        val next = if (s.p.isNaN()) {
            // First reading ever: start from the plain altimeter value.
            AutoState(Atmosphere.altitude(p, s.qnh), s.qnh, p, t, steps ?: -1, 0f)
        } else {
            val (alt, qnh) = AutoMath.split(s.alt, s.qnh, s.p, p, w)
            AutoState(alt, qnh, p, t, steps ?: -1, w.toFloat())
        }
        _state.value = next
        if (commit) commit(next)
        return next
    }

    private fun commit(s: AutoState) {
        lastCommit = s.t
        prefs.update {
            if (mode != Mode.AUTO) this
            else copy(qnh = s.qnh, fixedAlt = s.alt, autoP = s.p, autoT = s.t, autoSteps = s.steps)
        }
    }

    private fun record(p: Double, t: Long, steps: Long?) {
        if (press.isEmpty() || t - press.last().first >= 1_000) press.addLast(t to p)
        while (t - press.first().first > LONG_MS) press.removeFirst()
        if (steps != null && (stepLog.isEmpty() || stepLog.last().second != steps)) stepLog.addLast(t to steps)
        while (stepLog.size > 1 && t - stepLog[1].first > CADENCE_MS) stepLog.removeFirst()
    }

    /** Steps per minute over the last minute; null without a step sensor. */
    private fun cadence(t: Long, steps: Long?): Double? {
        if (steps == null) return null
        val first = stepLog.firstOrNull() ?: return 0.0
        // An entry older than the window still holds the count at the window start (no steps since).
        val minutes = ((t - first.first).coerceAtMost(CADENCE_MS) / 60_000.0).coerceAtLeast(MIN_CADENCE_MIN)
        return (steps - first.second) / minutes
    }

    companion object {
        private const val SHORT_MS = 30_000L
        private const val LONG_MS = 180_000L
        private const val CADENCE_MS = 60_000L
        private const val MIN_CADENCE_MIN = 1.0 / 3
        private const val GAP_MS = 60_000L
        private const val COMMIT_MS = 30_000L

        @Volatile private var instance: AutoBaro? = null
        fun get(context: Context): AutoBaro =
            instance ?: synchronized(this) { instance ?: AutoBaro(context.applicationContext).also { instance = it } }
    }
}
