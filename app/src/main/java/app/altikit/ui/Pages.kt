package app.altikit.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.Text
import androidx.wear.compose.material3.VerticalPageIndicator
import app.altikit.core.AutoState
import app.altikit.core.CalSource
import app.altikit.core.Change
import app.altikit.core.Config
import app.altikit.core.Mode
import app.altikit.core.Tendency
import app.altikit.core.Units
import app.altikit.core.forecast.Features
import app.altikit.core.forecast.Forecaster
import app.altikit.core.forecast.WxClass
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private const val PAGES = 6
private const val INDICATOR_HIDE_MS = 1_500L

private val ALT_SCALES_M = listOf(1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0)
private val ALT_SCALES_FT = listOf(2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0)
private val PRESS_SCALES_HPA = listOf(0.1, 0.2, 0.5, 1.0, 2.0, 5.0)
private val PRESS_SCALES_INHG = listOf(0.005, 0.01, 0.02, 0.05, 0.1, 0.2)

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
                    4 -> ForecastPage(state, vm, onNav)
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
        BottomClock(Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp))
        if (!state.sensorOk) {
            Text("NO BAROMETER", style = Tac.mono(10.sp, Tac.Red, bold = true), modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 30.dp))
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
    Text(fmt.format(java.util.Date(now)), style = Tac.mono(15.sp, Tac.Text, bold = true), modifier = modifier)
}

/** Centre column; tapping it (no ripple) runs [onTap]. */
@Composable
private fun BoxScope.CenterStack(onTap: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val tap = onTap?.let {
        Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = it)
    } ?: Modifier
    Column(
        Modifier.align(Alignment.Center).width(128.dp).then(tap),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

@Composable
private fun Readout(top: AnnotatedString, value: String, valueColor: Color, unit: AnnotatedString) {
    Text(top, style = Tac.label())
    Box(Modifier.drawBehind { brackets(Tac.Tick, 6.dp.toPx()) }.padding(horizontal = 8.dp)) {
        BigValue(value, valueColor)
    }
    Text(unit, style = Tac.mono(10.sp, Tac.Dim))
}

private fun windowLabel(minutes: Long) = if (minutes < 60) "${minutes}M" else "${minutes / 60}H"

/** "m · 1H ▲34": unit, window and the change over it, coloured by direction. */
private fun unitLine(
    unit: String, windowMin: Int, change: Change?, toDisplay: (Double) -> Double, decimals: Int,
    deadband: Double, up: Color, down: Color,
): AnnotatedString = buildAnnotatedString {
    append(unit)
    append(" · ")
    if (change == null) {
        append("${windowLabel(windowMin.toLong())} --")
        return@buildAnnotatedString
    }
    append(windowLabel(change.spanMs / 60_000) + " ")
    val d = toDisplay(change.delta)
    val (arrow, col) = when {
        abs(d) < deadband -> "►" to Tac.Text
        d > 0 -> "▲" to up
        else -> "▼" to down
    }
    withStyle(SpanStyle(color = col)) { append(arrow + Units.fmt(abs(d), decimals)) }
}

/** Top label with the mode: "ALT·GPS·WX", "LOCK·GPS·WX" or "AUTO·STILL·GPS·WX". */
private fun modeLabel(prefix: String, cfg: Config, auto: AutoState?): AnnotatedString = buildAnnotatedString {
    when (cfg.mode) {
        Mode.ALTIMETER -> append("$prefix·")
        Mode.BAROMETER -> {
            withStyle(SpanStyle(color = Tac.Violet)) { append("LOCK") }
            append("·")
        }
        Mode.AUTO -> {
            append("AUTO·")
            val moving = (auto?.moving ?: 0f) >= 0.5f
            withStyle(SpanStyle(color = if (moving) Tac.Amber else Tac.Cyan)) { append(if (moving) "MOVE" else "STILL") }
            append("·")
        }
    }
    append(cfg.source.tag)
}

@Composable
private fun AltitudePage(state: UiState) {
    val r = state.reading
    val u = state.units
    val cfg = state.cfg
    val haptics = rememberHaptics()
    var win by rememberSaveable { mutableIntStateOf(1) }
    val window = ALT_WINDOWS[win]
    val change = state.altChange[window]
    val disp = r?.let { u.altTo(it.altM) } ?: Double.NaN
    Box(Modifier.fillMaxSize()) {
        if (u.imperial) RotatingDial(disp, span = 300.0, minor = 10.0, majorEvery = 5, labelEvery = 2, accent = Tac.Amber)
        else RotatingDial(disp, span = 100.0, minor = 2.0, majorEvery = 5, labelEvery = 2, accent = Tac.Amber)
        VsArc(r?.vsMpm ?: Double.NaN)
        CenterStack(onTap = { win = (win + 1) % ALT_WINDOWS.size; haptics.tick() }) {
            Spacer(Modifier.height(6.dp))
            Readout(
                modeLabel("ALT", cfg, state.auto), r?.let { u.altStr(it.altM) } ?: "----", Tac.Amber,
                unitLine(u.alt, window, change, u::altTo, 0, u.altTo(1.0), Tac.Green, Tac.Red),
            )
            ChangeBar(
                Modifier.width(96.dp).height(10.dp).padding(vertical = 1.dp),
                delta = change?.let { u.altTo(it.delta) } ?: Double.NaN,
                scales = if (u.imperial) ALT_SCALES_FT else ALT_SCALES_M,
                deadband = u.altTo(1.0),
            )
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
    val haptics = rememberHaptics()
    var win by rememberSaveable { mutableIntStateOf(0) }
    val window = MSL_WINDOWS[win]
    val change = state.mslChange[window]
    val qnh = r?.qnhHpa ?: Double.NaN
    Box(Modifier.fillMaxSize()) {
        ArcGauge(
            value = qnh, min = 950.0, max = 1050.0, minor = 1.0, majorEvery = 10, labelEvery = 2,
            accent = Tac.Amber,
            ghost = if (trend != null && !qnh.isNaN()) qnh - trend.delta3h else Double.NaN,
            labelFmt = { if (u.imperial) Units.fmt(u.pressTo(it), 1) else it.roundToLong().toString() },
        )
        CenterStack(onTap = { win = (win + 1) % MSL_WINDOWS.size; haptics.tick() }) {
            Spacer(Modifier.height(4.dp))
            Readout(
                AnnotatedString("QNH·CAL"), r?.let { u.pressStr(it.qnhHpa) } ?: "----", Tac.Amber,
                unitLine(u.press, window, change, u::pressTo, if (u.imperial) 2 else 1, u.pressTo(0.1), Tac.Green, Tac.Amber),
            )
            ChangeBar(
                Modifier.width(96.dp).height(10.dp).padding(vertical = 1.dp),
                delta = change?.let { u.pressTo(it.delta) } ?: Double.NaN,
                scales = if (u.imperial) PRESS_SCALES_INHG else PRESS_SCALES_HPA,
                deadband = u.pressTo(0.1),
                down = Tac.Amber,
            )
            Spacer(Modifier.height(3.dp))
            DataRow("RAW", r?.let { u.pressStr(it.stationHpa) } ?: "--", Tac.Cyan, Modifier.width(112.dp))
            if (trend != null) {
                DataRow("3H ${trend.tendency.arrow}", u.pressSigned(trend.delta3h), tendencyColor(trend.tendency), Modifier.width(112.dp))
            } else {
                DataRow("3H", "LOGGING", Tac.Dim, Modifier.width(112.dp))
            }
            when (state.cfg.mode) {
                Mode.BAROMETER -> Text("ALT LOCK ${u.altStr(state.cfg.fixedAlt)}${u.alt}", style = Tac.mono(9.sp, Tac.Violet))
                Mode.AUTO -> Text(
                    "AUTO ${if ((state.auto?.moving ?: 0f) >= 0.5f) "MOVE" else "STILL"} · ${r?.let { u.altStr(it.altM) } ?: "--"}${u.alt}",
                    style = Tac.mono(9.sp, Tac.Violet),
                )
                Mode.ALTIMETER -> Unit
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

fun wxColor(c: WxClass) = when (c) {
    WxClass.FAIR -> Tac.Green
    WxClass.CLOUDY -> Tac.Text
    WxClass.RAIN -> Tac.Cyan
    WxClass.STORM -> Tac.Red
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
            Text("REL·ΔALT", style = Tac.label(Tac.Violet))
            Box(Modifier.drawBehind { brackets(Tac.Tick, 6.dp.toPx()) }.padding(horizontal = 8.dp)) {
                BigValue(if (active) u.altSigned(rel, 1) else "--.-", Tac.Violet)
            }
            Text(u.alt, style = Tac.mono(10.sp, Tac.Dim))
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
    val haptics = rememberHaptics()
    var hours by rememberSaveable { mutableIntStateOf(6) }
    val now = System.currentTimeMillis()
    val from = now - hours * 3_600_000L
    // Sea-level weather pressure, so climbing a hill doesn't look like a front.
    val points = state.log.filter { it.t >= from && !it.msl.isNaN() }.map { it.t to it.msl } +
        listOfNotNull(state.reading?.let { now to it.mslHpa })
    // The whole page cycles the window, like the change bars on the other pages.
    Column(
        Modifier.fillMaxSize().padding(top = 26.dp).clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null,
        ) {
            hours = WINDOWS[(WINDOWS.indexOf(hours) + 1) % WINDOWS.size]
            haptics.tick()
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("MSL TREND·${hours}H ⟳", style = Tac.mono(11.sp, Tac.Cyan, bold = true))
        Spacer(Modifier.height(4.dp))
        TrendGraph(points, from, now, hours, u, Modifier.width(172.dp).height(116.dp))
        Spacer(Modifier.height(5.dp))
        val t = state.trend
        if (t != null) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = tendencyColor(t.tendency))) { append("${t.tendency.arrow} ${t.tendency.label}") }
                    append("  ${u.pressSigned(t.delta3h)}/3H")
                },
                style = Tac.mono(12.sp, Tac.Text, bold = true),
            )
        } else {
            Text("NEED 3H OF DATA", style = Tac.mono(12.sp, Tac.Dim, bold = true))
        }
        Text(
            "${state.log.size} PTS · LOG ${if (state.cfg.logMinutes == 0) "OFF" else "${state.cfg.logMinutes}m"}",
            style = Tac.mono(9.sp, Tac.Dim),
        )
    }
}

@Composable
private fun TrendGraph(points: List<Pair<Long, Double>>, from: Long, to: Long, hours: Int, u: Units, modifier: Modifier) {
    val paint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; color = Tac.Dim.toArgb() }
    }
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        // Graticule: one column per sixth of the window.
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
        val canvas = drawContext.canvas.nativeCanvas
        paint.textSize = 9.dp.toPx()
        // Time axis, inside the frame just above the bottom marks.
        val ty = h - 7.dp.toPx()
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText("-${hours}H", 3f, ty, paint)
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(if (hours % 2 == 0) "-${hours / 2}H" else "-${hours * 30}M", w / 2, ty, paint)
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText("NOW", w - 3f, ty, paint)
        if (points.size < 2) {
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("NO DATA YET", w / 2, h / 2, paint)
            return@Canvas
        }
        var lo = points.minOf { it.second }
        var hi = points.maxOf { it.second }
        if (hi - lo < 2.0) {
            val mid = (hi + lo) / 2
            lo = mid - 1.0; hi = mid + 1.0
        }
        // Headroom so the line clears the labels at the top and bottom.
        val pad = (hi - lo) * 0.18
        val top = hi + pad
        val bottom = lo - pad
        fun px(t: Long) = ((t - from).toFloat() / (to - from)) * w
        fun py(p: Double) = (h - ((p - bottom) / (top - bottom)) * h).toFloat()
        val path = Path()
        points.forEachIndexed { i, (t, p) -> if (i == 0) path.moveTo(px(t), py(p)) else path.lineTo(px(t), py(p)) }
        drawPath(path, Tac.Cyan, style = Stroke(2f.dp.toPx()))
        val (lt, lp) = points.last()
        drawCircle(Tac.Amber, 3f.dp.toPx(), Offset(px(lt), py(lp)))
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText(u.pressStr(hi), 3f, paint.textSize + 2f, paint)
        // Just above the lowest value, clear of the time axis.
        canvas.drawText(u.pressStr(lo), 3f, py(lo) - 3f, paint)
    }
}

/**
 * One-time forecast: runs when the page opens and when the log changes. The ring shows the AI's
 * probabilities as shares of the bezel, the centre the likeliest class and %. Tapping cycles the horizon.
 */
@Composable
private fun ForecastPage(state: UiState, vm: MainViewModel, onNav: (String) -> Unit) {
    val fc by vm.forecast.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    val cfg = state.cfg
    LaunchedEffect(state.log.lastOrNull()?.t, state.reading != null) { vm.runForecast() }
    val f = fc
    val hours = cfg.fcstHours
    val ai = f?.ai(hours)
    val next = Forecaster.HORIZONS.let { it[(it.indexOf(hours) + 1) % it.size] }
    Box(Modifier.fillMaxSize()) {
        ProbRing(ai?.probs, WxClass.entries.map(::wxColor))
        CenterStack(onTap = { vm.setFcstHours(next); haptics.tick() }) {
            if (f == null || f.empty) {
                Text("FORECAST·12H", style = Tac.label(Tac.Cyan))
                Spacer(Modifier.height(10.dp))
                val msg = when {
                    f == null -> "…"
                    cfg.logMinutes == 0 -> "TURN ON\nTREND LOG"
                    else -> "NEED ${Features.MIN_HOURS.toInt()}H OF LOG\n${hoursText(f.hoursOfData)} SO FAR"
                }
                Text(msg, style = Tac.mono(13.sp, Tac.Text, bold = true), textAlign = TextAlign.Center)
                if (f != null && cfg.logMinutes == 0) {
                    Spacer(Modifier.height(8.dp))
                    SmallButton("SETTINGS", Tac.Cyan) { onNav("settings") }
                }
                return@CenterStack
            }
            if (ai != null) {
                val color = wxColor(ai.cls)
                Text("AI·NEXT ${hours}H ⟳", style = Tac.label())
                Text(ai.cls.label, style = Tac.mono(15.sp, color, bold = true))
                Box(Modifier.drawBehind { brackets(Tac.Tick, 6.dp.toPx()) }.padding(horizontal = 10.dp)) {
                    BigValue("${((ai.confidence ?: 0.0) * 100).roundToInt()}%", color)
                }
                Spacer(Modifier.height(4.dp))
                ai.probs?.let { ProbRow(it, ai.cls) }
            }
            f.zambretti?.zambretti?.let { z ->
                Spacer(Modifier.height(6.dp))
                Text(
                    buildAnnotatedString {
                        append("ZAMB ")
                        withStyle(SpanStyle(color = wxColor(z.cls), fontWeight = FontWeight.Bold)) { append("${z.letter} ${z.cls.label}") }
                    },
                    style = Tac.mono(11.sp, Tac.Dim),
                )
                Text(z.text, style = Tac.mono(10.sp, Tac.Text), maxLines = 1)
            }
            Spacer(Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The AI also uses the absolute pressure, which is only right after calibrating.
                val uncal = cfg.source == CalSource.STD
                Text(
                    if (uncal) "UNCAL" else hoursText(f.hoursOfData),
                    style = Tac.mono(9.sp, if (uncal) Tac.Amber else Tac.Dim),
                )
                Spacer(Modifier.width(6.dp))
                SmallButton("HIST", Tac.Cyan) { onNav("forecast/history") }
            }
        }
    }
}

private fun hoursText(h: Double) = if (h < 1) "${(h * 60).toInt()}M" else "${h.toInt()}H${((h % 1) * 60).toInt().toString().padStart(2, '0')}"

/** All four probabilities as a row of labelled numbers; the likeliest is bold. */
@Composable
private fun ProbRow(p: DoubleArray, top: WxClass) {
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        WxClass.entries.forEach { c ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(c.short, style = Tac.mono(8.sp, Tac.Dim))
                Text("${(p[c.ordinal] * 100).roundToInt()}", style = Tac.mono(13.sp, wxColor(c), bold = c == top))
            }
        }
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
        DataRow("QNH", "${u.pressStr(state.reading?.qnhHpa ?: cfg.qnh)} ${u.press}")
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
