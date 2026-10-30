package app.altikit.tile

import androidx.concurrent.futures.SuspendToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.degrees
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Arc
import androidx.wear.protolayout.LayoutElementBuilders.ArcLine
import androidx.wear.protolayout.LayoutElementBuilders.Box
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.FontStyle
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.LayoutElementBuilders.Text
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import app.altikit.core.Mode
import app.altikit.core.Snapshot
import app.altikit.core.Tendency
import app.altikit.core.Units
import app.altikit.ui.MainActivity
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers

private const val RES_VERSION = "1"
private const val AMBER = 0xFFFFB300.toInt()
private const val CYAN = 0xFF2EE6FF.toInt()
private const val TEXT = 0xFFE4ECE9.toInt()
private const val DIM = 0xFF7A8C88.toInt()
private const val GRID = 0xFF1C2826.toInt()
private const val GREEN = 0xFF7CFF5E.toInt()
private const val RED = 0xFFFF4D4D.toInt()

class AltTileService : TileService() {

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        SuspendToFutureAdapter.launchFuture(Dispatchers.Default) {
            val snap = Snapshot.take(applicationContext)
            TileBuilders.Tile.Builder()
                .setResourcesVersion(RES_VERSION)
                .setFreshnessIntervalMillis(10 * 60_000L)
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout(snap)))
                .build()
        }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        SuspendToFutureAdapter.launchFuture(Dispatchers.Default) {
            ResourceBuilders.Resources.Builder().setVersion(RES_VERSION).build()
        }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false): LayoutElement =
        Text.Builder()
            .setText(s)
            .setFontStyle(
                FontStyle.Builder()
                    .setSize(sp(size))
                    .setColor(argb(color))
                    .setWeight(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL)
                    .build()
            )
            .build()

    private fun gap(h: Float): LayoutElement = Spacer.Builder().setHeight(dp(h)).build()

    private fun layout(snap: Snapshot?): LayoutElement {
        val open = ModifiersBuilders.Modifiers.Builder()
            .setClickable(
                ModifiersBuilders.Clickable.Builder()
                    .setId("open")
                    .setOnClick(
                        ActionBuilders.LaunchAction.Builder()
                            .setAndroidActivity(
                                ActionBuilders.AndroidActivity.Builder()
                                    .setPackageName(packageName)
                                    .setClassName(MainActivity::class.java.name)
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()

        val root = Box.Builder().setWidth(expand()).setHeight(expand()).setModifiers(open)
        if (snap == null) {
            return root.addContent(text("NO BAROMETER", 14f, RED, bold = true)).build()
        }
        val u = Units(snap.config.imperial)
        val r = snap.reading
        // QNH gauge ring 950–1050 hPa over 270°.
        val frac = ((r.qnhHpa - 950) / 100).coerceIn(0.0, 1.0).toFloat()
        root.addContent(
            Arc.Builder()
                .setAnchorAngle(degrees(-135f))
                .setAnchorType(LayoutElementBuilders.ARC_ANCHOR_START)
                .addContent(ArcLine.Builder().setLength(degrees(270f)).setThickness(dp(3f)).setColor(argb(GRID)).build())
                .build()
        )
        root.addContent(
            Arc.Builder()
                .setAnchorAngle(degrees(-135f))
                .setAnchorType(LayoutElementBuilders.ARC_ANCHOR_START)
                .addContent(ArcLine.Builder().setLength(degrees(270f * frac)).setThickness(dp(3f)).setColor(argb(AMBER)).build())
                .build()
        )
        val trend = snap.trend
        val trendColor = when (trend?.tendency) {
            Tendency.RISING, Tendency.RISING_FAST -> GREEN
            Tendency.FALLING -> AMBER
            Tendency.STORM -> RED
            else -> TEXT
        }
        val col = Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(text("[ ALT·${snap.config.source.tag} ]", 10f, DIM))
            .addContent(text("${u.altStr(r.altM)} ${u.alt}", 30f, AMBER, bold = true))
            .addContent(text("STD ${u.altStr(r.stdAltM)}${if (snap.config.mode == Mode.BAROMETER) " · LOCK" else ""}", 11f, CYAN))
            .addContent(gap(6f))
            .addContent(text("QNH ${u.pressStr(r.qnhHpa)}", 14f, TEXT, bold = true))
            .addContent(text("RAW ${u.pressStr(r.stationHpa)} ${u.press}", 11f, DIM))
            .addContent(gap(4f))
            .addContent(
                text(
                    trend?.let { "${it.tendency.arrow} ${u.pressSigned(it.delta3h)}/3h" } ?: "TREND --",
                    11f, trendColor, bold = true,
                )
            )
            .addContent(text("CAL ${Units.age(snap.config.calTime)}", 9f, DIM))
            .build()
        return root.addContent(col).build()
    }
}
