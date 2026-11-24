package app.altikit.core

import app.altikit.core.forecast.Source

enum class CalSource(val tag: String) {
    STD("STD"),
    MANUAL_ALT("MAN·ALT"),
    MANUAL_QNH("MAN·QNH"),
    GPS("GPS"),
    WEB("GPS·WX"),
}

/**
 * ALTIMETER: QNH fixed, altitude follows pressure. BAROMETER: altitude locked, QNH follows pressure.
 * AUTO: motion decides how each pressure change is split between altitude and weather (see [AutoBaro]).
 */
enum class Mode(val tag: String) { AUTO("AUTO"), ALTIMETER("ALTI"), BAROMETER("BARO") }

data class Config(
    /** QNH; in AUTO mode the current weather estimate. */
    val qnh: Double = Atmosphere.STD_HPA,
    val source: CalSource = CalSource.STD,
    val calTime: Long = 0L,
    /** Estimated altitude uncertainty of the last calibration in metres, NaN if unknown. */
    val calAccM: Double = Double.NaN,
    val imperial: Boolean = false,
    val mode: Mode = Mode.AUTO,
    /** Altitude held while in BAROMETER mode; in AUTO mode the current altitude estimate. */
    val fixedAlt: Double = 0.0,
    /** AUTO anchor: station pressure, time and step count that [qnh] and [fixedAlt] belong to. */
    val autoP: Double = Double.NaN,
    val autoT: Long = 0L,
    val autoSteps: Long = -1L,
    /** Station pressure captured when relative mode was armed, NaN when off. */
    val relRefHpa: Double = Double.NaN,
    /** QNH at the moment relative mode was armed; lets AUTO mode ignore weather drift. */
    val relRefQnh: Double = Double.NaN,
    /** Value relative mode showed at the moment it was armed (m). */
    val relDisplay: Double = 0.0,
    /** Background trend-log interval in minutes, 0 = off. */
    val logMinutes: Int = 15,
    /** Forecast page horizon in hours (3, 6, 12 or 24). */
    val fcstHours: Int = 12,
    /** Continuous forecast: re-run after every background sample and notify on rain or storm. */
    val fcstAlerts: Boolean = false,
    val alertSource: Source = Source.AI,
    /** Last time a rain / storm alert fired, and which conditions held at the previous check. */
    val alertRainT: Long = 0L,
    val alertStormT: Long = 0L,
    val alertState: Int = 0,
) {
    val relActive get() = !relRefHpa.isNaN()
}

/** Filtered sensor output. */
data class BaroSample(val raw: Double, val hpa: Double, val rateHpaPerSec: Double)

data class Reading(
    /** Uncalibrated station pressure straight from the sensor (filtered). */
    val stationHpa: Double,
    /** Calibrated sea-level pressure. */
    val qnhHpa: Double,
    /** Calibrated altitude. */
    val altM: Double,
    /** Uncalibrated pressure altitude (QNH 1013.25). */
    val stdAltM: Double,
    /** Vertical speed, m/min. */
    val vsMpm: Double,
    /** Relative altitude, NaN when relative mode is off. */
    val relM: Double,
    /**
     * Weather pressure reduced to sea level, used for trends and the forecast. Equals [qnhHpa] in AUTO and
     * BAROMETER mode; in ALTIMETER mode the station pressure is reduced with the last known altitude.
     */
    val mslHpa: Double = qnhHpa,
) {
    companion object {
        fun from(s: BaroSample, c: Config, auto: AutoState? = null): Reading {
            val p = s.hpa
            val (alt, qnh) = when (c.mode) {
                Mode.BAROMETER -> c.fixedAlt to Atmosphere.seaLevel(p, c.fixedAlt)
                Mode.ALTIMETER -> Atmosphere.altitude(p, c.qnh) to c.qnh
                Mode.AUTO -> (auto ?: AutoState.fromConfig(c)).let { it.altAt(p) to it.qnhAt(p) }
            }
            val std = Atmosphere.altitude(p, Atmosphere.STD_HPA)
            val vs = Atmosphere.altitude(p + s.rateHpaPerSec * 60, Atmosphere.STD_HPA) - std
            val rel = when {
                !c.relActive -> Double.NaN
                // alt_now − alt_at_arm, so weather drift while standing still doesn't count as height.
                c.mode == Mode.AUTO -> alt - Atmosphere.altitude(c.relRefHpa, c.relRefQnh.takeUnless { it.isNaN() } ?: qnh) + c.relDisplay
                // Pressure differences keep relative mode tracking in BARO mode too.
                else -> Atmosphere.altitude(p, c.qnh) - Atmosphere.altitude(c.relRefHpa, c.qnh) + c.relDisplay
            }
            val msl = if (c.mode == Mode.ALTIMETER) Atmosphere.seaLevel(p, c.fixedAlt) else qnh
            return Reading(p, qnh, alt, std, vs, rel, msl)
        }
    }
}
