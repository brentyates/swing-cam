package com.example.swingcam.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * Overlay view to display ball tracking trail on top of video.
 * Shows the detected ball positions and path.
 */
class BallTrackerOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var ballPositions = listOf<BallTrackerProcessor.BallPosition>()
    private var currentBallPosition: BallTrackerProcessor.BallPosition? = null

    private val trailPaint = Paint().apply {
        color = Color.YELLOW
        strokeWidth = 8f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
        setShadowLayer(4f, 0f, 0f, Color.BLACK) // Add glow effect
    }

    private val ballPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
        isAntiAlias = true
        setShadowLayer(8f, 0f, 0f, Color.WHITE) // Add glow effect
    }

    private val ballOutlinePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    private val fadePaint = Paint().apply {
        isAntiAlias = true
    }

    var trailColor: Int = Color.YELLOW
        set(value) {
            field = value
            trailPaint.color = value
        }

    var ballColor: Int = Color.RED
        set(value) {
            field = value
            ballPaint.color = value
        }

    var showTrail = true
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (showTrail && ballPositions.size > 1) {
            drawTrail(canvas)
        }

        // Draw current ball position
        currentBallPosition?.let { ball ->
            drawBall(canvas, ball.x, ball.y)
        }
    }

    private fun drawTrail(canvas: Canvas) {
        val path = Path()
        val positions = ballPositions

        if (positions.isEmpty()) return

        // Start at first position
        path.moveTo(positions[0].x, positions[0].y)

        // Draw smooth curve through all positions using quadratic bezier curves
        for (i in 1 until positions.size) {
            val prev = positions[i - 1]
            val current = positions[i]

            // Control point is midpoint between previous and current
            val controlX = (prev.x + current.x) / 2
            val controlY = (prev.y + current.y) / 2

            if (i == 1) {
                path.lineTo(controlX, controlY)
            } else {
                path.quadTo(prev.x, prev.y, controlX, controlY)
            }
        }

        // Draw to last point
        if (positions.size > 1) {
            val last = positions.last()
            path.lineTo(last.x, last.y)
        }

        // Draw trail with fading effect (older positions fade out)
        val pathMeasure = PathMeasure(path, false)
        val pathLength = pathMeasure.length
        val segmentLength = pathLength / 20f // Divide into 20 segments for fading

        for (i in 0..19) {
            val startD = i * segmentLength
            val endD = (i + 1) * segmentLength

            // Calculate alpha for this segment (fade from full to transparent)
            val alpha = (255 * (i + 1) / 20f).toInt()
            fadePaint.set(trailPaint)
            fadePaint.alpha = alpha

            // Extract segment and draw
            val segment = Path()
            pathMeasure.getSegment(startD, endD, segment, true)
            canvas.drawPath(segment, fadePaint)
        }

        // Draw dots at each detected position
        positions.forEach { pos ->
            val dotPaint = Paint().apply {
                color = trailColor
                style = Paint.Style.FILL
                isAntiAlias = true
            }
            canvas.drawCircle(pos.x, pos.y, 6f, dotPaint)
        }
    }

    private fun drawBall(canvas: Canvas, x: Float, y: Float) {
        // Draw ball with outline and glow
        canvas.drawCircle(x, y, 15f, ballPaint)
        canvas.drawCircle(x, y, 15f, ballOutlinePaint)

        // Draw crosshair
        val crosshairPaint = Paint().apply {
            color = Color.WHITE
            strokeWidth = 2f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }
        canvas.drawLine(x - 20, y, x + 20, y, crosshairPaint)
        canvas.drawLine(x, y - 20, x, y + 20, crosshairPaint)
    }

    /**
     * Update the ball trail with new positions
     */
    fun updateTrail(positions: List<BallTrackerProcessor.BallPosition>) {
        ballPositions = positions
        currentBallPosition = positions.lastOrNull()
        invalidate()
    }

    /**
     * Update the current ball position
     */
    fun updateBallPosition(position: BallTrackerProcessor.BallPosition) {
        currentBallPosition = position
        invalidate()
    }

    /**
     * Clear the trail
     */
    fun clearTrail() {
        ballPositions = emptyList()
        currentBallPosition = null
        invalidate()
    }
}
