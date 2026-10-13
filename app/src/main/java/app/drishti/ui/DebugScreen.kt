package app.drishti.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.drishti.core.AppConfig
import app.drishti.core.CrowdingSpec
import app.drishti.core.OptotypeLayout
import app.drishti.core.OptotypeRender
import app.drishti.core.Orientation
import app.drishti.core.PxRect
import kotlin.math.roundToInt

private const val RULER_MM = 100
private const val RULER_PAD_PX = 24

/**
 * Physical verification: a 100 mm ruler drawn at the calibrated pxPerMm, then
 * one E per far line with intended vs. drawn size after pixel rounding.
 */
@Composable
fun DebugScreen(config: AppConfig, pxPerMm: Double, onBack: () -> Unit) {
    var crowded by remember { mutableStateOf(false) }
    val distanceMm = config.farDistanceMm
    val orientations = Orientation.entries

    LazyColumn(Modifier.fillMaxSize().background(Color.White).safeDrawingPadding()) {
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onBack) { Text("Back") }
                Text("Card calibration: %.3f px/mm".format(pxPerMm), color = Color.Black)
                Text(
                    "Hold a ruler against the line: 0 to 100 mm should match. " +
                        "100 mm = ${(RULER_MM * pxPerMm).roundToInt()} px.",
                    color = Color.Black,
                )
            }
        }
        item { Ruler(pxPerMm) }
        item {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "Letters for the far distance (${distanceMm.toInt()} mm), min stroke ${config.minStrokePx} px. " +
                        "Measure the E's height (top of the top limb to bottom of the bottom limb).",
                    color = Color.Black,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = crowded, onCheckedChange = { crowded = it })
                    Text("  Crowding bars (gap %.1f strokes)".format(config.crowding.barGapStrokes), color = Color.Black)
                }
            }
        }
        itemsIndexed(config.far.linesLogMAR) { i, lm ->
            // Preview uses the configured spacing even if crowding is disabled in config.
            val crowding = if (crowded) CrowdingSpec(config.crowding.barGapStrokes, config.crowding.barThicknessStrokes) else null
            val render = OptotypeLayout.layout(distanceMm, lm, pxPerMm, orientations[i % 4], config.minStrokePx, crowding)
            BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                val widthPx = constraints.maxWidth
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    when (render) {
                        is OptotypeRender.Drawable -> {
                            val pct = (render.actualHeightMm / render.intendedHeightMm - 1) * 100
                            Text(
                                "logMAR %.1f · intended %.3f mm · drawn %.3f mm · stroke %d px · error %+.2f%% (%+.3f logMAR)"
                                    .format(lm, render.intendedHeightMm, render.actualHeightMm, render.strokePx, pct, render.roundingErrorLogMAR),
                                color = Color.Black,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                            if (render.footprintPx > widthPx) {
                                Text(
                                    "Footprint %.1f mm is wider than the screen; clipped.".format(render.footprintPx / pxPerMm),
                                    color = Color.Red,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                            }
                            OptotypeCanvas(render)
                        }
                        is OptotypeRender.PixelLimited -> Text(
                            "logMAR %.1f · PIXEL-LIMITED: stroke %.2f px < %.1f px. Not drawn."
                                .format(lm, render.intendedStrokePx, render.minStrokePx),
                            color = Color.Red,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Vertical 100 mm ruler along the phone's long axis (100 mm is wider than most
 * phones in portrait). Ticks are 2 px tall, centred on round(i * pxPerMm).
 */
@Composable
private fun Ruler(pxPerMm: Double) {
    val totalPx = (RULER_MM * pxPerMm).roundToInt()
    val heightDp = with(LocalDensity.current) { (totalPx + 2 * RULER_PAD_PX).toDp() }
    Canvas(Modifier.fillMaxWidth().height(heightDp).clipToBounds()) {
        val x0 = (size.width * 0.3f).toInt()
        val rects = buildList {
            add(PxRect(x0 - 1, RULER_PAD_PX - 1, x0 + 1, RULER_PAD_PX + totalPx + 1)) // spine
            for (mm in 0..RULER_MM) {
                val y = RULER_PAD_PX + (mm * pxPerMm).roundToInt()
                val lenMm = when {
                    mm % 10 == 0 -> 8.0
                    mm % 5 == 0 -> 5.0
                    else -> 3.0
                }
                add(PxRect(x0, y - 1, x0 + (lenMm * pxPerMm).roundToInt(), y + 1))
            }
        }
        fillPxRects(rects, 0, 0, android.graphics.Color.BLACK)
        val text = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.BLACK
            textSize = (3.0 * pxPerMm).toFloat()
        }
        drawIntoCanvas { c ->
            for (mm in 0..RULER_MM step 10) {
                val y = RULER_PAD_PX + (mm * pxPerMm).roundToInt()
                c.nativeCanvas.drawText("$mm", x0 + (9.5 * pxPerMm).toFloat(), y + text.textSize / 3, text)
            }
        }
    }
}
