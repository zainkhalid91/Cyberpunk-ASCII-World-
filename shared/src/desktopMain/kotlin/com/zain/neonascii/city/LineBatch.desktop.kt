package com.zain.neonascii.city

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.PaintStrokeCap

private val paint = Paint().apply { isAntiAlias = true; mode = PaintMode.STROKE; strokeCap = PaintStrokeCap.ROUND }

actual fun DrawScope.drawLineBatch(pts: FloatArray, count: Int, color: Color, alpha: Float, width: Float) {
    if (count < 4) return
    paint.color = color.copy(alpha = alpha).toArgb()
    paint.strokeWidth = width
    drawIntoCanvas { it.nativeCanvas.drawLines(pts.copyOf(count - count % 4), paint) }
}

// Skia copies a mutable bitmap into a new image on every draw; an immutable one is shared.
actual fun ImageBitmap.markImmutable() {
    asSkiaBitmap().setImmutable()
}
