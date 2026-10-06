package com.controlcam.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** Simple framing grid drawn over the camera preview. */
class GridOverlay(context: Context, attrs: AttributeSet) : View(context, attrs) {
    private val cols = 5
    private val rows = 10
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(LINE_ALPHA, 255, 255, 255)
        strokeWidth = resources.displayMetrics.density // 1dp
        style = Paint.Style.STROKE
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        for (i in 1 until cols) {
            val x = w * i / cols
            canvas.drawLine(x, 0f, x, h, paint)
        }
        for (i in 1 until rows) {
            val y = h * i / rows
            canvas.drawLine(0f, y, w, y, paint)
        }
    }

    private companion object {
        const val LINE_ALPHA = 110
    }
}
