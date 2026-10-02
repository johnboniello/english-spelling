package com.johnb.englishspelling

import android.app.Activity
import android.util.TypedValue
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * From targetSdk 35 the OS forces edge-to-edge and API 36 removes the opt-out,
 * so the window decor no longer insets content for the system bars. These apps
 * use the classic AppCompat action bar (drawn just below the status bar) and the
 * content view is laid out from the very top of the window — so offset it by the
 * status bar + action bar at the top, and the nav bar / cutouts on the other
 * edges. Called once per activity, right after setContentView.
 */
fun Activity.padForSystemBars() {
    val content = findViewById<View>(android.R.id.content)
    val actionBarSize = TypedValue().let { tv ->
        if (theme.resolveAttribute(androidx.appcompat.R.attr.actionBarSize, tv, true))
            TypedValue.complexToDimensionPixelSize(tv.data, resources.displayMetrics)
        else 0
    }
    ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        v.updatePadding(
            left = bars.left,
            top = bars.top + actionBarSize,
            right = bars.right,
            bottom = bars.bottom,
        )
        WindowInsetsCompat.CONSUMED
    }
}
