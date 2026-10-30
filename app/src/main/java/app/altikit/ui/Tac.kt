package app.altikit.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme

/** Tactical palette: black field, grey graticule, a few saturated signal colours. */
object Tac {
    val Bg = Color(0xFF000000)
    val Panel = Color(0xFF0A1010)
    val Grid = Color(0xFF1C2826)
    val Tick = Color(0xFF465753)
    val TickMajor = Color(0xFF93A8A3)
    val Text = Color(0xFFE4ECE9)
    val Dim = Color(0xFF7A8C88)
    val Amber = Color(0xFFFFB300)
    val Cyan = Color(0xFF2EE6FF)
    val Green = Color(0xFF7CFF5E)
    val Red = Color(0xFFFF4D4D)
    val Violet = Color(0xFFC08CFF)

    val Mono = FontFamily.Monospace

    fun mono(size: TextUnit, color: Color = Text, bold: Boolean = false) =
        TextStyle(fontFamily = Mono, fontSize = size, color = color, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)

    fun label(color: Color = Dim) = mono(10.sp, color)
}

@Composable
fun TacTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = Colors(
            primary = Tac.Amber,
            secondary = Tac.Cyan,
            background = Tac.Bg,
            surface = Tac.Panel,
            onPrimary = Tac.Bg,
            onSecondary = Tac.Bg,
            onBackground = Tac.Text,
            onSurface = Tac.Text,
            error = Tac.Red,
        ),
        content = content,
    )
}
