package app.altikit.core

import android.content.Context
import android.content.SharedPreferences
import app.altikit.core.forecast.Source
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** SharedPreferences-backed config shared by the app, tile, complications and worker. */
class Prefs private constructor(context: Context) {
    private val sp = context.getSharedPreferences("altikit", Context.MODE_PRIVATE)
    private val _config = MutableStateFlow(read())
    val config: StateFlow<Config> = _config

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> _config.value = read() }

    init {
        sp.registerOnSharedPreferenceChangeListener(listener)
    }

    @Synchronized
    fun update(block: Config.() -> Config) = write(read().block())

    private fun read(): Config {
        val d = Config()
        return Config(
            qnh = sp.getD("qnh", d.qnh),
            source = runCatching { CalSource.valueOf(sp.getString("source", null)!!) }.getOrDefault(d.source),
            calTime = sp.getLong("calTime", d.calTime),
            calAccM = sp.getD("calAcc", d.calAccM),
            imperial = sp.getBoolean("imperial", d.imperial),
            mode = runCatching { Mode.valueOf(sp.getString("mode", null)!!) }.getOrDefault(d.mode),
            fixedAlt = sp.getD("fixedAlt", d.fixedAlt),
            autoP = sp.getD("autoP", d.autoP),
            autoT = sp.getLong("autoT", d.autoT),
            autoSteps = sp.getLong("autoSteps", d.autoSteps),
            relRefHpa = sp.getD("relRef", d.relRefHpa),
            relRefQnh = sp.getD("relRefQnh", d.relRefQnh),
            relDisplay = sp.getD("relDisplay", d.relDisplay),
            logMinutes = sp.getInt("logMinutes", d.logMinutes),
            fcstHours = sp.getInt("fcstHours", d.fcstHours),
            fcstAlerts = sp.getBoolean("fcstAlerts", d.fcstAlerts),
            alertSource = runCatching { Source.valueOf(sp.getString("alertSource", null)!!) }.getOrDefault(d.alertSource),
            alertRainT = sp.getLong("alertRainT", d.alertRainT),
            alertStormT = sp.getLong("alertStormT", d.alertStormT),
            alertState = sp.getInt("alertState", d.alertState),
        )
    }

    private fun write(c: Config) {
        sp.edit()
            .putD("qnh", c.qnh)
            .putString("source", c.source.name)
            .putLong("calTime", c.calTime)
            .putD("calAcc", c.calAccM)
            .putBoolean("imperial", c.imperial)
            .putString("mode", c.mode.name)
            .putD("fixedAlt", c.fixedAlt)
            .putD("autoP", c.autoP)
            .putLong("autoT", c.autoT)
            .putLong("autoSteps", c.autoSteps)
            .putD("relRef", c.relRefHpa)
            .putD("relRefQnh", c.relRefQnh)
            .putD("relDisplay", c.relDisplay)
            .putInt("logMinutes", c.logMinutes)
            .putInt("fcstHours", c.fcstHours)
            .putBoolean("fcstAlerts", c.fcstAlerts)
            .putString("alertSource", c.alertSource.name)
            .putLong("alertRainT", c.alertRainT)
            .putLong("alertStormT", c.alertStormT)
            .putInt("alertState", c.alertState)
            .apply()
        _config.value = c
    }

    private fun SharedPreferences.getD(k: String, def: Double) =
        if (contains(k)) Double.fromBits(getLong(k, 0)) else def

    private fun SharedPreferences.Editor.putD(k: String, v: Double) = putLong(k, v.toRawBits())

    companion object {
        @Volatile private var instance: Prefs? = null
        fun get(context: Context): Prefs =
            instance ?: synchronized(this) { instance ?: Prefs(context.applicationContext).also { instance = it } }
    }
}
