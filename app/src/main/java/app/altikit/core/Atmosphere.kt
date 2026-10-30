package app.altikit.core

import kotlin.math.pow

/** International Standard Atmosphere (troposphere) pressure/altitude relations. */
object Atmosphere {
    const val STD_HPA = 1013.25
    private const val K = 44330.77
    private const val EXP = 0.190263

    /** Altitude (m) for station pressure [p] given sea-level reference [p0] (QNH). */
    fun altitude(p: Double, p0: Double): Double = K * (1 - (p / p0).pow(EXP))

    /** Sea-level pressure (QNH) for station pressure [p] observed at altitude [h] (m). */
    fun seaLevel(p: Double, h: Double): Double = p / (1 - h / K).pow(1 / EXP)
}
