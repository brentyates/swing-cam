package com.example.swingcam.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

/**
 * Custom view for drawing annotations on top of video playback.
 * Supports multiple drawing tools: lines, circles, freehand, arrows, and angle measurements.
 */
class DrawingOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class DrawingTool {
        NONE,           // No drawing (allows video interaction)
        LINE,           // Straight line
        LINE_WITH_ANGLE,// Line with angle measurement
        CIRCLE,         // Circle/ellipse
        FREEHAND,       // Free drawing
        ARROW           // Arrow
    }

    data class DrawingPath(
        val tool: DrawingTool,
        val path: Path,
        val paint: Paint,
        val startPoint: PointF? = null,
        val endPoint: PointF? = null,
        val angle: Float? = null
    )

    private val drawings = mutableListOf<DrawingPath>()
    private val redoStack = mutableListOf<DrawingPath>()
    private var currentPath: Path? = null
    private var currentStartPoint: PointF? = null
    private var currentEndPoint: PointF? = null

    var currentTool: DrawingTool = DrawingTool.NONE
        set(value) {
            field = value
            invalidate()
        }

    var drawingColor: Int = Color.RED
        set(value) {
            field = value
            paint.color = value
        }

    var strokeWidth: Float = 5f
        set(value) {
            field = value
            paint.strokeWidth = value
        }

    private val paint = Paint().apply {
        color = drawingColor
        strokeWidth = this@DrawingOverlayView.strokeWidth
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 40f
        isAntiAlias = true
        style = Paint.Style.FILL
        setShadowLayer(4f, 2f, 2f, Color.BLACK)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw all saved drawings
        drawings.forEach { drawing ->
            canvas.drawPath(drawing.path, drawing.paint)

            // Draw angle measurement if applicable
            if (drawing.tool == DrawingTool.LINE_WITH_ANGLE &&
                drawing.startPoint != null &&
                drawing.endPoint != null &&
                drawing.angle != null) {
                drawAngleText(canvas, drawing.endPoint, drawing.angle)
            }
        }

        // Draw current path being drawn
        currentPath?.let { path ->
            canvas.drawPath(path, paint)

            // Draw angle for current line if applicable
            if (currentTool == DrawingTool.LINE_WITH_ANGLE &&
                currentStartPoint != null &&
                currentEndPoint != null) {
                val angle = calculateAngle(currentStartPoint!!, currentEndPoint!!)
                drawAngleText(canvas, currentEndPoint!!, angle)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (currentTool == DrawingTool.NONE) {
            return false // Allow touch events to pass through to video player
        }

        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                redoStack.clear() // Clear redo stack when new drawing starts
                startDrawing(x, y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                continueDrawing(x, y)
                return true
            }
            MotionEvent.ACTION_UP -> {
                finishDrawing(x, y)
                return true
            }
        }

        return super.onTouchEvent(event)
    }

    private fun startDrawing(x: Float, y: Float) {
        currentPath = Path()
        currentStartPoint = PointF(x, y)

        when (currentTool) {
            DrawingTool.FREEHAND -> {
                currentPath?.moveTo(x, y)
            }
            else -> {
                // For other tools, we'll draw on move/up
            }
        }
    }

    private fun continueDrawing(x: Float, y: Float) {
        currentEndPoint = PointF(x, y)

        when (currentTool) {
            DrawingTool.FREEHAND -> {
                currentPath?.lineTo(x, y)
            }
            DrawingTool.LINE, DrawingTool.LINE_WITH_ANGLE -> {
                currentPath = Path().apply {
                    moveTo(currentStartPoint!!.x, currentStartPoint!!.y)
                    lineTo(x, y)
                }
            }
            DrawingTool.CIRCLE -> {
                currentPath = createCirclePath(currentStartPoint!!, PointF(x, y))
            }
            DrawingTool.ARROW -> {
                currentPath = createArrowPath(currentStartPoint!!, PointF(x, y))
            }
            else -> {}
        }

        invalidate()
    }

    private fun finishDrawing(x: Float, y: Float) {
        currentEndPoint = PointF(x, y)

        currentPath?.let { path ->
            val drawingPaint = Paint(paint) // Create a copy of current paint settings

            val angle = if (currentTool == DrawingTool.LINE_WITH_ANGLE &&
                           currentStartPoint != null &&
                           currentEndPoint != null) {
                calculateAngle(currentStartPoint!!, currentEndPoint!!)
            } else null

            drawings.add(
                DrawingPath(
                    tool = currentTool,
                    path = path,
                    paint = drawingPaint,
                    startPoint = currentStartPoint,
                    endPoint = currentEndPoint,
                    angle = angle
                )
            )
        }

        currentPath = null
        currentStartPoint = null
        currentEndPoint = null
        invalidate()
    }

    private fun createCirclePath(start: PointF, end: PointF): Path {
        val path = Path()
        val centerX = (start.x + end.x) / 2
        val centerY = (start.y + end.y) / 2
        val radiusX = abs(end.x - start.x) / 2
        val radiusY = abs(end.y - start.y) / 2

        val rect = RectF(
            centerX - radiusX,
            centerY - radiusY,
            centerX + radiusX,
            centerY + radiusY
        )
        path.addOval(rect, Path.Direction.CW)
        return path
    }

    private fun createArrowPath(start: PointF, end: PointF): Path {
        val path = Path()

        // Draw main line
        path.moveTo(start.x, start.y)
        path.lineTo(end.x, end.y)

        // Calculate arrow head
        val angle = atan2((end.y - start.y).toDouble(), (end.x - start.x).toDouble())
        val arrowLength = 40f
        val arrowAngle = Math.toRadians(30.0)

        // First arrow line
        val x1 = end.x - arrowLength * cos(angle - arrowAngle).toFloat()
        val y1 = end.y - arrowLength * sin(angle - arrowAngle).toFloat()
        path.moveTo(end.x, end.y)
        path.lineTo(x1, y1)

        // Second arrow line
        val x2 = end.x - arrowLength * cos(angle + arrowAngle).toFloat()
        val y2 = end.y - arrowLength * sin(angle + arrowAngle).toFloat()
        path.moveTo(end.x, end.y)
        path.lineTo(x2, y2)

        return path
    }

    private fun calculateAngle(start: PointF, end: PointF): Float {
        val deltaY = start.y - end.y // Inverted for screen coordinates
        val deltaX = end.x - start.x
        var angle = Math.toDegrees(atan2(deltaY.toDouble(), deltaX.toDouble())).toFloat()

        // Normalize to 0-360
        if (angle < 0) angle += 360

        return angle
    }

    private fun drawAngleText(canvas: Canvas, point: PointF, angle: Float) {
        val text = "${angle.toInt()}°"
        val textX = point.x + 20
        val textY = point.y - 20
        canvas.drawText(text, textX, textY, textPaint)
    }

    fun undo() {
        if (drawings.isNotEmpty()) {
            val lastDrawing = drawings.removeAt(drawings.size - 1)
            redoStack.add(lastDrawing)
            invalidate()
        }
    }

    fun redo() {
        if (redoStack.isNotEmpty()) {
            val drawing = redoStack.removeAt(redoStack.size - 1)
            drawings.add(drawing)
            invalidate()
        }
    }

    fun clearAll() {
        drawings.clear()
        redoStack.clear()
        currentPath = null
        currentStartPoint = null
        currentEndPoint = null
        invalidate()
    }

    fun hasDrawings(): Boolean = drawings.isNotEmpty()

    fun canUndo(): Boolean = drawings.isNotEmpty()

    fun canRedo(): Boolean = redoStack.isNotEmpty()

    /**
     * Get the current view as a bitmap including all drawings
     */
    fun getDrawingBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        draw(canvas)
        return bitmap
    }
}
