package app.drishti.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import app.drishti.core.OptotypeRender
import app.drishti.core.PxRect

/**
 * Fills whole-pixel rectangles with anti-aliasing off, so edges land exactly on
 * pixel boundaries and limbs and gaps stay equal. Canvas coordinates are px.
 */
fun DrawScope.fillPxRects(rects: List<PxRect>, originX: Int, originY: Int, argb: Int) {
    val paint = Paint().apply {
        isAntiAlias = false
        isDither = false
        style = Paint.Style.FILL
        color = argb
    }
    drawIntoCanvas { canvas ->
        rects.forEach {
            canvas.nativeCanvas.drawRect(
                (originX + it.left).toFloat(), (originY + it.top).toFloat(),
                (originX + it.right).toFloat(), (originY + it.bottom).toFloat(),
                paint,
            )
        }
    }
}

/**
 * Draws a [OptotypeRender.Drawable] black on white, centred horizontally at an
 * integer offset. Only a Drawable can be passed: a pixel-limited letter has no
 * geometry and is never drawn.
 */
@Composable
fun OptotypeCanvas(render: OptotypeRender.Drawable, modifier: Modifier = Modifier) {
    val heightDp = with(LocalDensity.current) { render.footprintPx.toDp() }
    Canvas(modifier.fillMaxWidth().height(heightDp).clipToBounds()) {
        val ox = (size.width.toInt() - render.footprintPx) / 2
        fillPxRects(render.rects, ox, 0, android.graphics.Color.BLACK)
    }
}
