package com.hamseda.walkie.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Subtle doodle wallpaper behind the chat message list, in the spirit of
 * Telegram's patterned chat background.
 *
 * Clean-room: hand-drawn abstract mini-shapes (rings, plus signs, dots,
 * arcs, diamonds, squiggles) scattered on a deterministic jittered grid —
 * no Telegram artwork or assets copied. A fixed seed makes the field
 * identical on every composition (no flicker, no remembered state).
 */
@Composable
fun ChatWallpaper(dark: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val ink = (if (dark) Color.White else Color.Black)
            .copy(alpha = if (dark) 0.055f else 0.05f)
        // Fresh deterministic sequence on every draw: same shapes, every frame.
        val rng = Random(0x2A4D2048L)
        val stroke = 1.6f.dp.toPx()
        val cell = 104.dp.toPx()
        var row = 0
        var cy = cell * 0.5f
        while (cy < size.height + cell) {
            var cx = cell * 0.5f + (if (row % 2 == 1) cell * 0.5f else 0f)
            while (cx < size.width + cell) {
                // Jitter inside the cell so the grid doesn't look mechanical.
                val jx = cx + (rng.nextFloat() - 0.5f) * cell * 0.45f
                val jy = cy + (rng.nextFloat() - 0.5f) * cell * 0.45f
                val s = 7.dp.toPx() + rng.nextFloat() * 6.dp.toPx()
                when (rng.nextInt(6)) {
                    0 -> drawDoodleRing(jx, jy, s, ink, stroke)
                    1 -> drawDoodlePlus(jx, jy, s, rng.nextFloat() * 90f, ink, stroke)
                    2 -> drawCircle(ink, s * 0.3f, Offset(jx, jy))
                    3 -> drawDoodleArc(jx, jy, s, rng.nextFloat() * 360f, ink, stroke)
                    4 -> drawDoodleDiamond(jx, jy, s, ink, stroke)
                    else -> drawDoodleSquiggle(jx, jy, s, ink, stroke)
                }
                cx += cell
            }
            cy += cell
            row++
        }
    }
}

private fun DrawScope.drawDoodleRing(x: Float, y: Float, r: Float, c: Color, w: Float) {
    drawCircle(c, r, Offset(x, y), style = Stroke(w))
}

private fun DrawScope.drawDoodlePlus(x: Float, y: Float, s: Float, rotDeg: Float, c: Color, w: Float) {
    val a = Math.toRadians(rotDeg.toDouble())
    val dx = cos(a).toFloat() * s
    val dy = sin(a).toFloat() * s
    drawLine(c, Offset(x - dx, y - dy), Offset(x + dx, y + dy), w)
    // Perpendicular bar: (-dy, dx) is orthogonal to (dx, dy).
    drawLine(c, Offset(x + dy, y - dx), Offset(x - dy, y + dx), w)
}

private fun DrawScope.drawDoodleArc(x: Float, y: Float, s: Float, startDeg: Float, c: Color, w: Float) {
    drawArc(
        color = c,
        startAngle = startDeg,
        sweepAngle = 200f,
        useCenter = false,
        topLeft = Offset(x - s, y - s),
        size = Size(s * 2f, s * 2f),
        style = Stroke(w),
    )
}

private fun DrawScope.drawDoodleDiamond(x: Float, y: Float, s: Float, c: Color, w: Float) {
    val p = Path().apply {
        moveTo(x, y - s)
        lineTo(x + s * 0.7f, y)
        lineTo(x, y + s)
        lineTo(x - s * 0.7f, y)
        close()
    }
    drawPath(p, c, style = Stroke(w))
}

private fun DrawScope.drawDoodleSquiggle(x: Float, y: Float, s: Float, c: Color, w: Float) {
    val p = Path().apply {
        moveTo(x - s, y)
        quadraticTo(x - s * 0.5f, y - s * 0.85f, x, y)
        quadraticTo(x + s * 0.5f, y + s * 0.85f, x + s, y)
    }
    drawPath(p, c, style = Stroke(w))
}
