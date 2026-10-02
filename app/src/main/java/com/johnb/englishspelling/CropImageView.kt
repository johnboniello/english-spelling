package com.johnb.englishspelling

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.min

/**
 * Shows a photo with a draggable crop frame. Drag a corner or an edge to resize the
 * frame, drag inside it to move it. The frame is kept in bitmap-pixel coordinates, so
 * it survives the view being resized (e.g. on rotation).
 */
class CropImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var bitmap: Bitmap? = null
    private val crop = RectF()   // bitmap-pixel coordinates
    private val dest = RectF()   // where the bitmap is drawn on screen
    private var scale = 1f

    private val dp = resources.displayMetrics.density
    private val margin = 20 * dp        // breathing room so edge handles stay reachable
    private val touchSlop = 24 * dp
    private val minSideOnScreen = 72 * dp

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint().apply { color = 0xAA000000.toInt() }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2 * dp
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF; style = Paint.Style.STROKE; strokeWidth = 1 * dp
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.tri_gold)
        style = Paint.Style.STROKE; strokeWidth = 5 * dp; strokeCap = Paint.Cap.ROUND
    }

    private var dragMode = 0
    private var lastX = 0f
    private var lastY = 0f

    fun setBitmap(bmp: Bitmap) {
        bitmap = bmp
        crop.set(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        layoutImage()
        invalidate()
    }

    /** The crop frame as fractions (0..1) of the image's width and height. */
    fun cropFraction(): RectF {
        val bmp = bitmap ?: return RectF(0f, 0f, 1f, 1f)
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        return RectF(crop.left / w, crop.top / h, crop.right / w, crop.bottom / h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutImage()
    }

    private fun layoutImage() {
        val bmp = bitmap ?: return
        if (width == 0 || height == 0) return
        scale = min((width - 2 * margin) / bmp.width, (height - 2 * margin) / bmp.height)
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        dest.set((width - dw) / 2, (height - dh) / 2, (width + dw) / 2, (height + dh) / 2)
    }

    /** The crop frame in view coordinates. */
    private fun frame() = RectF(
        dest.left + crop.left * scale, dest.top + crop.top * scale,
        dest.left + crop.right * scale, dest.top + crop.bottom * scale,
    )

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: return
        canvas.drawBitmap(bmp, null, dest, bitmapPaint)

        val f = frame()
        canvas.drawRect(dest.left, dest.top, dest.right, f.top, dimPaint)
        canvas.drawRect(dest.left, f.bottom, dest.right, dest.bottom, dimPaint)
        canvas.drawRect(dest.left, f.top, f.left, f.bottom, dimPaint)
        canvas.drawRect(f.right, f.top, dest.right, f.bottom, dimPaint)

        for (i in 1..2) {
            val x = f.left + f.width() * i / 3
            val y = f.top + f.height() * i / 3
            canvas.drawLine(x, f.top, x, f.bottom, gridPaint)
            canvas.drawLine(f.left, y, f.right, y, gridPaint)
        }
        canvas.drawRect(f, framePaint)

        // Gold corner brackets and mid-edge bars show what can be grabbed.
        val arm = min(28 * dp, min(f.width(), f.height()) / 3)
        for ((cx, dx) in listOf(f.left to 1f, f.right to -1f)) {
            for ((cy, dy) in listOf(f.top to 1f, f.bottom to -1f)) {
                canvas.drawLine(cx, cy, cx + dx * arm, cy, handlePaint)
                canvas.drawLine(cx, cy, cx, cy + dy * arm, handlePaint)
            }
        }
        val barX = min(20 * dp, f.width() / 6)
        val barY = min(20 * dp, f.height() / 6)
        val mx = f.centerX()
        val my = f.centerY()
        canvas.drawLine(mx - barX, f.top, mx + barX, f.top, handlePaint)
        canvas.drawLine(mx - barX, f.bottom, mx + barX, f.bottom, handlePaint)
        canvas.drawLine(f.left, my - barY, f.left, my + barY, handlePaint)
        canvas.drawLine(f.right, my - barY, f.right, my + barY, handlePaint)
    }

    private fun hitTest(x: Float, y: Float): Int {
        val f = frame()
        var mode = 0
        if (y >= f.top - touchSlop && y <= f.bottom + touchSlop) {
            val dl = abs(x - f.left)
            val dr = abs(x - f.right)
            if (dl <= touchSlop || dr <= touchSlop) mode = mode or (if (dl <= dr) LEFT else RIGHT)
        }
        if (x >= f.left - touchSlop && x <= f.right + touchSlop) {
            val dt = abs(y - f.top)
            val db = abs(y - f.bottom)
            if (dt <= touchSlop || db <= touchSlop) mode = mode or (if (dt <= db) TOP else BOTTOM)
        }
        if (mode == 0 && f.contains(x, y)) mode = MOVE
        return mode
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val bmp = bitmap ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragMode = hitTest(event.x, event.y)
                if (dragMode == 0) return false
                lastX = event.x
                lastY = event.y
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragMode == 0) return false
                drag((event.x - lastX) / scale, (event.y - lastY) / scale, bmp)
                lastX = event.x
                lastY = event.y
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragMode = 0
        }
        return true
    }

    private fun drag(dx: Float, dy: Float, bmp: Bitmap) {
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        if (dragMode == MOVE) {
            val w = crop.width()
            val h = crop.height()
            val l = clamp(crop.left + dx, 0f, bw - w)
            val t = clamp(crop.top + dy, 0f, bh - h)
            crop.set(l, t, l + w, t + h)
            return
        }
        val minW = min(minSideOnScreen / scale, bw / 2)
        val minH = min(minSideOnScreen / scale, bh / 2)
        if (dragMode and LEFT != 0) crop.left = clamp(crop.left + dx, 0f, crop.right - minW)
        if (dragMode and RIGHT != 0) crop.right = clamp(crop.right + dx, crop.left + minW, bw)
        if (dragMode and TOP != 0) crop.top = clamp(crop.top + dy, 0f, crop.bottom - minH)
        if (dragMode and BOTTOM != 0) crop.bottom = clamp(crop.bottom + dy, crop.top + minH, bh)
    }

    // coerceIn throws when hi < lo, which a rotation can briefly cause.
    private fun clamp(v: Float, lo: Float, hi: Float) = if (hi < lo) lo else v.coerceIn(lo, hi)

    private companion object {
        const val LEFT = 1
        const val TOP = 2
        const val RIGHT = 4
        const val BOTTOM = 8
        const val MOVE = 16
    }
}
