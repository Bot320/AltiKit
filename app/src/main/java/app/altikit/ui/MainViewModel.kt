package app.altikit.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.altikit.core.Atmosphere
import app.altikit.core.AutoBaro
import app.altikit.core.AutoState
import app.altikit.core.BaroSample
import app.altikit.core.BaroSensor
import app.altikit.core.CalSource
import app.altikit.core.Change
import app.altikit.core.Changes
import app.altikit.core.Config
import app.altikit.core.Locator
import app.altikit.core.Mode
import app.altikit.core.Motion
import app.altikit.core.Prefs
import app.altikit.core.Reading
import app.altikit.core.Sample
import app.altikit.core.Trend
import app.altikit.core.TrendLog
import app.altikit.core.Units
import app.altikit.core.Weather
import app.altikit.core.forecast.ForecastLog
import app.altikit.core.forecast.Forecaster
import app.altikit.core.forecast.Forecasts
import app.altikit.core.forecast.LoggedForecast
import app.altikit.core.forecast.Source
import app.altikit.work.AlertKind
import app.altikit.work.Alerts
import app.altikit.work.Updates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Change-bar windows in minutes; tapping the readout cycles through them. */
val ALT_WINDOWS = listOf(15, 60, 360)
val MSL_WINDOWS = listOf(60, 180, 720)

data class UiState(
    val cfg: Config,
    val sensorOk: Boolean,
    val reading: Reading?,
    val trend: Trend?,
    val log: List<Sample>,
    /** Live AUTO split, null in the other modes. */
    val auto: AutoState? = null,
    /** Altitude (m) and sea-level pressure (hPa) change per window in minutes. */
    val altChange: Map<Int, Change?> = emptyMap(),
    val mslChange: Map<Int, Change?> = emptyMap(),
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
    private val autoBaro = AutoBaro.get(app)
    private val log = MutableStateFlow<List<Sample>>(emptyList())
    private var lastP: Double? = null

    // Bumped after the activity-recognition permission is granted, to re-register the step counter.
    private val motionRetry = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val steps: Flow<Long?> = motionRetry.flatMapLatest {
        Motion(app).steps().map<Long, Long?> { it }.onStart { emit(null) }
    }

    // Live history while the app is open (one point per 10 s, 6 h) for the change bars.
    private val hist = ArrayDeque<Sample>()
    private var seriesCache: Pair<List<Pair<Long, Double>>, List<Pair<Long, Double>>>? = null
    private var seriesFrom: List<Sample>? = null

    val state: StateFlow<UiState> = combine(
        prefs.config,
        sensor.readings().map<BaroSample, BaroSample?> { it }.onStart { emit(null) }
            .onEach { s -> s?.let { lastP = it.hpa } },
        steps,
        log,
    ) { cfg, s, n, samples ->
        val now = System.currentTimeMillis()
        val auto = if (cfg.mode == Mode.AUTO && s != null) autoBaro.feedLive(s.hpa, now, n) else null
        val reading = s?.let { Reading.from(it, cfg, auto) }
        reading?.let { record(it, now) }
        val (altSeries, mslSeries) = series(samples)
        UiState(
            cfg = cfg,
            sensorOk = sensor.available,
            reading = reading,
            trend = TrendLog.trend(samples, reading?.mslHpa),
            log = samples,
            auto = auto,
            altChange = ALT_WINDOWS.associateWith { w ->
                reading?.let { Changes.over(altSeries, it.altM, now, w * 60_000L) }
            },
            mslChange = MSL_WINDOWS.associateWith { w ->
                reading?.let { Changes.over(mslSeries, it.mslHpa, now, w * 60_000L) }
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(3_000), UiState(prefs.config.value, sensor.available, null, null, emptyList()))

    private val _cal = MutableStateFlow<CalState>(CalState.Idle)
    val cal: StateFlow<CalState> = _cal
    private var calJob: Job? = null

    private val _forecast = MutableStateFlow<Forecasts?>(null)
    val forecast: StateFlow<Forecasts?> = _forecast

    private val _history = MutableStateFlow<List<LoggedForecast>>(emptyList())
    val history: StateFlow<List<LoggedForecast>> = _history

    init {
        refreshLog()
    }

    override fun onCleared() {
        autoBaro.flush()
    }

    private fun record(r: Reading, now: Long) {
        if (hist.isNotEmpty() && now - hist.last().t < HIST_STEP_MS) return
        hist.addLast(Sample.of(r, now))
        while (now - hist.first().t > HIST_KEEP_MS) hist.removeFirst()
        seriesCache = null
    }

    /** Trend log followed by the live buffer, as (time, altitude) and (time, sea-level pressure). */
    private fun series(samples: List<Sample>): Pair<List<Pair<Long, Double>>, List<Pair<Long, Double>>> {
        seriesCache?.takeIf { seriesFrom === samples }?.let { return it }
        val start = hist.firstOrNull()?.t ?: Long.MAX_VALUE
        val merged = samples.filter { it.t < start && !it.msl.isNaN() } + hist
        seriesFrom = samples
        return (merged.map { it.t to it.alt } to merged.map { it.t to it.msl }).also { seriesCache = it }
    }

    /** Reloads the trend log and adds a sample if the last one is stale, so a fresh install gets data quickly. */
    fun refreshLog() {
        viewModelScope.launch {
            val r = state.value.reading ?: currentReading()
            val app = getApplication<Application>()
            log.value = withContext(Dispatchers.IO) {
                TrendLog.migrate(app, prefs.config.value.fixedAlt)
                if (r != null) TrendLog.appendIfStale(app, Sample.of(r))
                TrendLog.read(app)
            }
        }
    }

    private suspend fun pressure(): Double? = lastP ?: sensor.readOnce()

    /** Reading for the current pressure, also when the live stream hasn't started yet. */
    private suspend fun currentReading(): Reading? {
        val p = pressure() ?: return null
        val cfg = prefs.config.value
        val auto = if (cfg.mode == Mode.AUTO) {
            autoBaro.feedOnce(p, System.currentTimeMillis(), Motion(getApplication()).readSteps())
        } else null
        return Reading.from(BaroSample(p, p, 0.0), prefs.config.value, auto)
    }

    // ---- forecast ----

    /** One-time forecast from the trend log; microseconds of work, so it simply runs on demand. */
    fun runForecast() = viewModelScope.launch {
        val r = state.value.reading ?: currentReading() ?: return@launch
        val samples = log.value
        _forecast.value = withContext(Dispatchers.IO) { Forecaster.run(getApplication(), samples, r) }
    }

    fun loadHistory() = viewModelScope.launch {
        _history.value = withContext(Dispatchers.IO) { ForecastLog.read(getApplication()) }
    }

    // ---- calibration ----

    private fun applyQnh(qnh: Double, source: CalSource, accM: Double, p: Double?) {
        val alt = p?.let { Atmosphere.altitude(it, qnh) }
        prefs.update {
            copy(
                qnh = qnh,
                source = source,
                calTime = System.currentTimeMillis(),
                calAccM = accM,
                fixedAlt = alt ?: fixedAlt,
            )
        }
        if (p != null && alt != null) autoBaro.reanchor(p, alt, qnh)
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

    /** Switches mode without a jump: the current altitude and QNH carry over. */
    fun setMode(mode: Mode) = viewModelScope.launch {
        val cfg = prefs.config.value
        if (mode == cfg.mode) return@launch
        val p = pressure()
        val r = p?.let { Reading.from(BaroSample(it, it, 0.0), cfg, if (cfg.mode == Mode.AUTO) autoBaro.state.value else null) }
        prefs.update { copy(mode = mode, qnh = r?.qnhHpa ?: qnh, fixedAlt = r?.altM ?: fixedAlt) }
        if (mode == Mode.AUTO && p != null && r != null) autoBaro.reanchor(p, r.altM, r.qnhHpa)
        Updates.push(getApplication())
    }

    /** Call after the activity-recognition permission was granted so AUTO starts using steps. */
    fun motionPermissionChanged() {
        motionRetry.value++
    }

    fun setImperial(imperial: Boolean) {
        prefs.update { copy(imperial = imperial) }
        Updates.push(getApplication())
    }

    fun setLogMinutes(minutes: Int) {
        prefs.update { copy(logMinutes = minutes) }
        Updates.schedule(getApplication())
    }

    /** Continuous forecast needs the background log, so turning it on also turns logging on. */
    fun setFcstAlerts(on: Boolean) {
        prefs.update { copy(fcstAlerts = on, logMinutes = if (on && logMinutes == 0) 15 else logMinutes) }
        Updates.schedule(getApplication())
    }

    fun setFcstHours(hours: Int) {
        prefs.update { copy(fcstHours = hours) }
    }

    fun setAlertSource(source: Source) {
        prefs.update { copy(alertSource = source) }
        Updates.push(getApplication())
    }

    fun testAlert() {
        Alerts.post(getApplication(), AlertKind.RAIN, "TEST · ALERTS WORK")
    }

    fun clearLog() = viewModelScope.launch {
        withContext(Dispatchers.IO) { TrendLog.clear(getApplication()) }
        log.value = emptyList()
    }

    /** Arms relative mode so the current position reads [displayM]. */
    fun setRelative(displayM: Double) = viewModelScope.launch {
        val p = pressure() ?: return@launch
        val qnh = state.value.reading?.qnhHpa ?: currentReading()?.qnhHpa ?: prefs.config.value.qnh
        prefs.update { copy(relRefHpa = p, relRefQnh = qnh, relDisplay = displayM) }
    }

    fun clearRelative() = prefs.update { copy(relRefHpa = Double.NaN, relRefQnh = Double.NaN, relDisplay = 0.0) }

    private companion object {
        const val HIST_STEP_MS = 10_000L
        const val HIST_KEEP_MS = 6 * 3_600_000L
    }
}
