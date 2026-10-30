package app.altikit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text

private val CutShape = CutCornerShape(5.dp)

@Composable
fun TacButton(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    sub: String? = null,
    value: String? = null,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .border(1.dp, color.copy(alpha = 0.75f), CutShape)
            .background(color.copy(alpha = 0.07f), CutShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f, fill = false)) {
                Text(label, style = Tac.mono(12.sp, color, bold = true))
                if (sub != null) Text(sub, style = Tac.mono(9.sp, Tac.Dim))
            }
            if (value != null) {
                Spacer(Modifier.width(6.dp))
                Text(value, style = Tac.mono(12.sp, Tac.Text, bold = true), textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun SmallButton(label: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .border(1.dp, color.copy(alpha = 0.8f), CutShape)
            .background(color.copy(alpha = 0.08f), CutShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Tac.mono(11.sp, color, bold = true))
    }
}

/** "KEY ······ VALUE" row. */
@Composable
fun DataRow(key: String, value: String, valueColor: Color = Tac.Text, modifier: Modifier = Modifier.fillMaxWidth()) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(key, style = Tac.mono(10.sp, Tac.Dim))
        Spacer(Modifier.weight(1f))
        Text(value, style = Tac.mono(12.sp, valueColor, bold = true))
    }
}

@Composable
fun Header(text: String, color: Color = Tac.Amber, sub: String? = null) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("[ $text ]", style = Tac.mono(12.sp, color, bold = true))
        if (sub != null) Text(sub, style = Tac.mono(9.sp, Tac.Dim), textAlign = TextAlign.Center)
    }
}

/** Big numeric readout that shrinks for long strings. */
@Composable
fun BigValue(text: String, color: Color) {
    val size = when {
        text.length <= 5 -> 38.sp
        text.length <= 7 -> 32.sp
        else -> 26.sp
    }
    Text(text, style = Tac.mono(size, color, bold = true), maxLines = 1)
}
