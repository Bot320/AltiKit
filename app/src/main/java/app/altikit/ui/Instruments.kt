package app.altikit.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.math.sin

private fun polar(c: Offset, r: Float, deg: Float): Offset {
    val a = Math.toRadians(deg.toDouble())
    return Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat())
}

@Composable
private fun rememberLabelPaint(color: Color): Paint = remember(color) {
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
        this.color = color.toArgb()
    }
}

private fun DrawScope.label(paint: Paint, text: String, at: Offset) {
    paint.textSize = 9.dp.toPx()
    drawContext.canvas.nativeCanvas.drawText(text, at.x, at.y + paint.textSize / 3, paint)
}

/**
 * Bezel dial that scrolls under a fixed index at 12 o'clock, like a rotating altimeter tape bent into a ring.
 * [span] is how many units fit in a full circle; ticks are only drawn on the upper 300° so the bottom stays free.
 */
@Composable
fun RotatingDial(
    value: Double,
    span: Double,
    minor: Double,
    majorEvery: Int,
    labelEvery: Int,
    accent: Color,
    modifier: Modifier = Modifier.fillMaxSize(),
    labelFmt: (Double) -> String = { it.roundToLong().toString() },
) {
    val paint = rememberLabelPaint(Tac.Dim)
    Canvas(modifier) {
        val c = center
        val r = size.minDimension / 2 - 2.dp.toPx()
        val visible = span * 150 / 360
        if (!value.isNaN()) {
            val k0 = ceil((value - visible) / minor).toLong()
            val k1 = floor((value + visible) / minor).toLong()
            for (k in k0..k1) {
                val v = k * minor
                val deg = ((v - value) / span * 360 - 90).toFloat()
                val major = k % majorEvery == 0L
                val lab = k % (majorEvery.toLong() * labelEvery) == 0L
                val len = when {
                    lab -> 11.dp.toPx()
                    major -> 8.dp.toPx()
                    else -> 4.dp.toPx()
                }
                // Fade ticks out toward the bottom gap.
                val fade = (1 - abs(deg + 90) / 160f).coerceIn(0.25f, 1f)
                val col = (if (major) Tac.TickMajor else Tac.Tick).copy(alpha = fade)
                drawLine(col, polar(c, r, deg), polar(c, r - len, deg), strokeWidth = if (major) 1.5f.dp.toPx() else 1.dp.toPx())
                if (lab) {
                    paint.alpha = (fade * 255).toInt()
                    label(paint, labelFmt(v), polar(c, r - 19.dp.toPx(), deg))
                }
            }
        }
        // Inner hairline ring.
        drawArc(Tac.Grid, startAngle = -240f, sweepAngle = 300f, useCenter = false,
            topLeft = Offset(c.x - r + 13.dp.toPx(), c.y - r + 13.dp.toPx()),
            size = Size((r - 13.dp.toPx()) * 2, (r - 13.dp.toPx()) * 2), style = Stroke(1f))
        indexMark(c, r, -90f, accent)
    }
}

private fun DrawScope.indexMark(c: Offset, r: Float, deg: Float, color: Color) {
    val tip = polar(c, r - 13.dp.toPx(), deg)
    val baseL = polar(c, r - 1.dp.toPx(), deg - 3.5f)
    val baseR = polar(c, r - 1.dp.toPx(), deg + 3.5f)
    drawPath(Path().apply { moveTo(tip.x, tip.y); lineTo(baseL.x, baseL.y); lineTo(baseR.x, baseR.y); close() }, color)
}

/** Fixed 270° gauge with a moving pointer. */
@Composable
fun ArcGauge(
    value: Double,
    min: Double,
    max: Double,
    minor: Double,
    majorEvery: Int,
    labelEvery: Int,
    accent: Color,
    modifier: Modifier = Modifier.fillMaxSize(),
    ghost: Double = Double.NaN,
    labelFmt: (Double) -> String = { it.roundToLong().toString() },
) {
    val paint = rememberLabelPaint(Tac.Dim)
    Canvas(modifier) {
        val c = center
        val r = size.minDimension / 2 - 2.dp.toPx()
        val start = 135f
        val sweep = 270f
        fun ang(v: Double) = start + ((v - min) / (max - min) * sweep).toFloat().coerceIn(0f, sweep)
        val n = ((max - min) / minor).roundToLong()
        for (i in 0..n) {
            val v = min + i * minor
            val major = i % majorEvery == 0L
            val lab = i % (majorEvery.toLong() * labelEvery) == 0L
            val len = if (lab) 11.dp.toPx() else if (major) 8.dp.toPx() else 4.dp.toPx()
            val deg = ang(v)
            drawLine(if (major) Tac.TickMajor else Tac.Tick, polar(c, r, deg), polar(c, r - len, deg),
                strokeWidth = if (major) 1.5f.dp.toPx() else 1.dp.toPx())
            if (lab) label(paint, labelFmt(v), polar(c, r - 19.dp.toPx(), deg))
        }
        if (!value.isNaN()) {
            val inset = 13.dp.toPx()
            drawArc(accent.copy(alpha = 0.35f), start, ang(value) - start, false,
                topLeft = Offset(c.x - r + inset, c.y - r + inset), size = Size((r - inset) * 2, (r - inset) * 2),
                style = Stroke(2.dp.toPx(), cap = StrokeCap.Butt))
            if (!ghost.isNaN()) {
                val g = ang(ghost)
                drawLine(Tac.Dim, polar(c, r + 1, g), polar(c, r - 13.dp.toPx(), g), strokeWidth = 2.dp.toPx())
            }
            indexMark(c, r, ang(value), accent)
        }
    }
}

/** Vertical-speed bar on the right edge: ±[full] m/min over ±40°. */
@Composable
fun VsArc(vsMpm: Double, full: Double = 20.0, modifier: Modifier = Modifier.fillMaxSize()) {
    Canvas(modifier) {
        val c = center
        val r = size.minDimension / 2 - 24.dp.toPx()
        // Scale: centre at 3 o'clock (0°), up = negative angle.
        for (i in -4..4) {
            val deg = i * 10f
            val len = if (i == 0) 7.dp.toPx() else if (i % 2 == 0) 5.dp.toPx() else 3.dp.toPx()
            drawLine(Tac.Tick, polar(c, r, deg), polar(c, r - len, deg), strokeWidth = 1.dp.toPx())
        }
        if (vsMpm.isNaN()) return@Canvas
        val frac = (vsMpm / full).coerceIn(-1.0, 1.0).toFloat()
        val col = when {
            abs(vsMpm) < 0.5 -> Tac.Dim
            vsMpm > 0 -> Tac.Green
            else -> Tac.Red
        }
        val inset = r + 3.dp.toPx()
        drawArc(col, 0f, -frac * 40f, false,
            topLeft = Offset(c.x - inset, c.y - inset), size = Size(inset * 2, inset * 2),
            style = Stroke(3.dp.toPx(), cap = StrokeCap.Butt))
        val tip = polar(c, inset, -frac * 40f)
        drawCircle(col, 2.5f.dp.toPx(), tip)
    }
}

/** Fine horizontal ruler used as a separator; [offset] (0..1) slides the ticks for a tape effect. */
@Composable
fun Ruler(modifier: Modifier, color: Color = Tac.Tick, offset: Float = 0f) {
    Canvas(modifier) {
        val step = 4.dp.toPx()
        val y = size.height / 2
        drawLine(Tac.Grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        val shift = (offset - floor(offset)) * step * 5
        var i = -5
        while (true) {
            val x = i * step + shift
            if (x > size.width) break
            if (x >= 0) {
                val idx = Math.floorMod(i, 5)
                val h = if (idx == 0) size.height / 2 else size.height / 4
                drawLine(color, Offset(x, y - h), Offset(x, y + h), strokeWidth = 1f)
            }
            i++
        }
        // Centre index.
        drawLine(Tac.Amber, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), strokeWidth = 1.5f)
    }
}

/** Corner brackets framing a readout. */
fun DrawScope.brackets(color: Color, len: Float = 8.dp.toPx()) {
    val w = size.width
    val h = size.height
    val s = 1.dp.toPx()
    listOf(
        Offset(0f, 0f) to Offset(1f, 1f), Offset(w, 0f) to Offset(-1f, 1f),
        Offset(0f, h) to Offset(1f, -1f), Offset(w, h) to Offset(-1f, -1f),
    ).forEach { (p, d) ->
        drawLine(color, p, Offset(p.x + d.x * len, p.y), strokeWidth = s)
        drawLine(color, p, Offset(p.x, p.y + d.y * len), strokeWidth = s)
    }
}

/** Progress ring with fine ticks, for calibration runs. */
@Composable
fun TickRing(progress: Float, color: Color, modifier: Modifier = Modifier.fillMaxSize()) {
    Canvas(modifier) {
        val c = center
        val r = size.minDimension / 2 - 3.dp.toPx()
        val n = 60
        for (i in 0 until n) {
            val deg = i * 6f - 90f
            val on = i < progress * n
            val len = if (i % 5 == 0) 8.dp.toPx() else 4.dp.toPx()
            drawLine(if (on) color else Tac.Grid, polar(c, r, deg), polar(c, r - len, deg),
                strokeWidth = if (i % 5 == 0) 2.dp.toPx() else 1.dp.toPx())
        }
    }
}
