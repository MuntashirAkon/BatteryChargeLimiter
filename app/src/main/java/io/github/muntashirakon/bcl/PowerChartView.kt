// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.bcl

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors

/**
 * A small filled line chart of the recent battery power samples, drawn with the
 * theme's primary color. Positive values are charging, negative values are load,
 * and the dashed line marks zero.
 */
class PowerChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val samples = ArrayDeque<Float>()
    private val maxSamples = 60
    private val density = resources.displayMetrics.density

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2.5f * density
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val zeroPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 6f * density), 0f)
    }

    private val accentColor =
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, Color.BLUE)
    private val zeroColor =
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant, Color.GRAY)
    private var gradient: Shader? = null

    init {
        linePaint.color = accentColor
        zeroPaint.color = zeroColor
    }

    fun addSample(watts: Float) {
        if (watts.isNaN() || watts.isInfinite()) return
        samples.addLast(watts)
        while (samples.size > maxSamples) {
            samples.removeFirst()
        }
        invalidate()
    }

    fun clear() {
        samples.clear()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        gradient = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(
                (accentColor and 0x00FFFFFF) or 0x66000000,
                (accentColor and 0x00FFFFFF) or 0x00000000,
            ),
            null,
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (samples.size < 2) return

        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val top = paddingTop.toFloat()
        val bottom = (height - paddingBottom).toFloat()
        if (right <= left || bottom <= top) return

        var min = 0f
        var max = 0f
        for (sample in samples) {
            if (sample < min) min = sample
            if (sample > max) max = sample
        }
        if (max - min < 0.5f) max = min + 0.5f

        val stepX = (right - left) / (maxSamples - 1).coerceAtLeast(1)
        // Keep the newest sample at the right edge.
        var x = right - stepX * (samples.size - 1)
        val path = Path()
        val line = Path()
        var first = true
        val zeroY = bottom - (0f - min) / (max - min) * (bottom - top)
        for (sample in samples) {
            val y = bottom - (sample - min) / (max - min) * (bottom - top)
            if (first) {
                line.moveTo(x, y)
                path.moveTo(x, bottom)
                path.lineTo(x, y)
                first = false
            } else {
                line.lineTo(x, y)
                path.lineTo(x, y)
            }
            x += stepX
        }
        path.lineTo(right, bottom)
        path.close()

        fillPaint.shader = gradient
        canvas.drawPath(path, fillPaint)
        canvas.drawPath(line, linePaint)
        if (min < 0f && max > 0f) {
            canvas.drawLine(left, zeroY, right, zeroY, zeroPaint)
        }
    }
}
