package app.altikit

import app.altikit.core.Atmosphere
import app.altikit.core.AutoMath
import app.altikit.core.AutoState
import app.altikit.core.BaroSample
import app.altikit.core.Config
import app.altikit.core.Mode
import app.altikit.core.Reading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoModeTest {
    private val alt0 = 500.0
    private val qnh0 = 1015.0
    private val p0 = 1015.0 * Math.pow(1 - alt0 / 44330.77, 1 / 0.190263)

    @Test
    fun stillWithSlowFall_isWeather() {
        val w = AutoMath.movingWeight(stepsPerMin = 0.0, rateHpaPerH = -1.0)
        assertEquals(0.0, w, 0.0)
        val (alt, qnh) = AutoMath.split(alt0, qnh0, p0, p0 - 1.0, w)
        assertEquals(alt0, alt, 1e-9)
        assertTrue("QNH falls with the weather", qnh < qnh0 - 1.0)
    }

    @Test
    fun walkingUphill_isHeight() {
        val w = AutoMath.movingWeight(stepsPerMin = 90.0, rateHpaPerH = -20.0)
        assertEquals(1.0, w, 0.0)
        val (alt, qnh) = AutoMath.split(alt0, qnh0, p0, p0 - 1.0, w)
        assertEquals(qnh0, qnh, 1e-6)
        assertTrue("about 8.5 m per hPa", alt - alt0 in 8.0..9.5)
    }

    @Test
    fun liftWithoutSteps_isHeight() {
        assertEquals(1.0, AutoMath.movingWeight(stepsPerMin = 0.0, rateHpaPerH = 300.0), 0.0)
        assertEquals(1.0, AutoMath.movingWeight(stepsPerMin = null, rateHpaPerH = 300.0), 0.0)
    }

    @Test
    fun stormJumpWhileStill_isWeatherWhenStepsKnown() {
        // A 3 hPa/h squall jump: weather if we know nobody walked, ambiguous without a step sensor.
        assertEquals(0.0, AutoMath.movingWeight(stepsPerMin = 0.0, rateHpaPerH = 3.0), 0.0)
        assertEquals(0.6, AutoMath.movingWeight(stepsPerMin = null, rateHpaPerH = 3.0), 1e-9)
    }

    @Test
    fun walkingOnTheFlat_ignoresTinyChanges() {
        assertEquals(0.0, AutoMath.movingWeight(stepsPerMin = 100.0, rateHpaPerH = 0.3), 0.0)
    }

    @Test
    fun mixedWeight_splitsAndStaysConsistent() {
        val p = p0 - 2.0
        val (alt, qnh) = AutoMath.split(alt0, qnh0, p0, p, 0.5)
        val full = Atmosphere.altitude(p, qnh0) - Atmosphere.altitude(p0, qnh0)
        assertEquals(alt0 + full / 2, alt, 1e-9)
        assertEquals("altitude and QNH describe the same pressure", alt, Atmosphere.altitude(p, qnh), 1e-6)
    }

    @Test
    fun slope_perHour() {
        val pts = (0..10).map { it * 60_000L to 1000.0 + it * 0.05 }
        assertEquals(3.0, AutoMath.slopePerHour(pts), 1e-9)
    }

    @Test
    fun freshInstall_startsAsPlainAltimeter() {
        val s = AutoState.fromConfig(Config())
        assertEquals(Atmosphere.altitude(950.0, Atmosphere.STD_HPA), s.altAt(950.0), 1e-9)
        assertEquals(Atmosphere.STD_HPA, s.qnhAt(950.0), 0.0)
    }

    @Test
    fun pendingChange_isTreatedAsWeather() {
        val s = AutoState(alt0, qnh0, p0, 0L, -1L, 0f)
        assertEquals(alt0, s.altAt(p0 - 3), 0.0)
        assertEquals(Atmosphere.seaLevel(p0 - 3, alt0), s.qnhAt(p0 - 3), 1e-9)
    }

    @Test
    fun relativeInAuto_ignoresWeatherDrift() {
        // Armed at p0 with QNH q0; then the weather drops 2 hPa while standing still.
        val cfg = Config(mode = Mode.AUTO, relRefHpa = p0, relRefQnh = qnh0, relDisplay = 0.0)
        val p = p0 - 2.0
        val still = AutoState(alt0, Atmosphere.seaLevel(p, alt0), p, 0L, -1L, 0f)
        val r = Reading.from(BaroSample(p, p, 0.0), cfg, still)
        assertEquals(0.0, r.relM, 1e-6)
        assertEquals(alt0, r.altM, 0.0)
        assertEquals(r.qnhHpa, r.mslHpa, 0.0)
    }

    @Test
    fun altimeterMode_reducesWeatherWithLastAltitude() {
        val cfg = Config(mode = Mode.ALTIMETER, qnh = qnh0, fixedAlt = 300.0)
        val r = Reading.from(BaroSample(p0, p0, 0.0), cfg)
        assertEquals(qnh0, r.qnhHpa, 0.0)
        assertEquals(Atmosphere.seaLevel(p0, 300.0), r.mslHpa, 1e-9)
    }
}
