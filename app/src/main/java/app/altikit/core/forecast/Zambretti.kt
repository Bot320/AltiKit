package app.altikit.core.forecast

import kotlin.math.floor

/**
 * The Negretti & Zambra barometer forecaster (1915), in the widely used form by beteljuice: sea-level
 * pressure, the 3-hour trend and the season pick one of 26 forecasts, A (settled fine) to Z (stormy).
 * No wind input, Northern hemisphere. Mirrored by `zambretti()` in tools/forecast/train.py.
 */
object Zambretti {
    data class Result(val index: Int, val text: String, val cls: WxClass, val exceptional: Boolean) {
        val letter get() = 'A' + index
    }

    /** Δ3h beyond ±this (hPa) counts as rising or falling. */
    const val TREND_HPA = 1.6
    private const val TOP = 1050.0
    private const val BOTTOM = 950.0

    private val TEXTS = listOf(
        "SETTLED FINE", "FINE WEATHER", "BECOMING FINE", "FINE, LESS SETTLED", "FINE, POSS. SHOWERS",
        "FAIR, IMPROVING", "FAIR, SHOWERS EARLY", "FAIR, SHOWERS LATER", "SHOWERS, IMPROVING",
        "CHANGEABLE, MENDING", "FAIR, SHOWERS LIKELY", "UNSETTLED, CLEARING", "UNSETTLED, IMPROVING",
        "SHOWERY, BRIGHT", "SHOWERY, WORSENING", "CHANGEABLE, RAIN", "UNSETTLED, SHORT FINE",
        "UNSETTLED, RAIN LATER", "UNSETTLED, SOME RAIN", "VERY UNSETTLED", "RAIN, WORSENING",
        "RAIN AT TIMES", "FREQUENT RAIN", "RAIN, VERY UNSETTLED", "STORMY, MAY IMPROVE", "STORMY, MUCH RAIN",
    )
    private val RISING = intArrayOf(25, 25, 25, 24, 24, 19, 16, 12, 11, 9, 8, 6, 5, 2, 1, 1, 0, 0, 0, 0, 0, 0)
    private val STEADY = intArrayOf(25, 25, 25, 25, 25, 25, 23, 23, 22, 18, 15, 13, 10, 4, 1, 1, 0, 0, 0, 0, 0, 0)
    private val FALLING = intArrayOf(25, 25, 25, 25, 25, 25, 25, 25, 23, 23, 21, 20, 17, 14, 7, 3, 1, 1, 1, 0, 0, 0)

    /**
     * Forecast index → class, as first index of CLOUDY, RAIN and STORM: A–F fair, G–M changeable,
     * N–X rain, Y–Z stormy, so the class always agrees with the text. Same as Z_CUTS in train.py.
     */
    internal val CUTS = intArrayOf(6, 13, 24)

    fun classOf(index: Int): WxClass = when {
        index < CUTS[0] -> WxClass.FAIR
        index < CUTS[1] -> WxClass.CLOUDY
        index < CUTS[2] -> WxClass.RAIN
        else -> WxClass.STORM
    }

    /** [month] is 1–12, local time. */
    fun forecast(mslHpa: Double, delta3h: Double, month: Int): Result {
        val trend = when {
            delta3h >= TREND_HPA -> 1
            delta3h <= -TREND_HPA -> -1
            else -> 0
        }
        val range = TOP - BOTTOM
        var p = mslHpa
        // Summer half-year: a rise means more, a fall less than in winter.
        if (month in 4..9) p += trend * 7.0 / 100 * range
        if (p >= TOP) p = TOP - 1
        var option = floor((p - BOTTOM) / (range / 22)).toInt()
        val exceptional = option < 0 || option > 21
        option = option.coerceIn(0, 21)
        val index = when (trend) {
            1 -> RISING[option]
            -1 -> FALLING[option]
            else -> STEADY[option]
        }
        return result(index).copy(exceptional = exceptional)
    }

    fun result(index: Int) = Result(index, TEXTS[index], classOf(index), false)
}
