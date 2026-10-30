package app.altikit.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.altikit.core.Atmosphere
import app.altikit.core.BaroSample
import app.altikit.core.BaroSensor
import app.altikit.core.CalSource
import app.altikit.core.Config
import app.altikit.core.Locator
import app.altikit.core.Mode
import app.altikit.core.Prefs
import app.altikit.core.Reading
import app.altikit.core.Sample
import app.altikit.core.Trend
import app.altikit.core.TrendLog
import app.altikit.core.Units
import app.altikit.core.Weather
import app.altikit.work.Updates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val cfg: Config,
    val sensorOk: Boolean,
    val reading: Reading?,
    val trend: Trend?,
    val log: List<Sample>,
) {
    val units get() = Units(cfg.imperial)
}

sealed interface CalState {
    data object Idle : CalState
    data class Working(val title: String, val detail: String, val progress: Float) : CalState
    data class Proposal(
        val title: String,
        val source: CalSource,
        val qnh: Double,
        val accM: Double,
        val lines: List<Pair<String, String>>,
        val warning: String? = null,
    ) : CalState
    data class Failed(val title: String, val message: String) : CalState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs.get(app)
    private val sensor = BaroSensor(app)
    private val log = MutableStateFlow<List<Sample>>(emptyList())
    private var lastP: Double? = null

    val state: StateFlow<UiState> = combine(
        prefs.config,
        sensor.readings().map<BaroSample, BaroSample?> { it }.onStart { emit(null) }
            .onEach { s -> s?.let { lastP = it.hpa } },
        log,
    ) { cfg, s, samples ->
        UiState(
            cfg = cfg,
            sensorOk = sensor.available,
            reading = s?.let { Reading.from(it, cfg) },
            trend = TrendLog.trend(samples, s?.hpa),
            log = samples,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(3_000), UiState(prefs.config.value, sensor.available, null, null, emptyList()))

    private val _cal = MutableStateFlow<CalState>(CalState.Idle)
    val cal: StateFlow<CalState> = _cal
    private var calJob: Job? = null

    init {
        refreshLog()
    }

    /** Reloads the trend log and adds a sample if the last one is stale, so a fresh install gets data quickly. */
    fun refreshLog() {
        viewModelScope.launch {
            val p = lastP ?: sensor.readOnce()
            log.value = withContext(Dispatchers.IO) {
                if (p != null) TrendLog.appendIfStale(getApplication(), p)
                TrendLog.read(getApplication())
            }
        }
    }

    private suspend fun pressure(): Double? = lastP ?: sensor.readOnce()

    // ---- calibration ----

    private fun applyQnh(qnh: Double, source: CalSource, accM: Double, p: Double?) {
        prefs.update {
            copy(
                qnh = qnh,
                source = source,
                calTime = System.currentTimeMillis(),
                calAccM = accM,
                fixedAlt = p?.let { Atmosphere.altitude(it, qnh) } ?: fixedAlt,
            )
        }
        Updates.push(getApplication())
    }

    fun calibrateAltitude(altM: Double) = viewModelScope.launch {
        val p = pressure() ?: return@launch
        applyQnh(Atmosphere.seaLevel(p, altM), CalSource.MANUAL_ALT, Double.NaN, p)
    }

    fun calibrateQnh(qnh: Double) = viewModelScope.launch {
        applyQnh(qnh, CalSource.MANUAL_QNH, Double.NaN, pressure())
    }

    fun resetStd() = viewModelScope.launch {
        applyQnh(Atmosphere.STD_HPA, CalSource.STD, Double.NaN, pressure())
    }

    fun applyProposal(p: CalState.Proposal) = viewModelScope.launch {
        applyQnh(p.qnh, p.source, p.accM, pressure())
        _cal.value = CalState.Idle
    }

    fun cancelCal() {
        calJob?.cancel()
        _cal.value = CalState.Idle
    }

    fun startGpsAltitude(durationMs: Long = 30_000) {
        calJob?.cancel()
        calJob = viewModelScope.launch {
            val title = "GPS ALT"
            _cal.value = CalState.Working(title, "ACQUIRING…", 0f)
            val t0 = System.currentTimeMillis()
            val u = state.value.units
            val fix = Locator(getApplication()).averageAltitude(durationMs) { n, est, acc ->
                val frac = ((System.currentTimeMillis() - t0).toFloat() / durationMs).coerceIn(0f, 1f)
                val detail = if (est == null) "FIX $n" else "FIX $n  ${u.altStr(est)}${u.alt} ±${u.altStr(acc ?: 0.0)}"
                _cal.value = CalState.Working(title, detail, frac)
            }
            val p = pressure()
            if (fix == null || p == null) {
                _cal.value = CalState.Failed(title, if (p == null) "NO PRESSURE SENSOR" else "NO VERTICAL FIX.\nGO OUTSIDE, CLEAR SKY.")
                return@launch
            }
            val qnh = Atmosphere.seaLevel(p, fix.altM)
            val baroAlt = state.value.reading?.altM ?: Atmosphere.altitude(p, prefs.config.value.qnh)
            _cal.value = CalState.Proposal(
                title = title,
                source = CalSource.GPS,
                qnh = qnh,
                accM = fix.accM,
                lines = listOf(
                    "GPS" to "${u.altStr(fix.altM)} ±${u.altStr(fix.accM)} ${u.alt}",
                    "BARO" to "${u.altStr(baroAlt)} ${u.alt}",
                    "Δ" to "${u.altSigned(fix.altM - baroAlt, 0)} ${u.alt}",
                    "QNH" to "${u.pressStr(qnh)} ${u.press}",
                ),
                warning = if (!fix.msl) "ELLIPSOID HEIGHT – NO GEOID CORR." else null,
            )
        }
    }

    fun startWeatherQnh() {
        calJob?.cancel()
        calJob = viewModelScope.launch {
            val title = "GPS + WX"
            val u = state.value.units
            try {
                _cal.value = CalState.Working(title, "LOCATING…", 0.2f)
                val loc = Locator(getApplication()).quickFix()
                    ?: run { _cal.value = CalState.Failed(title, "NO POSITION"); return@launch }
                _cal.value = CalState.Working(title, "FETCHING QNH…", 0.6f)
                val qnh = Weather.fetchQnh(loc.latitude, loc.longitude)
                val p = pressure()
                val newAlt = p?.let { Atmosphere.altitude(it, qnh) }
                val oldAlt = state.value.reading?.altM
                _cal.value = CalState.Proposal(
                    title = title,
                    source = CalSource.WEB,
                    qnh = qnh,
                    // Model QNH is typically good to ~1 hPa ≈ 8 m.
                    accM = 8.0,
                    lines = listOfNotNull(
                        "QNH" to "${u.pressStr(qnh)} ${u.press}",
                        newAlt?.let { "ALT" to "${u.altStr(it)} ${u.alt}" },
                        if (newAlt != null && oldAlt != null) "Δ" to "${u.altSigned(newAlt - oldAlt, 0)} ${u.alt}" else null,
                        "POS" to String.format(java.util.Locale.US, "%.3f %.3f", loc.latitude, loc.longitude),
                    ),
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _cal.value = CalState.Failed(title, "NO DATA: ${e.message ?: e.javaClass.simpleName}".take(60).uppercase())
            }
        }
    }

    // ---- modes & settings ----

    fun setMode(mode: Mode) = viewModelScope.launch {
        val p = pressure()
        prefs.update {
            when {
                mode == this.mode -> this
                // Lock current altitude; QNH now floats with the weather.
                mode == Mode.BAROMETER -> copy(mode = mode, fixedAlt = p?.let { Atmosphere.altitude(it, qnh) } ?: fixedAlt)
                // Carry the live QNH over so altitude continues seamlessly.
                else -> copy(mode = mode, qnh = p?.let { Atmosphere.seaLevel(it, fixedAlt) } ?: qnh)
            }
        }
        Updates.push(getApplication())
    }

    fun setImperial(imperial: Boolean) {
        prefs.update { copy(imperial = imperial) }
        Updates.push(getApplication())
    }

    fun setLogMinutes(minutes: Int) {
        prefs.update { copy(logMinutes = minutes) }
        Updates.schedule(getApplication())
    }

    fun clearLog() = viewModelScope.launch {
        withContext(Dispatchers.IO) { TrendLog.clear(getApplication()) }
        log.value = emptyList()
    }

    /** Arms relative mode so the current position reads [displayM]. */
    fun setRelative(displayM: Double) = viewModelScope.launch {
        val p = pressure() ?: return@launch
        prefs.update { copy(relRefHpa = p, relDisplay = displayM) }
    }

    fun clearRelative() = prefs.update { copy(relRefHpa = Double.NaN, relDisplay = 0.0) }
}
