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
import app.altikit.core.BaroSensor
import app.altikit.core.Prefs
import app.altikit.core.TrendLog
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
}

class LogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val p = BaroSensor(applicationContext).readOnce() ?: return Result.success()
        TrendLog.append(applicationContext, p)
        Updates.push(applicationContext)
        return Result.success()
    }
}
