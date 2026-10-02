package com.johnb.englishspelling

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.random.Random

/**
 * A one-shot confetti burst, drawn by hand so the app keeps zero extra
 * dependencies and works fully offline. Add it on top of a screen's root view,
 * call [start], and it removes itself from its parent when every piece has
 * fallen off-screen.
 */
class ConfettiView(context: Context) : View(context) {

    private class Piece(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        var rot: Float,
        var vr: Float,
        val w: Float,
        val h: Float,
        val color: Int,
        val round: Boolean,
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val pieces = ArrayList<Piece>()
    private var lastFrame = 0L
    private var running = false

    private val colors = intArrayOf(
        0xFF1565C0.toInt(), // blue
        0xFFE23B3B.toInt(), // red
        0xFFFAC775.toInt(), // gold
        0xFFFFFFFF.toInt(), // white
        0xFF85B7EB.toInt(), // pale blue
    )

    /** @param intensity 1f for a normal burst, larger for a streak celebration. */
    fun start(intensity: Float = 1f) {
        val count = (70 * intensity).toInt().coerceIn(40, 220)
        post {
            val w = width.toFloat().coerceAtLeast(1f)
            val originY = height * 0.32f
            pieces.clear()
            repeat(count) {
                val fromLeft = Random.nextBoolean()
                val px = if (fromLeft) w * Random.nextFloat() * 0.15f else w * (0.85f + Random.nextFloat() * 0.15f)
                val spread = (if (fromLeft) 1f else -1f)
                pieces.add(
                    Piece(
                        x = px,
                        y = originY + Random.nextFloat() * 40f - 20f,
                        vx = spread * (3f + Random.nextFloat() * 7f) * density(),
                        vy = -(6f + Random.nextFloat() * 8f) * density(),
                        rot = Random.nextFloat() * 360f,
                        vr = (Random.nextFloat() - 0.5f) * 24f,
                        w = (7f + Random.nextFloat() * 7f) * density(),
                        h = (10f + Random.nextFloat() * 10f) * density(),
                        color = colors[Random.nextInt(colors.size)],
                        round = Random.nextFloat() < 0.35f,
                    )
                )
            }
            running = true
            lastFrame = 0L
            postInvalidateOnAnimation()
        }
    }

    private fun density() = resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        if (!running) return
        val now = System.nanoTime()
        val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1_000_000_000f).coerceAtMost(0.05f)
        lastFrame = now

        val gravity = 22f * density()
        val drag = 0.99f
        val floor = height + 40f
        var alive = false

        val it = pieces.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.vy += gravity * dt
            p.vx *= drag
            p.x += p.vx * dt * 60f
            p.y += p.vy * dt * 60f
            p.rot += p.vr

            if (p.y > floor) {
                it.remove()
                continue
            }
            alive = true

            paint.color = p.color
            canvas.save()
            canvas.translate(p.x, p.y)
            canvas.rotate(p.rot)
            if (p.round) {
                canvas.drawCircle(0f, 0f, p.w * 0.5f, paint)
            } else {
                rect.set(-p.w / 2f, -p.h / 2f, p.w / 2f, p.h / 2f)
                canvas.drawRoundRect(rect, 3f, 3f, paint)
            }
            canvas.restore()
        }

        if (alive) {
            postInvalidateOnAnimation()
        } else {
            running = false
            post { (parent as? android.view.ViewGroup)?.removeView(this) }
        }
    }
}
