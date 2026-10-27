package com.ismi9.vdsscope

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** Відображення осцилограм двох каналів (сирі 8-бітні семпли) */
class ScopeView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    var ch1: ByteArray? = null
    var ch2: ByteArray? = null
    var running = false

    private val grid = Paint().apply {
        color = Color.rgb(30, 46, 30); strokeWidth = 1f
    }
    private val axis = Paint().apply {
        color = Color.rgb(60, 90, 60); strokeWidth = 2f
    }
    private val ch1Paint = Paint().apply {
        color = Color.rgb(255, 200, 0); strokeWidth = 3f; isAntiAlias = true
    }
    private val ch2Paint = Paint().apply {
        color = Color.rgb(0, 200, 255); strokeWidth = 3f; isAntiAlias = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        // сітка 10 x 8
        for (i in 0..10) {
            val x = w * i / 10f
            canvas.drawLine(x, 0f, x, h, grid)
        }
        for (i in 0..8) {
            val y = h * i / 8f
            canvas.drawLine(0f, y, w, y, grid)
        }
        canvas.drawLine(w / 2, 0f, w / 2, h, axis)
        canvas.drawLine(0f, h / 2, w, h / 2, axis)
        drawChannel(canvas, ch1, ch1Paint, w, h)
        drawChannel(canvas, ch2, ch2Paint, w, h)
    }

    private fun drawChannel(c: Canvas, data: ByteArray?, p: Paint, w: Float, h: Float) {
        val d = data ?: return
        if (d.isEmpty()) return
        val step = w / d.size
        // семпли знакові: -125..+125, нуль у центрі екрана (як в офіційному ПЗ)
        fun yOf(b: Byte): Float {
            val v = b.toInt().coerceIn(-125, 125)
            return h / 2f - v / 125f * (h / 2f)
        }
        var prevX = 0f
        var prevY = yOf(d[0])
        for (i in 1 until d.size) {
            val x = i * step
            val y = yOf(d[i])
            c.drawLine(prevX, prevY, x, y, p)
            prevX = x; prevY = y
        }
    }
}
