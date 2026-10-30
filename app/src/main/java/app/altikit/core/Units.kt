package app.altikit.core

import java.util.Locale

class Units(val imperial: Boolean) {
    val alt get() = if (imperial) "ft" else "m"
    val press get() = if (imperial) "inHg" else "hPa"

    fun altTo(m: Double) = if (imperial) m * FT else m
    fun altFrom(v: Double) = if (imperial) v / FT else v
    fun pressTo(hpa: Double) = if (imperial) hpa * INHG else hpa
    fun pressFrom(v: Double) = if (imperial) v / INHG else v

    fun altStr(m: Double, decimals: Int = 0) = fmt(altTo(m), decimals)
    fun altSigned(m: Double, decimals: Int = 1) = fmt(altTo(m), decimals, signed = true)
    fun pressStr(hpa: Double) = fmt(pressTo(hpa), if (imperial) 2 else 1)
    fun pressSigned(hpa: Double) = fmt(pressTo(hpa), if (imperial) 2 else 1, signed = true)

    companion object {
        const val FT = 3.28084
        const val INHG = 0.0295300

        fun fmt(v: Double, decimals: Int, signed: Boolean = false): String {
            if (v.isNaN()) return "--"
            val f = "%" + (if (signed) "+" else "") + "." + decimals + "f"
            // Avoid "-0" flicker around zero.
            val r = String.format(Locale.US, f, v)
            return if (r.trimStart('+', '-').all { it == '0' || it == '.' }) r.replace("-", if (signed) "+" else "") else r
        }

        fun age(ms: Long, now: Long = System.currentTimeMillis()): String {
            if (ms <= 0) return "never"
            val m = (now - ms) / 60_000
            return when {
                m < 1 -> "now"
                m < 60 -> "${m}m"
                m < 48 * 60 -> "${m / 60}h"
                else -> "${m / 1440}d"
            }
        }
    }
}
