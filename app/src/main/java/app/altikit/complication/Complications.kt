package app.altikit.complication

import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import app.altikit.core.Atmosphere
import app.altikit.core.Config
import app.altikit.core.Prefs
import app.altikit.core.Reading
import app.altikit.core.Snapshot
import app.altikit.core.Trend
import app.altikit.core.Units

private fun plain(s: String): ComplicationText = PlainComplicationText.Builder(s).build()

/** Shared plumbing: take a reading, then let the subclass format it. */
abstract class BaroComplicationService : SuspendingComplicationDataSourceService() {
    abstract fun build(type: ComplicationType, cfg: Config, r: Reading, trend: Trend?): ComplicationData?

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val snap = Snapshot.take(applicationContext) ?: return NoDataComplicationData()
        return build(request.complicationType, snap.config, snap.reading, snap.trend)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val cfg = Prefs.get(applicationContext).config.value
        val p = 960.0
        return build(type, cfg, Reading(p, Atmosphere.STD_HPA, Atmosphere.altitude(p, Atmosphere.STD_HPA), 0.0, 0.0, Double.NaN), null)
    }
}

class AltitudeComplicationService : BaroComplicationService() {
    override fun build(type: ComplicationType, cfg: Config, r: Reading, trend: Trend?): ComplicationData? {
        val u = Units(cfg.imperial)
        val alt = u.altStr(r.altM)
        val desc = plain("Altitude $alt ${u.alt}")
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(plain(alt), desc)
                .setTitle(plain(u.alt)).build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(plain("ALT $alt ${u.alt}"), desc)
                .setTitle(plain("STD ${u.altStr(r.stdAltM)} · ${cfg.source.tag}")).build()
            // Ring spans 0–5000 m (0–16 000 ft).
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                u.altTo(r.altM).toFloat().coerceIn(0f, u.altTo(5000.0).toFloat()), 0f, u.altTo(5000.0).toFloat(), desc,
            ).setText(plain(alt)).setTitle(plain(u.alt)).build()
            else -> null
        }
    }
}

class PressureComplicationService : BaroComplicationService() {
    override fun build(type: ComplicationType, cfg: Config, r: Reading, trend: Trend?): ComplicationData? {
        val u = Units(cfg.imperial)
        val qnh = u.pressStr(r.qnhHpa)
        val arrow = trend?.tendency?.arrow ?: ""
        val desc = plain("Pressure $qnh ${u.press}")
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(plain(if (cfg.imperial) qnh else u.pressStr(r.qnhHpa).substringBefore('.')), desc)
                .setTitle(plain(arrow.ifEmpty { u.press })).build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(plain("QNH $qnh ${u.press} $arrow"), desc)
                .setTitle(plain("RAW ${u.pressStr(r.stationHpa)}" + (trend?.let { " · ${u.pressSigned(it.delta3h)}/3h" } ?: ""))).build()
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                r.qnhHpa.toFloat().coerceIn(950f, 1050f), 950f, 1050f, desc,
            ).setText(plain(if (cfg.imperial) qnh else qnh.substringBefore('.'))).setTitle(plain(arrow.ifEmpty { "QNH" })).build()
            else -> null
        }
    }
}
