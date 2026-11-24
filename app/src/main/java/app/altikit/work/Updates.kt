package app.altikit.work

import android.content.ComponentName
import android.content.Context
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.altikit.complication.AltitudeComplicationService
import app.altikit.complication.PressureComplicationService
import app.altikit.core.Prefs
import app.altikit.core.Snapshot
import app.altikit.core.Units
import app.altikit.core.forecast.ForecastLog
import app.altikit.tile.AltTileService
import java.util.concurrent.TimeUnit

object Updates {
    private const val WORK = "trend-log"

    /** (Re)schedules the periodic trend logger according to the current setting. */
    fun schedule(context: Context) {
        val minutes = Prefs.get(context).config.value.logMinutes
        val wm = WorkManager.getInstance(context)
        if (minutes <= 0) {
            wm.cancelUniqueWork(WORK)
            return
        }
        val req = PeriodicWorkRequestBuilder<LogWorker>(minutes.toLong(), TimeUnit.MINUTES).build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    /** Asks the tile and complications to refresh after calibration or settings change. */
    fun push(context: Context) {
        runCatching { TileService.getUpdater(context).requestUpdate(AltTileService::class.java) }
        listOf(AltitudeComplicationService::class.java, PressureComplicationService::class.java).forEach {
            runCatching {
                ComplicationDataSourceUpdateRequester.create(context, ComponentName(context, it)).requestUpdateAll()
            }
        }
    }

    /** Continuous forecast: notifies when rain or a storm newly becomes likely (see [AlertRules]). */
    fun checkAlerts(context: Context, snap: Snapshot, now: Long = System.currentTimeMillis()) {
        val f = snap.forecasts[snap.config.alertSource]
        val state = AlertRules.state(f, snap.trend)
        var kind: AlertKind? = null
        Prefs.get(context).update { AlertRules.decide(this, state, now).also { kind = it.first }.second }
        val k = kind ?: return
        val u = Units(snap.config.imperial)
        val text = listOfNotNull(
            f?.let { "${it.source.tag}: ${it.summary} · 12H" },
            snap.trend?.let { "3H ${u.pressSigned(it.delta3h)} ${u.press}" },
        ).joinToString("\n")
        Alerts.post(context, k, text)
    }
}

/** Background sample: logs pressure, advances AUTO mode, records both forecasts and checks alerts. */
class LogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val snap = Snapshot.take(ctx, logMinAgeMs = 60_000L) ?: return Result.success()
        ForecastLog.append(ctx, snap.forecasts)
        if (snap.config.fcstAlerts) Updates.checkAlerts(ctx, snap)
        Updates.push(ctx)
        return Result.success()
    }
}
