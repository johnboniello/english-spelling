package com.johnb.englishspelling

import android.app.Activity
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.security.MessageDigest

/**
 * Optional parent PIN (not the phone's). When one is set, the parent screens
 * (word list, scan) ask for it, so a child can't change the game limits or
 * reset them with a new week. Off unless a parent turns it on.
 */
object ParentLock {

    private fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256").digest("spelling-pin:$pin".toByteArray())
            .joinToString("") { "%02x".format(it) }

    fun isSet(store: WordStore) = store.parentPinHash() != null

    /** Runs [action] right away when there's no PIN, otherwise once the right PIN is entered. */
    fun unlock(a: Activity, store: WordStore, action: () -> Unit) {
        val want = store.parentPinHash() ?: return action()
        askPin(a, "Parent PIN", forgot = true) { pin ->
            if (hash(pin) == want) action()
            else {
                Toast.makeText(a, "Wrong PIN.", Toast.LENGTH_SHORT).show()
                unlock(a, store, action)
            }
        }
    }

    /** Set, change or remove the PIN (called from inside the parent area). */
    fun manage(a: Activity, store: WordStore, onDone: () -> Unit) {
        if (!isSet(store)) return setNew(a, store, onDone)
        AlertDialog.Builder(a)
            .setTitle("🔑  Parent PIN")
            .setItems(arrayOf("Change PIN", "Remove PIN")) { _, which ->
                if (which == 0) setNew(a, store, onDone)
                else {
                    store.setParentPinHash(null)
                    Toast.makeText(a, "Parent PIN removed.", Toast.LENGTH_SHORT).show()
                    onDone()
                }
            }
            .setNegativeButton("Cancel") { _, _ -> onDone() }
            .show()
    }

    private fun setNew(a: Activity, store: WordStore, onDone: () -> Unit) {
        askPin(a, "New parent PIN") { first ->
            if (first.length < 4) {
                Toast.makeText(a, "The PIN must be 4 to 8 digits.", Toast.LENGTH_LONG).show()
                return@askPin setNew(a, store, onDone)
            }
            askPin(a, "Enter the PIN again") { second ->
                if (second != first) {
                    Toast.makeText(a, "The two PINs don't match.", Toast.LENGTH_LONG).show()
                    setNew(a, store, onDone)
                } else {
                    store.setParentPinHash(hash(first))
                    Toast.makeText(a, "Parent PIN set. It will be asked for Manage Words and Scan a List.", Toast.LENGTH_LONG).show()
                    onDone()
                }
            }
        }
    }

    fun statusText(store: WordStore) = if (isSet(store)) "on" else "none"

    private fun askPin(a: Activity, title: String, forgot: Boolean = false, onPin: (String) -> Unit) {
        val input = EditText(a).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(8))
            hint = "PIN (4 to 8 digits)"
        }
        val pad = (20 * a.resources.displayMetrics.density).toInt()
        val box = FrameLayout(a).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        val b = AlertDialog.Builder(a)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("OK") { _, _ -> onPin(input.text.toString()) }
            .setNegativeButton("Cancel", null)
        if (forgot) b.setNeutralButton("Forgot PIN?") { _, _ ->
            AlertDialog.Builder(a).setTitle("Forgot PIN?").setMessage("To remove the PIN: phone Settings → Apps → Spelling EN → Storage → Clear data.\n\nThe word list comes back at the next sync if you use a family code; otherwise you'll need to scan it again.")
                .setPositiveButton("OK", null).show()
        }
        val d = b.create()
        d.setOnShowListener {
            input.requestFocus()
            d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        d.show()
    }
}
