package com.johnb.englishspelling

import android.app.Activity
import android.os.Bundle
import androidx.appcompat.app.AlertDialog

/**
 * Optional parent setting: how many rounds of each game are allowed per week's
 * list. Unlimited unless a parent picks a number in "Game limits". The count
 * starts over with every "New week" (here, or synced from another device).
 */
object PlayLimit {

    enum class Game(val key: String, val title: String) {
        SCRAMBLE("scramble", "🔤  Scrambled Letters"),
        CHOICE("choice", "🎯  Pick the Spelling"),
        DICTATION("dictation", "✏️  Write the Word"),
    }

    /** The limits a parent can pick; 0 = unlimited. */
    private val OPTIONS = intArrayOf(0, 1, 2, 3, 4, 5, 10)

    private fun name(game: Game) = game.title.substringAfter("  ")

    /** Home-screen label: the game's name, plus what's left when it's limited. */
    fun label(store: WordStore, game: Game): String = game.title + when (val left = store.playsLeft(game.key)) {
        null -> ""
        0 -> " (done for this week)"
        1 -> " (1 game left)"
        else -> " ($left games left)"
    }

    fun blockedText(store: WordStore, game: Game): String {
        val limit = store.playLimit(game.key)
        return "You've played ${name(game)} ${if (limit == 1) "once" else "$limit times"} this week. 💪\n\nTry another game!"
    }

    /** The end-of-round dialog: offers another round only if the limit allows it. */
    fun showEndDialog(
        a: Activity, store: WordStore, game: Game,
        title: String, message: String, back: String, restart: () -> Unit,
    ) {
        val left = store.playsLeft(game.key)
        val b = AlertDialog.Builder(a).setTitle(title).setCancelable(false)
        if (left == 0) {
            b.setMessage(message + "\n\n" + "No more ${name(game)} games this week.")
            b.setNegativeButton(back) { _, _ -> a.finish() }
        } else {
            b.setMessage(message + when (left) {
                null -> ""
                1 -> "\n\n1 game left"
                else -> "\n\n$left games left"
            })
            b.setPositiveButton("Play again") { _, _ -> restart() }
            b.setNegativeButton(back, null)
        }
        b.show()
    }

    private fun describe(limit: Int) = when (limit) {
        0 -> "Unlimited"
        1 -> "1 game per week"
        else -> "$limit games per week"
    }

    /** Parental controls: each game's limit, and the parent PIN. */
    fun settingsDialog(a: Activity, store: WordStore, onChange: () -> Unit = {}) {
        val games = Game.values()
        val items = (games.map { "${it.title}  —  ${describe(store.playLimit(it.key))}" } +
            "🔑  Parent PIN  —  ${ParentLock.statusText(store)}").toTypedArray()
        AlertDialog.Builder(a)
            .setTitle("🔒  Parental controls")
            .setItems(items) { _, i ->
                if (i < games.size) pickLimit(a, store, games[i], onChange)
                else ParentLock.manage(a, store) { settingsDialog(a, store, onChange) }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun pickLimit(a: Activity, store: WordStore, game: Game, onChange: () -> Unit) {
        val current = OPTIONS.indexOf(store.playLimit(game.key)).coerceAtLeast(0)
        AlertDialog.Builder(a)
            .setTitle(game.title)
            .setSingleChoiceItems(OPTIONS.map { describe(it) }.toTypedArray(), current) { d, w ->
                store.setPlayLimit(game.key, OPTIONS[w])
                d.dismiss()
                onChange()
                settingsDialog(a, store, onChange)
            }
            .show()
    }
}

/**
 * One game screen's round, as far as [PlayLimit] is concerned. A round uses up
 * a play on its first answer, so opening a game and backing out costs nothing;
 * a word-list change mid-round (a sync) restarts it without charging again.
 */
class RoundCharge(private val store: WordStore, private val game: PlayLimit.Game) {
    /** The week ([WordStore.wordsReplacedAt]) this round was charged to; -1 = not yet. */
    private var week = -1L

    private fun charged() = week == store.wordsReplacedAt()

    /** A new round can't start: the week's plays are used up and this one isn't paid for. */
    fun blocked() = !charged() && store.playsLeft(game.key) == 0

    fun charge() {
        if (charged()) return
        store.usePlay(game.key)
        week = store.wordsReplacedAt()
    }

    /** "Play again": the next round is a new play. */
    fun reset() {
        week = -1L
    }

    fun save(out: Bundle) = out.putLong(KEY, week)

    fun restore(state: Bundle) {
        week = state.getLong(KEY, -1L)
    }

    private companion object {
        const val KEY = "state_charged_week"
    }
}
