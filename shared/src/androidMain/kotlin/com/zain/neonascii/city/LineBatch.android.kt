package com.zain.neonascii.city

import android.graphics.Paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb

private val paint = Paint().apply { isAntiAlias = true; strokeCap = Paint.Cap.ROUND }

actual fun DrawScope.drawLineBatch(pts: FloatArray, count: Int, color: Color, alpha: Float, width: Float) {
    if (count < 4) return
    paint.color = color.copy(alpha = alpha).toArgb()
    paint.strokeWidth = width
    drawIntoCanvas { it.nativeCanvas.drawLines(pts, 0, count - count % 4, paint) }
}

// HWUI uploads a bitmap to the GPU once and reuses it; nothing to do.
actual fun ImageBitmap.markImmutable() = Unit
