package app.altikit.core.forecast

import android.content.Context
import app.altikit.core.Reading
import app.altikit.core.Sample
import java.time.Instant
import java.time.ZoneId

/** Weather in the forecast window, worst condition wins. RAIN includes snow. */
enum class WxClass(val label: String, val short: String) {
    FAIR("FAIR", "FAIR"),
    CLOUDY("CLOUDY", "CLDY"),
    RAIN("RAIN", "RAIN"),
    STORM("STORM", "STRM"),
}

enum class Source(val tag: String) { AI("AI"), ZAMBRETTI("ZAMB") }

data class Forecast(
    val source: Source,
    val cls: WxClass,
    /** Class probabilities (AI only). */
    val probs: DoubleArray? = null,
    /** Letter and text (Zambretti only). */
    val zambretti: Zambretti.Result? = null,
) {
    val confidence: Double? get() = probs?.get(cls.ordinal)

    /** Compact one-line form for the tile and history, e.g. "RAIN 64%" or "P CHANGEABLE, RAIN". */
    val summary: String
        get() = zambretti?.let { "${it.letter} ${it.text}" }
            ?: "${cls.label} ${((confidence ?: 0.0) * 100).toInt()}%"
}

/**
 * Both sources for one moment: the AI per horizon (hours → forecast) and Zambretti, which has no horizon.
 * Sources are missing when there's too little history (see [hoursOfData]).
 */
data class Forecasts(val aiByHours: Map<Int, Forecast>, val zambretti: Forecast?, val hoursOfData: Double, val t: Long) {
    /** The AI forecast for [hours], or the 12 h one used by alerts, the tile and the history. */
    fun ai(hours: Int = Forecaster.DEFAULT_HOURS): Forecast? = aiByHours[hours]
    val ai: Forecast? get() = ai()
    val empty get() = aiByHours.isEmpty() && zambretti == null

    operator fun get(s: Source) = when (s) {
        Source.AI -> ai
        Source.ZAMBRETTI -> zambretti
    }
}

object Forecaster {
    @Volatile private var model: NeuralForecast? = null

    /** Loads the network once per process (~15 KB); null if the asset is missing or broken. */
    fun model(context: Context): NeuralForecast? = model ?: synchronized(this) {
        model ?: runCatching {
            NeuralForecast(context.assets.open(ASSET).bufferedReader().use { it.readText() })
        }.getOrNull()?.also { model = it }
    }

    fun run(context: Context, samples: List<Sample>, reading: Reading, now: Long = System.currentTimeMillis()) =
        run(model(context), samples.map { it.t to it.msl }, reading.mslHpa, reading.altM, now, ZoneId.systemDefault())

    /** Pure version for tests: [series] is (time, sea-level hPa). */
    fun run(nn: NeuralForecast?, series: List<Pair<Long, Double>>, currentMsl: Double, altM: Double, now: Long, zone: ZoneId): Forecasts {
        val hours = Features.hoursOfData(series, now)
        val x = currentMsl.takeUnless { it.isNaN() }?.let { Features.extract(series, it, now, altM, zone) }
            ?: return Forecasts(emptyMap(), null, hours, now)
        val ai = nn?.let { net ->
            net.horizons.zip(net.predict(x)).associate { (h, p) ->
                h to Forecast(Source.AI, WxClass.entries[p.indices.maxBy { i -> p[i] }], p)
            }
        } ?: emptyMap()
        val month = Instant.ofEpochMilli(now).atZone(zone).monthValue
        val z = Zambretti.forecast(currentMsl, x[2], month)
        return Forecasts(ai, Forecast(Source.ZAMBRETTI, z.cls, zambretti = z), hours, now)
    }

    const val ASSET = "forecast_model.json"
    /** Horizons the page can switch between, and the one alerts, the tile and the history use. */
    val HORIZONS = listOf(3, 6, 12, 24)
    const val DEFAULT_HOURS = 12
}
