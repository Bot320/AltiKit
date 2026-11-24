package app.altikit

import app.altikit.core.Atmosphere
import app.altikit.core.Changes
import app.altikit.core.Sample
import app.altikit.core.TrendLog
import app.altikit.ui.changeScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrendLogTest {
    private val h = 3_600_000L

    @Test
    fun parsesOldAndNewRows() {
        val old = TrendLog.parse("1700000000000,965.40")!!
        assertEquals(965.40, old.hpa, 0.0)
        assertTrue(old.msl.isNaN())
        val s = Sample(1700000000000, 965.4, 1021.37, 503.2)
        assertEquals(s, TrendLog.parse(TrendLog.format(s)))
        assertNull(TrendLog.parse("garbage"))
    }

    @Test
    fun migrationReducesOldRowsWithTheLastAltitude() {
        val rows = listOf(Sample(0, 960.0), Sample(1, 961.0, 1018.0, 480.0))
        val m = TrendLog.migrated(rows, 500.0)
        assertEquals(Atmosphere.seaLevel(960.0, 500.0), m[0].msl, 1e-9)
        assertEquals(500.0, m[0].alt, 0.0)
        assertEquals(rows[1], m[1])
    }

    @Test
    fun trendUsesWeatherPressure() {
        val now = 10 * h
        val samples = listOf(Sample(now - 3 * h, 960.0, 1015.0, 500.0))
        val t = TrendLog.trend(samples, 1011.0, now)!!
        assertEquals(-4.0, t.delta3h, 1e-9)
    }

    @Test
    fun valueAt_interpolatesAndRespectsGaps() {
        val s = listOf(0L to 1000.0, h to 1002.0, 5 * h to 1010.0)
        assertEquals(1001.0, TrendLog.valueAt(s, h / 2)!!, 1e-9)
        assertNull("inside a 4 h gap", TrendLog.valueAt(s, 3 * h))
        assertEquals("exact hit next to a gap", 1002.0, TrendLog.valueAt(s, h)!!, 0.0)
        assertNull(TrendLog.valueAt(s, -1))
        assertNull(TrendLog.valueAt(s, 6 * h))
    }

    @Test
    fun changeOver_shortHistoryReportsItsSpan() {
        val now = 10 * h
        val s = listOf(now - 12 * 60_000L to 500.0, now to 503.0)
        val c = Changes.over(s, 503.0, now, h)!!
        assertEquals(3.0, c.delta, 1e-9)
        assertEquals(12 * 60_000L, c.spanMs)
        assertNull("under a minute of history", Changes.over(listOf(now - 30_000L to 1.0), 2.0, now, h))
        val full = Changes.over(listOf(now - 2 * h to 490.0, now to 503.0), 503.0, now, h)!!
        assertEquals(h, full.spanMs)
        assertEquals(6.5, full.delta, 1e-9)
    }

    @Test
    fun changeBarPicksTheSmallestScaleThatFits() {
        val m = listOf(1.0, 2.0, 5.0, 10.0, 20.0, 50.0)
        assertEquals(1.0, changeScale(8.0, m, 11), 0.0)
        assertEquals(5.0, changeScale(-34.0, m, 11), 0.0)
        assertEquals(50.0, changeScale(5000.0, m, 11), 0.0)
    }
}
