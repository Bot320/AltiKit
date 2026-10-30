package app.altikit.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Text
import app.altikit.core.Mode
import app.altikit.core.Units
import kotlinx.coroutines.launch
import kotlin.math.pow
import kotlin.math.roundToLong

@Composable
fun TacList(content: ScalingLazyListScope.() -> Unit) {
    val listState = rememberScalingLazyListState()
    val scope = rememberCoroutineScope()
    ScalingLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Tac.Bg)
            .crown { px, _ -> scope.launch { listState.scrollBy(px) } },
        state = listState,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
        content = content,
    )
}

@Composable
fun CalibrateScreen(state: UiState, vm: MainViewModel, onNav: (String) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val haptics = rememberHaptics()
    val cfg = state.cfg
    val u = state.units
    var pending by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val route = pending
        pending = null
        if (route != null && res.values.any { it }) onNav(route)
    }
    fun withLocation(route: String) {
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (granted) onNav(route) else {
            pending = route
            launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
    TacList {
        item {
            Header(
                "CALIBRATE",
                sub = "${cfg.source.tag} · ${Units.age(cfg.calTime)}" +
                    if (cfg.calAccM.isNaN()) "" else " · ±${u.altStr(cfg.calAccM)}${u.alt}",
            )
        }
        item { TacButton("GPS + WX QNH", Tac.Green, Modifier.fillMaxWidth(), sub = "LOCAL QNH · NEEDS NET") { withLocation("run/web") } }
        item { TacButton("GPS ALTITUDE", Tac.Cyan, Modifier.fillMaxWidth(), sub = "AVG 30 S · OFFLINE") { withLocation("run/gps") } }
        item { TacButton("MANUAL ALT", Tac.Amber, Modifier.fillMaxWidth(), sub = "KNOWN ELEVATION") { onNav("edit/alt") } }
        item { TacButton("MANUAL QNH", Tac.Amber, Modifier.fillMaxWidth(), sub = "METAR / AIRFIELD") { onNav("edit/qnh") } }
        item {
            TacButton("RESET STD", Tac.Dim, Modifier.fillMaxWidth(), sub = "QNH 1013.25 hPa") {
                vm.resetStd(); haptics.confirm(); onBack()
            }
        }
    }
}

@Composable
fun CalRunScreen(gps: Boolean, vm: MainViewModel, onDone: () -> Unit) {
    val cal by vm.cal.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    LaunchedEffect(gps) { if (gps) vm.startGpsAltitude() else vm.startWeatherQnh() }
    DisposableEffect(Unit) { onDispose { vm.cancelCal() } }
    LaunchedEffect(cal is CalState.Proposal) { if (cal is CalState.Proposal) haptics.heavy() }

    Box(Modifier.fillMaxSize().background(Tac.Bg)) {
        when (val c = cal) {
            is CalState.Working -> {
                TickRing(c.progress, if (gps) Tac.Cyan else Tac.Green)
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Header(c.title, if (gps) Tac.Cyan else Tac.Green)
                    Spacer(Modifier.height(6.dp))
                    Text(c.detail, style = Tac.mono(11.sp, Tac.Text), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(10.dp))
                    SmallButton("ABORT", Tac.Dim) { onDone() }
                }
            }
            is CalState.Proposal -> Column(
                Modifier.align(Alignment.Center).width(148.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Header(c.title, Tac.Green)
                Spacer(Modifier.height(4.dp))
                c.lines.forEach { (k, v) -> DataRow(k, v, if (k == "Δ") Tac.Amber else Tac.Text) }
                c.warning?.let { Text(it, style = Tac.mono(8.sp, Tac.Amber), textAlign = TextAlign.Center) }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmallButton("APPLY", Tac.Green) { vm.applyProposal(c); haptics.confirm(); onDone() }
                    SmallButton("DISCARD", Tac.Dim) { onDone() }
                }
            }
            is CalState.Failed -> Column(
                Modifier.align(Alignment.Center).width(150.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Header(c.title, Tac.Red)
                Spacer(Modifier.height(6.dp))
                Text(c.message, style = Tac.mono(10.sp, Tac.Text), textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmallButton("RETRY", Tac.Amber) { if (gps) vm.startGpsAltitude() else vm.startWeatherQnh() }
                    SmallButton("BACK", Tac.Dim) { onDone() }
                }
            }
            CalState.Idle -> Unit
        }
    }
}

@Composable
fun SettingsScreen(state: UiState, vm: MainViewModel) {
    val cfg = state.cfg
    val baro = cfg.mode == Mode.BAROMETER
    TacList {
        item { Header("SETTINGS", Tac.Cyan) }
        item {
            TacButton("UNITS", Tac.Cyan, Modifier.fillMaxWidth(), value = if (cfg.imperial) "ft/inHg" else "m/hPa") {
                vm.setImperial(!cfg.imperial)
            }
        }
        item {
            TacButton(
                "MODE", Tac.Amber, Modifier.fillMaxWidth(),
                sub = if (baro) "ALT LOCKED · QNH LIVE" else "QNH FIXED · ALT LIVE",
                value = if (baro) "BARO" else "ALTI",
            ) { vm.setMode(if (baro) Mode.ALTIMETER else Mode.BAROMETER) }
        }
        item {
            val opts = listOf(15, 30, 60, 0)
            TacButton(
                "TREND LOG", Tac.Green, Modifier.fillMaxWidth(),
                sub = "BACKGROUND SAMPLING",
                value = if (cfg.logMinutes == 0) "OFF" else "${cfg.logMinutes}m",
            ) { vm.setLogMinutes(opts[(opts.indexOf(cfg.logMinutes) + 1) % opts.size]) }
        }
        item { TacButton("CLEAR LOG", Tac.Red, Modifier.fillMaxWidth()) { vm.clearLog() } }
        item {
            Text(
                "ALTIKIT 1.0\nSTD ATMOSPHERE · ISA",
                style = Tac.mono(9.sp, Tac.Dim), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Crown-driven numeric editor with a rotating tape and selectable step size. */
@Composable
fun ValueEditor(
    title: String,
    unit: String,
    initial: Double,
    steps: List<Double>,
    defaultStep: Int,
    decimals: Int,
    min: Double,
    max: Double,
    accent: Color,
    onConfirm: (Double) -> Unit,
) {
    var value by rememberSaveable { mutableDoubleStateOf(initial) }
    var stepIdx by rememberSaveable { mutableIntStateOf(defaultStep) }
    val stepper = remember { CrownStepper() }
    val haptics = rememberHaptics()
    val scale = 10.0.pow(decimals)
    fun bump(n: Int) {
        val nv = (((value + n * steps[stepIdx]) * scale).roundToLong() / scale).coerceIn(min, max)
        if (nv != value) {
            value = nv
            haptics.tick()
        }
    }
    val step = steps[stepIdx]
    Box(
        Modifier
            .fillMaxSize()
            .background(Tac.Bg)
            .crown { px, t -> stepper.feed(px, t).takeIf { it != 0 }?.let(::bump) }
    ) {
        RotatingDial(
            value = value, span = step * 60, minor = step, majorEvery = 5, labelEvery = 2, accent = accent,
            labelFmt = { Units.fmt(it, (decimals - (kotlin.math.log10(step * 10)).toInt()).coerceIn(0, decimals)) },
        )
        Column(Modifier.align(Alignment.Center).padding(top = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = Tac.label(accent))
            BigValue(Units.fmt(value, decimals), accent)
            Text(unit, style = Tac.mono(10.sp, Tac.Dim))
            Spacer(Modifier.height(4.dp))
            SmallButton("STEP ${Units.fmt(step, decimals.coerceAtMost(if (step < 1) decimals else 0))}", Tac.Cyan) {
                stepIdx = (stepIdx + 1) % steps.size
                haptics.tick()
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SmallButton(" − ", Tac.Dim) { bump(-1) }
                SmallButton("SET", Tac.Green) { haptics.confirm(); onConfirm(value) }
                SmallButton(" + ", Tac.Dim) { bump(1) }
            }
        }
    }
}
