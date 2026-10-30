package app.altikit.core

enum class CalSource(val tag: String) {
    STD("STD"),
    MANUAL_ALT("MAN·ALT"),
    MANUAL_QNH("MAN·QNH"),
    GPS("GPS"),
    WEB("GPS·WX"),
}

/** ALTIMETER: QNH fixed, altitude follows pressure. BAROMETER: altitude locked, QNH follows pressure. */
enum class Mode { ALTIMETER, BAROMETER }

data class Config(
    val qnh: Double = Atmosphere.STD_HPA,
    val source: CalSource = CalSource.STD,
    val calTime: Long = 0L,
    /** Estimated altitude uncertainty of the last calibration in metres, NaN if unknown. */
    val calAccM: Double = Double.NaN,
    val imperial: Boolean = false,
    val mode: Mode = Mode.ALTIMETER,
    /** Altitude held while in BAROMETER mode. */
    val fixedAlt: Double = 0.0,
    /** Station pressure captured when relative mode was armed, NaN when off. */
    val relRefHpa: Double = Double.NaN,
    /** Value relative mode showed at the moment it was armed (m). */
    val relDisplay: Double = 0.0,
    /** Background trend-log interval in minutes, 0 = off. */
    val logMinutes: Int = 15,
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
) {
    companion object {
        fun from(s: BaroSample, c: Config): Reading {
            val p = s.hpa
            val baro = c.mode == Mode.BAROMETER
            val alt = if (baro) c.fixedAlt else Atmosphere.altitude(p, c.qnh)
            val qnh = if (baro) Atmosphere.seaLevel(p, c.fixedAlt) else c.qnh
            val std = Atmosphere.altitude(p, Atmosphere.STD_HPA)
            val vs = Atmosphere.altitude(p + s.rateHpaPerSec * 60, Atmosphere.STD_HPA) - std
            // Relative mode works on pressure differences so it keeps tracking in BARO mode too.
            val rel = if (c.relActive) {
                Atmosphere.altitude(p, c.qnh) - Atmosphere.altitude(c.relRefHpa, c.qnh) + c.relDisplay
            } else Double.NaN
            return Reading(p, qnh, alt, std, vs, rel)
        }
    }
}
