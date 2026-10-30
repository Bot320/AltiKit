package app.altikit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController

@Composable
fun AltiKitApp(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val nav = rememberSwipeDismissableNavController()
    val go: (String) -> Unit = { nav.navigate(it) }
    val back: () -> Unit = { nav.popBackStack() }
    TacTheme {
        SwipeDismissableNavHost(navController = nav, startDestination = "main") {
            composable("main") { MainPager(state, vm, go) }
            composable("calibrate") { CalibrateScreen(state, vm, go, back) }
            composable("settings") { SettingsScreen(state, vm) }
            composable("run/gps") { CalRunScreen(gps = true, vm = vm, onDone = back) }
            composable("run/web") { CalRunScreen(gps = false, vm = vm, onDone = back) }
            composable("edit/alt") {
                val u = state.units
                val initial = remember { u.altTo(state.reading?.altM ?: 0.0).let { kotlin.math.round(it) } }
                ValueEditor(
                    title = "KNOWN ALT", unit = u.alt, initial = initial,
                    steps = listOf(1.0, 10.0, 100.0), defaultStep = 1, decimals = 0,
                    min = u.altTo(-500.0), max = u.altTo(9_000.0), accent = Tac.Amber,
                ) { vm.calibrateAltitude(u.altFrom(it)); back() }
            }
            composable("edit/qnh") {
                val u = state.units
                val initial = remember { u.pressTo(state.cfg.qnh).let { if (u.imperial) kotlin.math.round(it * 100) / 100 else kotlin.math.round(it * 10) / 10 } }
                ValueEditor(
                    title = "QNH", unit = u.press, initial = initial,
                    steps = if (u.imperial) listOf(0.01, 0.1, 1.0) else listOf(0.1, 1.0, 10.0),
                    defaultStep = 1, decimals = if (u.imperial) 2 else 1,
                    min = u.pressTo(870.0), max = u.pressTo(1085.0), accent = Tac.Amber,
                ) { vm.calibrateQnh(u.pressFrom(it)); back() }
            }
            composable("edit/rel") {
                val u = state.units
                ValueEditor(
                    title = "REL SET", unit = u.alt, initial = 0.0,
                    steps = listOf(1.0, 10.0, 100.0), defaultStep = 0, decimals = 0,
                    min = u.altTo(-9_999.0), max = u.altTo(9_999.0), accent = Tac.Violet,
                ) { vm.setRelative(u.altFrom(it)); back() }
            }
        }
    }
}
