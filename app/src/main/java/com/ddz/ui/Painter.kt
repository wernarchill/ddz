package com.ddz.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/**
 * 绘制工具。持有一组复用的 Paint，避免每帧 new 出一堆对象（垃圾回收抖动在自绘 UI 里很明显）。
 */
class Painter {

    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        textAlign = Paint.Align.LEFT
    }
    val textBold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        textAlign = Paint.Align.LEFT
    }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** 复用的矩形，减少分配 */
    private val scratch = RectF()

    fun rect(c: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int) {
        fill.color = color
        c.drawRect(l, t, r, b, fill)
    }

    fun roundRect(c: Canvas, l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int) {
        scratch.set(l, t, r, b)
        fill.color = color
        c.drawRoundRect(scratch, radius, radius, fill)
    }

    fun roundRectStroke(
        c: Canvas,
        l: Float, t: Float, r: Float, b: Float,
        radius: Float, color: Int, width: Float
    ) {
        scratch.set(l, t, r, b)
        stroke.color = color
        stroke.strokeWidth = width
        c.drawRoundRect(scratch, radius, radius, stroke)
    }

    fun circle(c: Canvas, cx: Float, cy: Float, radius: Float, color: Int) {
        fill.color = color
        c.drawCircle(cx, cy, radius, fill)
    }

    fun circleStroke(c: Canvas, cx: Float, cy: Float, radius: Float, color: Int, width: Float) {
        stroke.color = color
        stroke.strokeWidth = width
        c.drawCircle(cx, cy, radius, stroke)
    }

    /** 画弧线（倒计时环）。角度制，0 度在三点钟方向，顺时针为正。 */
    fun arc(
        c: Canvas,
        cx: Float, cy: Float, radius: Float,
        startAngle: Float, sweep: Float,
        color: Int, width: Float
    ) {
        scratch.set(cx - radius, cy - radius, cx + radius, cy + radius)
        stroke.color = color
        stroke.strokeWidth = width
        stroke.strokeCap = Paint.Cap.ROUND
        c.drawArc(scratch, startAngle, sweep, false, stroke)
        stroke.strokeCap = Paint.Cap.BUTT
    }

    /** 画文字。align 决定 x 是左/中/右锚点 */
    fun text(
        c: Canvas,
        s: String,
        x: Float,
        y: Float,
        size: Float,
        color: Int,
        align: Paint.Align = Paint.Align.LEFT,
        bold: Boolean = false
    ) {
        val p = if (bold) textBold else textPaint
        p.textSize = size
        p.color = color
        p.textAlign = align
        c.drawText(s, x, y, p)
    }

    fun textWidth(s: String, size: Float, bold: Boolean = false): Float {
        val p = if (bold) textBold else textPaint
        p.textSize = size
        return p.measureText(s)
    }

    /** 把 bitmap 拉伸到指定矩形绘制 */
    fun bitmap(c: Canvas, bmp: Bitmap, l: Float, t: Float, r: Float, b: Float) {
        scratch.set(l, t, r, b)
        c.drawBitmap(bmp, null, scratch, bmpPaint)
    }

    /** 带透明度的 bitmap */
    fun bitmapAlpha(c: Canvas, bmp: Bitmap, l: Float, t: Float, r: Float, b: Float, alpha: Int) {
        val old = bmpPaint.alpha
        bmpPaint.alpha = alpha.coerceIn(0, 255)
        scratch.set(l, t, r, b)
        c.drawBitmap(bmp, null, scratch, bmpPaint)
        bmpPaint.alpha = old
    }
}
