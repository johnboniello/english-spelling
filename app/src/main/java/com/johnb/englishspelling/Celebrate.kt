package com.johnb.englishspelling

import android.app.Activity
import android.view.Gravity
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * Visual reward for a correct answer: a confetti burst plus a quick pop of the
 * owl mascot. Sits alongside the existing [Feedback] sound/voice line — call
 * [correct] right after `Feedback.correct(...)`, and [reset] next to
 * `Feedback.wrong(...)` so the streak counter stays honest.
 *
 * Everything is drawn/animated by hand: no new dependencies, no assets beyond the
 * mascot PNG the app already ships.
 */
object Celebrate {

    private var streak = 0

    fun correct(activity: Activity) {
        streak++
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return

        val big = streak > 0 && streak % 3 == 0
        val confetti = ConfettiView(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isClickable = false
            isFocusable = false
        }
        root.addView(confetti)
        confetti.start(if (big) 2.2f else 1f)

        popMascot(activity, root, big)
    }

    fun reset() {
        streak = 0
    }

    private fun popMascot(activity: Activity, root: ViewGroup, big: Boolean) {
        val density = activity.resources.displayMetrics.density
        val size = ((if (big) 168 else 132) * density).toInt()

        val holder = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
                topMargin = (56 * density).toInt()
            }
            isClickable = false
            isFocusable = false
        }
        val img = ImageView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setImageResource(R.drawable.mascot_owl)
            contentDescription = null
        }
        holder.addView(img)
        root.addView(holder)

        holder.scaleX = 0.2f
        holder.scaleY = 0.2f
        holder.alpha = 0f
        holder.animate()
            .scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(280)
            .setInterpolator(OvershootInterpolator(2.5f))
            .withEndAction {
                holder.animate()
                    .alpha(0f)
                    .setStartDelay(if (big) 1100 else 800)
                    .setDuration(260)
                    .withEndAction { (holder.parent as? ViewGroup)?.removeView(holder) }
                    .start()
            }
            .start()
    }
}
