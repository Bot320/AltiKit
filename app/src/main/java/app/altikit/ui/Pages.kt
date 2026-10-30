package app.altikit.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.wear.compose.foundation.pager.VerticalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.wear.compose.material.Text
import androidx.wear.compose.material3.VerticalPageIndicator
import app.altikit.core.Mode
import app.altikit.core.Tendency
import app.altikit.core.Units
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs
import kotlin.math.roundToLong

private const val PAGES = 5
private const val INDICATOR_HIDE_MS = 1_500L

@Composable
fun MainPager(state: UiState, vm: MainViewModel, onNav: (String) -> Unit) {
    val pager = rememberPagerState { PAGES }

    LifecycleResumeEffect(Unit) {
        vm.refreshLog()
        onPauseOrDispose { }
    }

    // Wear's pager snaps page-by-page on the crown with system haptics.
    Box(Modifier.fillMaxSize().background(Tac.Bg)) {
        VerticalPager(pager, Modifier.fillMaxSize()) { page ->
            Box(Modifier.fillMaxSize()) {
                when (page) {
                    0 -> AltitudePage(state)
                    1 -> BaroPage(state)
                    2 -> RelPage(state, vm, onNav)
                    3 -> TrendPage(state)
                    else -> SetupPage(state, onNav)
                }
            }
        }
        // The dots sit over the bezel scales, so only show them while paging and briefly after.
        var showIndicator by remember { mutableStateOf(true) }
        LaunchedEffect(pager) {
            snapshotFlow { pager.isScrollInProgress to pager.currentPage }.collectLatest { (scrolling, _) ->
                showIndicator = true
                if (!scrolling) {
                    delay(INDICATOR_HIDE_MS)
                    showIndicator = false
                }
            }
        }
        val indicatorAlpha by animateFloatAsState(
            targetValue = if (showIndicator) 1f else 0f,
            animationSpec = tween(if (showIndicator) 150 else 400),
            label = "indicatorAlpha",
        )
        VerticalPageIndicator(
            pagerState = pager,
            modifier = Modifier.align(Alignment.CenterEnd).graphicsLayer { alpha = indicatorAlpha },
        )
        // Clock sits in the dial's bottom gap so it never covers the 12 o'clock index.
        BottomClock(Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp))
        if (!state.sensorOk) {
            Text("NO BAROMETER", style = Tac.mono(10.sp, Tac.Red, bold = true), modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp))
        }
    }
}

@Composable
private fun BottomClock(modifier: Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000 - now % 60_000)
        }
    }
    val fmt = remember { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT) }
    Text(fmt.format(java.util.Date(now)), style = Tac.mono(10.sp, Tac.Dim), modifier = modifier)
}

@Composable
private fun BoxScope.CenterStack(content: @Composable () -> Unit) {
    Column(
        Modifier.align(Alignment.Center).width(128.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

@Composable
private fun Readout(top: String, topColor: Color, value: String, valueColor: Color, unit: String) {
    Text(top, style = Tac.label(topColor))
    Box(Modifier.drawBehind { brackets(Tac.Tick, 6.dp.toPx()) }.padding(horizontal = 8.dp)) {
        BigValue(value, valueColor)
    }
    Text(unit, style = Tac.mono(10.sp, Tac.Dim))
}

@Composable
private fun AltitudePage(state: UiState) {
    val r = state.reading
    val u = state.units
    val cfg = state.cfg
    val disp = r?.let { u.altTo(it.altM) } ?: Double.NaN
    Box(Modifier.fillMaxSize()) {
        if (u.imperial) RotatingDial(disp, span = 300.0, minor = 10.0, majorEvery = 5, labelEvery = 2, accent = Tac.Amber)
        else RotatingDial(disp, span = 100.0, minor = 2.0, majorEvery = 5, labelEvery = 2, accent = Tac.Amber)
        VsArc(r?.vsMpm ?: Double.NaN)
        CenterStack {
            Spacer(Modifier.height(6.dp))
            Readout("ALT·${cfg.source.tag}", Tac.Dim, r?.let { u.altStr(it.altM) } ?: "----", Tac.Amber,
                u.alt + if (cfg.mode == Mode.BAROMETER) " · LOCKED" else "")
            Ruler(Modifier.width(96.dp).height(8.dp).padding(vertical = 1.dp), offset = (disp / 2).toFloat().takeIf { !it.isNaN() } ?: 0f)
            Spacer(Modifier.height(3.dp))
            DataRow("STD", r?.let { "${u.altStr(it.stdAltM)} ${u.alt}" } ?: "--", Tac.Cyan, Modifier.width(110.dp))
            val vs = r?.vsMpm ?: Double.NaN
            DataRow("V/S", "${Units.fmt(u.altTo(vs), 1, signed = true)}/min",
                when { vs.isNaN() || abs(vs) < 0.5 -> Tac.Text; vs > 0 -> Tac.Green; else -> Tac.Red }, Modifier.width(110.dp))
        }
    }
}

@Composable
private fun BaroPage(state: UiState) {
    val r = state.reading
    val u = state.units
    val trend = state.trend
    val qnh = r?.qnhHpa ?: Double.NaN
    Box(Modifier.fillMaxSize()) {
        ArcGauge(
            value = qnh, min = 950.0, max = 1050.0, minor = 1.0, majorEvery = 10, labelEvery = 2,
            accent = Tac.Amber,
            ghost = if (trend != null && !qnh.isNaN()) qnh - trend.delta3h else Double.NaN,
            labelFmt = { if (u.imperial) Units.fmt(u.pressTo(it), 1) else it.roundToLong().toString() },
        )
        CenterStack {
            Spacer(Modifier.height(4.dp))
            Readout("QNH·CAL", Tac.Dim, r?.let { u.pressStr(it.qnhHpa) } ?: "----", Tac.Amber, u.press)
            Ruler(Modifier.width(96.dp).height(8.dp).padding(vertical = 1.dp))
            Spacer(Modifier.height(3.dp))
            DataRow("RAW", r?.let { u.pressStr(it.stationHpa) } ?: "--", Tac.Cyan, Modifier.width(112.dp))
            if (trend != null) {
                DataRow("3H ${trend.tendency.arrow}", u.pressSigned(trend.delta3h), tendencyColor(trend.tendency), Modifier.width(112.dp))
            } else {
                DataRow("3H", "LOGGING", Tac.Dim, Modifier.width(112.dp))
            }
            if (state.cfg.mode == Mode.BAROMETER) {
                Text("ALT LOCK ${u.altStr(state.cfg.fixedAlt)}${u.alt}", style = Tac.mono(9.sp, Tac.Violet))
            }
        }
    }
}

fun tendencyColor(t: Tendency) = when (t) {
    Tendency.RISING_FAST, Tendency.RISING -> Tac.Green
    Tendency.STEADY -> Tac.Text
    Tendency.FALLING -> Tac.Amber
    Tendency.STORM -> Tac.Red
}

@Composable
private fun RelPage(state: UiState, vm: MainViewModel, onNav: (String) -> Unit) {
    val r = state.reading
    val u = state.units
    val active = state.cfg.relActive
    val haptics = rememberHaptics()
    val rel = r?.relM ?: Double.NaN
    val disp = if (active) u.altTo(rel) else 0.0
    Box(Modifier.fillMaxSize()) {
        if (u.imperial) RotatingDial(disp, span = 60.0, minor = 1.0, majorEvery = 5, labelEvery = 2, accent = Tac.Violet)
        else RotatingDial(disp, span = 20.0, minor = 0.5, majorEvery = 2, labelEvery = 5, accent = Tac.Violet)
        CenterStack {
            Spacer(Modifier.height(8.dp))
            Readout("REL·ΔALT", Tac.Violet, if (active) u.altSigned(rel, 1) else "--.-", Tac.Violet, u.alt)
            Spacer(Modifier.height(2.dp))
            DataRow("OFS", if (active && r != null) "${u.altSigned(r.altM - rel, 1)} ${u.alt}" else "--", Tac.Text, Modifier.width(112.dp))
            Spacer(Modifier.height(5.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SmallButton("ZERO", Tac.Violet) { vm.setRelative(0.0); haptics.confirm() }
                SmallButton("SET", Tac.Amber) { onNav("edit/rel") }
                if (active) SmallButton("CLR", Tac.Dim) { vm.clearRelative(); haptics.tick() }
            }
        }
    }
}

private val WINDOWS = listOf(3, 6, 12, 24, 48)

@Composable
private fun TrendPage(state: UiState) {
    val u = state.units
    var hours by rememberSaveable { mutableIntStateOf(6) }
    val cycle = { hours = WINDOWS[(WINDOWS.indexOf(hours) + 1) % WINDOWS.size] }
    val now = System.currentTimeMillis()
    val from = now - hours * 3_600_000L
    val points = state.log.filter { it.t >= from }.map { it.t to it.hpa } +
        listOfNotNull(state.reading?.let { now to it.stationHpa })
    Column(
        Modifier.fillMaxSize().padding(top = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("STN TREND·${hours}H ⟳", style = Tac.mono(10.sp, Tac.Cyan, bold = true), modifier = Modifier.clickable(onClick = cycle))
        Spacer(Modifier.height(4.dp))
        TrendGraph(points, from, now, u, Modifier.width(150.dp).height(76.dp).clickable(onClick = cycle))
        Spacer(Modifier.height(5.dp))
        val t = state.trend
        if (t != null) {
            Text("${t.tendency.arrow} ${t.tendency.label}", style = Tac.mono(11.sp, tendencyColor(t.tendency), bold = true))
            Text("3H Δ ${u.pressSigned(t.delta3h)} ${u.press}", style = Tac.mono(10.sp, Tac.Text))
        } else {
            Text("NEED 3H OF DATA", style = Tac.mono(10.sp, Tac.Dim))
        }
        Text(
            "${state.log.size} PTS · LOG ${if (state.cfg.logMinutes == 0) "OFF" else "${state.cfg.logMinutes}m"}",
            style = Tac.mono(9.sp, Tac.Dim),
        )
    }
}

@Composable
private fun TrendGraph(points: List<Pair<Long, Double>>, from: Long, to: Long, u: Units, modifier: Modifier) {
    val paint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; color = Tac.Dim.toArgb() }
    }
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        // Graticule.
        for (i in 0..6) {
            val x = w * i / 6
            drawLine(Tac.Grid, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
        }
        for (i in 0..4) {
            val y = h * i / 4
            drawLine(Tac.Grid, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }
        // Fine marks along the bottom edge.
        for (i in 0..30) {
            val x = w * i / 30
            drawLine(Tac.Tick, Offset(x, h), Offset(x, h - if (i % 5 == 0) 5.dp.toPx() else 2.dp.toPx()), strokeWidth = 1f)
        }
        paint.textSize = 8.dp.toPx()
        if (points.size < 2) {
            paint.textAlign = Paint.Align.CENTER
            drawContext.canvas.nativeCanvas.drawText("NO DATA YET", w / 2, h / 2, paint)
            return@Canvas
        }
        var lo = points.minOf { it.second }
        var hi = points.maxOf { it.second }
        if (hi - lo < 2.0) {
            val mid = (hi + lo) / 2
            lo = mid - 1.0; hi = mid + 1.0
        }
        fun px(t: Long) = ((t - from).toFloat() / (to - from)) * w
        fun py(p: Double) = (h - ((p - lo) / (hi - lo)) * h).toFloat()
        val path = Path()
        points.forEachIndexed { i, (t, p) -> if (i == 0) path.moveTo(px(t), py(p)) else path.lineTo(px(t), py(p)) }
        drawPath(path, Tac.Cyan, style = Stroke(1.5f.dp.toPx()))
        val (lt, lp) = points.last()
        drawCircle(Tac.Amber, 2.5f.dp.toPx(), Offset(px(lt), py(lp)))
        paint.textAlign = Paint.Align.LEFT
        drawContext.canvas.nativeCanvas.drawText(u.pressStr(hi), 2f, paint.textSize, paint)
        drawContext.canvas.nativeCanvas.drawText(u.pressStr(lo), 2f, h - 7.dp.toPx(), paint)
    }
}

@Composable
private fun SetupPage(state: UiState, onNav: (String) -> Unit) {
    val cfg = state.cfg
    val u = state.units
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Header("SETUP")
        Spacer(Modifier.height(4.dp))
        DataRow("CAL", "${cfg.source.tag} ${Units.age(cfg.calTime)}", Tac.Amber)
        DataRow("QNH", "${u.pressStr(cfg.qnh)} ${u.press}")
        DataRow("ACC", if (cfg.calAccM.isNaN()) "--" else "±${u.altStr(cfg.calAccM)} ${u.alt}")
        Spacer(Modifier.height(6.dp))
        TacButton("CALIBRATE", Tac.Amber, Modifier.fillMaxWidth()) { onNav("calibrate") }
        Spacer(Modifier.height(4.dp))
        TacButton("SETTINGS", Tac.Cyan, Modifier.fillMaxWidth()) { onNav("settings") }
    }
}

@Composable
fun Placeholder(text: String) {
    Box(Modifier.fillMaxSize().background(Tac.Bg), contentAlignment = Alignment.Center) {
        Text(text, style = Tac.mono(11.sp, Tac.Dim), textAlign = TextAlign.Center, modifier = Modifier.size(140.dp))
    }
}
