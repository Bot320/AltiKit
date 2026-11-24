package app.altikit

import app.altikit.core.RateWindow
import org.junit.Assert.assertEquals
import org.junit.Test

class RateWindowTest {
    // Stairs down at ~30 m/min ≈ +0.06 hPa/s near sea level, sampled at 10 Hz.
    private val rate = 0.06

    @Test
    fun steadyDescent_readsTrueRate() {
        val w = RateWindow(4_000)
        var r = 0.0
        for (i in 0..100) r = w.add(i * 100L, 1000 + rate * i / 10)
        assertEquals(rate, r, 1e-9)
    }

    @Test
    fun backToZero_oneWindowAfterStop() {
        val w = RateWindow(4_000)
        var p = 1000.0
        for (i in 0..150) {
            if (i > 0) p += rate / 10
            w.add(i * 100L, p)
        }
        // Level again from t = 15 s: the slope must be exactly 0 once the window holds only level samples.
        var r = Double.NaN
        for (i in 151..190) r = w.add(i * 100L, p)
        assertEquals(0.0, r, 1e-9)
    }

    @Test
    fun zeroUntilHalfFull() {
        val w = RateWindow(4_000)
        for (i in 0..19) assertEquals(0.0, w.add(i * 100L, 1000 + i * 0.01), 0.0)
    }
}
