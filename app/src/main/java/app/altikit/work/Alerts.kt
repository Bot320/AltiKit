package app.altikit.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.altikit.R
import app.altikit.core.Config
import app.altikit.core.Tendency
import app.altikit.core.Trend
import app.altikit.core.forecast.Forecast
import app.altikit.core.forecast.Source
import app.altikit.core.forecast.WxClass
import app.altikit.ui.MainActivity

enum class AlertKind(val title: String) { RAIN("RAIN LIKELY"), STORM("STORM RISK") }

/** Decision for the continuous forecast, kept pure so it can be unit tested. */
object AlertRules {
    const val MIN_GAP_MS = 3 * 3_600_000L
    const val AI_RAIN = 0.6
    const val AI_STORM = 0.4
    private const val RAIN_BIT = 1
    private const val STORM_BIT = 2

    /** Which conditions hold now, as a bit set (see [Config.alertState]). */
    fun state(f: Forecast?, trend: Trend?): Int {
        val storm = trend?.tendency == Tendency.STORM || when (f?.source) {
            Source.AI -> (f.probs?.get(WxClass.STORM.ordinal) ?: 0.0) >= AI_STORM
            Source.ZAMBRETTI -> f.cls == WxClass.STORM
            null -> false
        }
        val rain = when (f?.source) {
            Source.AI -> f.probs?.let { it[WxClass.RAIN.ordinal] + it[WxClass.STORM.ordinal] >= AI_RAIN } ?: false
            Source.ZAMBRETTI -> f.cls == WxClass.RAIN || f.cls == WxClass.STORM
            null -> false
        }
        return (if (rain) RAIN_BIT else 0) or (if (storm) STORM_BIT else 0)
    }

    /**
     * Alerts only when a condition newly appears, and at most once per [MIN_GAP_MS] per kind. A storm
     * outranks rain. Returns the alert to post (if any) and the config to save.
     */
    fun decide(cfg: Config, state: Int, now: Long): Pair<AlertKind?, Config> {
        val newly = state and cfg.alertState.inv()
        val kind = when {
            newly and STORM_BIT != 0 && now - cfg.alertStormT >= MIN_GAP_MS -> AlertKind.STORM
            newly and RAIN_BIT != 0 && now - cfg.alertRainT >= MIN_GAP_MS -> AlertKind.RAIN
            else -> null
        }
        val next = cfg.copy(
            alertState = state,
            alertStormT = if (kind == AlertKind.STORM) now else cfg.alertStormT,
            alertRainT = if (kind == AlertKind.RAIN) now else cfg.alertRainT,
        )
        return kind to next
    }
}

object Alerts {
    private const val CHANNEL = "weather"

    fun post(context: Context, kind: AlertKind, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Weather alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Rain or storm predicted from the pressure trend"
                enableVibration(true)
            }
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_baro)
            .setContentTitle(kind.title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(kind.ordinal + 1, n) }
    }
}
