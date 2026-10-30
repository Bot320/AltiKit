package app.altikit.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlin.math.abs

/**
 * Routes digital-crown rotation to [onDelta] (pixels, positive = clockwise).
 * Re-grabs focus every time the screen resumes so the crown works after navigating back.
 */
@Composable
fun Modifier.crown(onDelta: (px: Float, uptimeMs: Long) -> Unit): Modifier {
    val fr = remember { FocusRequester() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { runCatching { fr.requestFocus() } }
    }
    return this
        .onRotaryScrollEvent { onDelta(it.verticalScrollPixels, it.uptimeMillis); true }
        .focusRequester(fr)
        .focusable()
}

/** Converts crown pixels into discrete steps, with acceleration for fast spins. */
class CrownStepper(private val pxPerStep: Float = 28f) {
    private var acc = 0f
    private var lastT = 0L

    /** Returns signed step count scaled by acceleration (1x, 10x). */
    fun feed(px: Float, t: Long): Int {
        val dt = (t - lastT).coerceAtLeast(1)
        lastT = t
        if (acc != 0f && (acc > 0) != (px > 0)) acc = 0f
        acc += px
        val steps = (acc / pxPerStep).toInt()
        if (steps == 0) return 0
        acc -= steps * pxPerStep
        val fast = abs(px) / dt > 2.5f
        return if (fast) steps * 10 else steps
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics { c -> view.performHapticFeedback(c) } }
}

class Haptics(private val perform: (Int) -> Unit) {
    fun tick() = perform(HapticFeedbackConstants.CLOCK_TICK)
    fun confirm() = perform(HapticFeedbackConstants.CONFIRM)
    fun heavy() = perform(HapticFeedbackConstants.LONG_PRESS)
}
