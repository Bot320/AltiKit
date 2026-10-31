package app.altikit

import app.altikit.core.Config
import app.altikit.core.Tendency
import app.altikit.core.Trend
import app.altikit.core.forecast.Features
import app.altikit.core.forecast.Forecast
import app.altikit.core.forecast.Forecaster
import app.altikit.core.forecast.NeuralForecast
import app.altikit.core.forecast.Source
import app.altikit.core.forecast.WxClass
import app.altikit.core.forecast.Zambretti
import app.altikit.work.AlertKind
import app.altikit.work.AlertRules
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneId

/** Checks the Kotlin port against vectors written by tools/forecast/train.py. */
class ForecastTest {
    private val model = NeuralForecast(File("src/main/assets/forecast_model.json").readText())
    private val golden = JSONObject(javaClass.classLoader!!.getResource("forecast_golden.json")!!.readText())

    @Test
    fun goldenVectors() {
        val cases = golden.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val arr = c.getJSONArray("series")
            val series = (0 until arr.length()).map { arr.getJSONArray(it).let { p -> p.getLong(0) to p.getDouble(1) } }
            val now = c.getLong("now")
            val zone = ZoneId.of(c.getString("zone"))
            val x = Features.extract(series, c.getDouble("current"), now, c.getDouble("alt"), zone)
            if (c.isNull("features")) {
                assertNull("case $i needs too much history", x)
                continue
            }
            val features = c.getJSONArray("features").let { f -> DoubleArray(f.length()) { f.getDouble(it) } }
            assertArrayEquals("features, case $i", features, x!!, 1e-9)
            val got = model.predict(x)
            val want = c.getJSONArray("probs")
            assertEquals(want.length(), got.size)
            for (h in got.indices) {
                val p = want.getJSONArray(h).let { f -> DoubleArray(f.length()) { f.getDouble(it) } }
                assertArrayEquals("probabilities, case $i, ${model.horizons[h]} h", p, got[h], 1e-6)
            }
            val month = Instant.ofEpochMilli(now).atZone(zone).monthValue
            val z = Zambretti.forecast(x[0], x[2], month)
            assertEquals("Zambretti index, case $i", c.getInt("zambretti"), z.index)
            assertEquals("Zambretti class, case $i", c.getString("zambretti_class"), z.cls.name)
            val both = Forecaster.run(model, series, c.getDouble("current"), c.getDouble("alt"), now, zone)
            assertEquals(z, both.zambretti!!.zambretti)
        }
    }

    @Test
    fun zambrettiClassicCases() {
        // High and rising in winter: settled fine. Deep and falling: stormy.
        assertEquals('A', Zambretti.forecast(1040.0, 2.0, 1).letter)
        assertEquals('Z', Zambretti.forecast(960.0, -3.0, 1).letter)
        assertEquals(true, Zambretti.forecast(940.0, 0.0, 1).exceptional)
    }

    private fun ai(rain: Double, storm: Double): Forecast {
        val p = doubleArrayOf(1 - rain - storm, 0.0, rain, storm)
        return Forecast(Source.AI, WxClass.entries[p.indices.maxBy { p[it] }], p)
    }

    @Test
    fun alertsFireOnceWhenRainAppears() {
        val h = 3_600_000L
        var cfg = Config(fcstAlerts = true, alertSource = Source.AI)
        val wet = AlertRules.state(ai(0.5, 0.2), null)
        val dry = AlertRules.state(ai(0.1, 0.0), null)

        var (kind, next) = AlertRules.decide(cfg, wet, 10 * h)
        assertEquals(AlertKind.RAIN, kind)
        cfg = next
        AlertRules.decide(cfg, wet, 11 * h).let { (k, n) -> assertNull("still raining: no repeat", k); cfg = n }
        AlertRules.decide(cfg, dry, 11 * h).let { (_, n) -> cfg = n }
        AlertRules.decide(cfg, wet, 12 * h).let { (k, n) -> assertNull("within 3 h of the last one", k); cfg = n }
        AlertRules.decide(cfg, dry, 13 * h).let { (_, n) -> cfg = n }
        kind = AlertRules.decide(cfg, wet, 14 * h).first
        assertEquals(AlertKind.RAIN, kind)
    }

    @Test
    fun stormOutranksRainAndRapidFallAlwaysCounts() {
        val cfg = Config(fcstAlerts = true)
        val z = Forecast(Source.ZAMBRETTI, WxClass.FAIR, zambretti = Zambretti.result(0))
        val state = AlertRules.state(z, Trend(-4.0, Tendency.STORM))
        val now = 1_700_000_000_000L
        assertEquals(AlertKind.STORM, AlertRules.decide(cfg, state, now).first)
        assertEquals(AlertKind.STORM, AlertRules.decide(cfg, AlertRules.state(ai(0.3, 0.45), null), now).first)
    }
}
