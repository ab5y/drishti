package app.drishti.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import app.drishti.core.CardCalibration
import app.drishti.core.PxRect
import app.drishti.core.ScreenCalibration
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

private const val MIN_EDGE_PX = 100
private const val FALLBACK_SEED_FRACTION = 0.6
private val FILL_ARGB = android.graphics.Color.rgb(255, 200, 0)

/**
 * Card calibration (build sheet section 02). The rectangle's long edge runs
 * along the phone's long axis, because 85.6 mm is wider than most phones in
 * portrait. Drag anywhere above the rectangle's middle to move its top edge,
 * below to move its bottom edge; the +/- buttons nudge the bottom edge 1 px.
 */
@Composable
fun CalibrationScreen(
    xdpi: Double,
    saved: ScreenCalibration?,
    onSave: (matchedEdgePx: Int) -> Unit,
    onCancel: () -> Unit,
) {
    var areaH by remember { mutableIntStateOf(0) }
    var top by remember { mutableIntStateOf(-1) }
    var bottom by remember { mutableIntStateOf(-1) }
    // True when a card at the phone's reported density would not fit above the controls.
    var seedClamped by remember { mutableStateOf(false) }

    fun seed() {
        if (areaH <= 0) return
        val maxEdge = areaH - 2
        val wanted = CardCalibration.seedEdgePx(xdpi) ?: (areaH * FALLBACK_SEED_FRACTION).roundToInt()
        seedClamped = wanted > maxEdge
        val edge = wanted.coerceIn(MIN_EDGE_PX.coerceAtMost(maxEdge), maxEdge)
        top = (areaH - edge) / 2
        bottom = top + edge
    }

    Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val h = constraints.maxHeight
            val w = constraints.maxWidth
            LaunchedEffect(h) {
                areaH = h
                if (top < 0 || bottom > h) seed()
            }
            Canvas(
                Modifier.fillMaxSize().pointerInput(h) {
                    var moveTop = false
                    var acc = 0f
                    detectDragGestures(
                        onDragStart = { o ->
                            moveTop = o.y < (top + bottom) / 2f
                            acc = 0f
                        },
                        onDrag = { change, delta ->
                            change.consume()
                            acc += delta.y
                            val step = acc.toInt()
                            if (step != 0) {
                                acc -= step
                                if (moveTop) top = (top + step).coerceIn(0, bottom - MIN_EDGE_PX)
                                else bottom = (bottom + step).coerceIn(top + MIN_EDGE_PX, h)
                            }
                        },
                    )
                },
            ) {
                if (top < 0) return@Canvas
                val edge = bottom - top
                val cardW = (edge * CardCalibration.ID1_SHORT_EDGE_MM / CardCalibration.ID1_LONG_EDGE_MM)
                    .roundToInt().coerceAtMost(w)
                val left = (w - cardW) / 2
                fillPxRects(listOf(PxRect(left, top, left + cardW, bottom)), 0, 0, FILL_ARGB)
            }
        }

        val edge = (bottom - top).takeIf { top >= 0 }
        Column(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "Hold an ID-1 card (bank, PAN or Aadhaar card) flat on the glass, long edge along the phone, " +
                    "over the yellow box. Drag above the card to move the top edge and below it to move the bottom " +
                    "edge, until no yellow shows past either end of the card.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                saved?.let {
                    "Saved: %.3f px/mm (%s)".format(
                        it.pxPerMm, DateFormat.getDateTimeInstance().format(Date(it.calibratedAtEpochMs)),
                    )
                } ?: "Saved: none, this device is not calibrated",
                style = MaterialTheme.typography.bodySmall,
            )
            if (edge != null) {
                val px = CardCalibration.pxPerMm(edge.toDouble())
                Text("Card edge $edge px → %.3f px/mm".format(px), style = MaterialTheme.typography.titleMedium)
                val check = CardCalibration.check(px, xdpi)
                val ref = check.xdpiPxPerMm
                if (ref == null || check.deviation == null) {
                    Text("Phone reports no usable xdpi; cannot cross-check.", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(
                        "Phone's reported xdpi %.1f → %.3f px/mm (card differs by %+.1f%%)"
                            .format(xdpi, ref, check.deviation * 100),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (check.warn) {
                    Text(
                        ("More than %.0f%% from the phone's reported density. Check the card is flat and both ends " +
                            "line up. You can still save; the card value is what will be used.")
                            .format(CardCalibration.XDPI_WARN_FRACTION * 100),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (seedClamped) {
                Text(
                    "The space above these controls may be shorter than a card on this phone. " +
                        "If the card's ends cannot both be matched, this phone cannot be calibrated this way.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { if (bottom - 1 - top >= MIN_EDGE_PX) bottom -= 1 }) { Text("−1 px") }
                OutlinedButton(onClick = { if (bottom + 1 <= areaH) bottom += 1 }) { Text("+1 px") }
                OutlinedButton(onClick = { seed() }) { Text("Reset") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
                Button(onClick = { edge?.let(onSave) }, enabled = edge != null) { Text("Save") }
            }
        }
    }
}
