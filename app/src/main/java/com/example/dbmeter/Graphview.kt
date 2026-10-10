package com.example.dbmeter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

class GraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val linePaint = Paint().apply {
        color = Color.parseColor("#00E676")
        strokeWidth = 4f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val gridPaint = Paint().apply {
        color = Color.parseColor("#333333")
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    private val textPaint = Paint().apply {
        color = Color.parseColor("#888888")
        textSize = 24f
        isAntiAlias = true
    }

    private val fillPaint = Paint().apply {
        color = Color.parseColor("#1A4D2E")
        style = Paint.Style.FILL
        alpha = 100
    }

    private var dataPoints = listOf<Pair<Long, Double>>()

    fun updateData(data: List<Pair<Long, Double>>) {
        dataPoints = data
        invalidate() // Redraw the view
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (width == 0 || height == 0) return

        // Draw grid lines
        val numHorizontalLines = 5
        for (i in 0..numHorizontalLines) {
            val y = (height.toFloat() / numHorizontalLines) * i
            canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)

            // Draw dB labels
            val db = 110 - (110.0 / numHorizontalLines) * i
            // Each label sits under its line; the bottom one has to go above.
            val textY = if (i == numHorizontalLines) y - 5 else y + textPaint.textSize
            canvas.drawText("${db.toInt()}", 5f, textY, textPaint)
        }

        // Draw vertical time grid (every 5 seconds)
        val timeSpan = 20000L // 20 seconds in milliseconds
        val numVerticalLines = 4 // Lines at 5, 10, 15 seconds
        for (i in 1..numVerticalLines) {
            val x = (width.toFloat() / 4) * i
            canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint)
        }

        if (dataPoints.isEmpty()) return

        val currentTime = System.currentTimeMillis()
        val oldestTime = currentTime - timeSpan

        // Create path for the line graph
        val path = Path()
        val fillPath = Path()
        var firstPoint = true

        dataPoints.forEach { (timestamp, db) ->
            // Calculate X position (right to left, newest on right)
            val timeProgress = (timestamp - oldestTime).toFloat() / timeSpan
            val x = width * timeProgress

            // Calculate Y position (inverted, high dB at top)
            val dbProgress = (db / 110.0).coerceIn(0.0, 1.0)
            val y = height * (1 - dbProgress).toFloat()

            if (firstPoint) {
                path.moveTo(x, y)
                fillPath.moveTo(x, height.toFloat())
                fillPath.lineTo(x, y)
                firstPoint = false
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }

        // Complete fill path
        if (!firstPoint) {
            val lastX = width * ((dataPoints.last().first - oldestTime).toFloat() / timeSpan)
            fillPath.lineTo(lastX, height.toFloat())
            fillPath.close()
            canvas.drawPath(fillPath, fillPaint)
        }

        // Draw the line
        canvas.drawPath(path, linePaint)
    }
}