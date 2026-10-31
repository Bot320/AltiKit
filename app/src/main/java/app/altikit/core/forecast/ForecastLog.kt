package app.altikit.core.forecast

import android.content.Context
import java.io.File
import java.util.Locale

/** One logged prediction from each source, kept 48 h so the two can be compared with what happened. */
data class LoggedForecast(val t: Long, val ai: WxClass?, val aiProb: Double, val zIndex: Int) {
    val zambretti: Zambretti.Result? get() = zIndex.takeIf { it in 0..25 }?.let { Zambretti.result(it) }
}

object ForecastLog {
    private const val FILE = "forecast.csv"
    private const val KEEP_MS = 48 * 3_600_000L

    @Synchronized
    fun read(context: Context): List<LoggedForecast> {
        val f = File(context.filesDir, FILE)
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull(::parse)
    }

    internal fun parse(line: String): LoggedForecast? {
        val p = line.split(',')
        if (p.size != 4) return null
        return runCatching {
            LoggedForecast(p[0].toLong(), p[1].takeIf { it.isNotEmpty() }?.let(WxClass::valueOf), p[2].toDouble(), p[3].toInt())
        }.getOrNull()
    }

    internal fun format(e: LoggedForecast) =
        String.format(Locale.US, "%d,%s,%.3f,%d", e.t, e.ai?.name ?: "", e.aiProb, e.zIndex)

    @Synchronized
    fun append(context: Context, f: Forecasts) {
        if (f.empty) return
        val e = LoggedForecast(f.t, f.ai?.cls, f.ai?.confidence ?: Double.NaN, f.zambretti?.zambretti?.index ?: -1)
        val keep = read(context).filter { f.t - it.t < KEEP_MS } + e
        File(context.filesDir, FILE).writeText(keep.joinToString("\n", postfix = "\n", transform = ::format))
    }

    fun clear(context: Context) = File(context.filesDir, FILE).delete()
}
