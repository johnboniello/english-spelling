package com.johnb.englishspelling

import org.json.JSONObject

/**
 * "Words to review" — a persistent, per-word practice record that survives the
 * weekly list swap. Keyed by the normalized word (trim + lowercase).
 *
 * Box model (no spacing clock — the weekly list swap is the session boundary):
 *   a miss  -> box 1
 *   a correct answer -> box + 1
 *   box 4   -> graduated, off the review list
 * A word is only "on the list" once it has actually been missed (or is pinned).
 * A word with no miss in 4 weeks graduates on the next "New week".
 */
object Stats {

    private const val GRAD_BOX = 4
    private const val MAX_BOX = 4
    private const val CAP = 18
    private const val AGE_OUT_MS = 28L * 24 * 60 * 60 * 1000

    class Entry(
        var text: String,
        var box: Int = 1,
        var seen: Int = 0,
        var miss: Int = 0,
        var lastMissAt: Long = 0,
        var pinned: Boolean = false,
    )

    data class ManageItem(val text: String, val box: Int, val miss: Int, val pinned: Boolean)

    fun norm(s: String): String = s.trim().lowercase()

    // ---- JSON (used by WordStore + Sync) ----

    fun toJson(map: Map<String, Entry>): String {
        val o = JSONObject()
        for ((k, e) in map) {
            o.put(k, JSONObject().apply {
                put("text", e.text)
                put("box", e.box)
                put("seen", e.seen)
                put("miss", e.miss)
                put("lastMissAt", e.lastMissAt)
                put("pinned", e.pinned)
            })
        }
        return o.toString()
    }

    fun fromJson(raw: String?): MutableMap<String, Entry> {
        val out = LinkedHashMap<String, Entry>()
        if (raw.isNullOrBlank()) return out
        try {
            val o = JSONObject(raw)
            for (k in o.keys()) {
                val e = o.getJSONObject(k)
                out[k] = Entry(
                    text = e.optString("text", k),
                    box = e.optInt("box", 1).coerceIn(1, MAX_BOX),
                    seen = e.optInt("seen", 0),
                    miss = e.optInt("miss", 0),
                    lastMissAt = e.optLong("lastMissAt", 0),
                    pinned = e.optBoolean("pinned", false),
                )
            }
        } catch (_: Exception) {
        }
        return out
    }

    // ---- core ----

    private fun isAgedOut(e: Entry): Boolean =
        !e.pinned && e.box < GRAD_BOX && e.lastMissAt > 0 &&
            (System.currentTimeMillis() - e.lastMissAt) > AGE_OUT_MS

    private fun onList(e: Entry): Boolean =
        e.pinned || (e.miss > 0 && e.box < GRAD_BOX && !isAgedOut(e))

    /** Record one round's outcome. missed = wrong at least once, or answer revealed. */
    fun record(store: WordStore, word: String, missed: Boolean) {
        synchronized(WordStore.LOCK) {
            if (word.isBlank()) return
            val m = store.stats()
            val k = norm(word)
            val e = m.getOrPut(k) { Entry(word) }
            e.text = word
            e.seen++
            if (missed) {
                e.box = 1
                e.miss++
                e.lastMissAt = System.currentTimeMillis()
            } else {
                e.box = (e.box + 1).coerceAtMost(MAX_BOX)
            }
            store.saveStats(m)
        }
    }

    fun dueCount(store: WordStore): Int = store.stats().values.count { onList(it) }

    /** Words for one review session: weighted toward box 1 / pinned, capped, shuffled. */
    fun poolWords(store: WordStore): List<String> {
        val due = store.stats().values.filter { onList(it) }
        val bag = ArrayList<String>()
        for (e in due) {
            val w = (if (e.box == 1) 3 else 1) + (if (e.pinned) 2 else 0)
            repeat(w) { bag.add(e.text) }
        }
        bag.shuffle()
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        for (t in bag) {
            if (seen.add(norm(t))) out.add(t)
            if (out.size >= CAP) break
        }
        return out
    }

    /** For the "Manage Words" review section — hardest first. */
    fun listForManage(store: WordStore): List<ManageItem> =
        store.stats().values.filter { onList(it) }
            .sortedWith(
                compareBy<Entry> { if (it.pinned) 1 else 0 }
                    .thenBy { it.box }
                    .thenByDescending { it.lastMissAt }
            )
            .map { ManageItem(it.text, it.box, it.miss, it.pinned) }

    fun master(store: WordStore, word: String) {
        synchronized(WordStore.LOCK) {
            val m = store.stats()
            m[norm(word)]?.let {
                it.box = MAX_BOX
                it.pinned = false
                store.saveStats(m)
            }
        }
    }

    fun setPinned(store: WordStore, word: String, on: Boolean) {
        synchronized(WordStore.LOCK) {
            val m = store.stats()
            val e = m.getOrPut(norm(word)) { Entry(word) }
            e.pinned = on
            store.saveStats(m)
        }
    }

    fun isPinned(store: WordStore, word: String): Boolean = store.stats()[norm(word)]?.pinned == true

    /** Graduate aged-out words, drop clearly-finished ones. Called on "New week". */
    fun prune(store: WordStore) {
        synchronized(WordStore.LOCK) {
            val m = store.stats()
            var changed = false
            val it = m.entries.iterator()
            while (it.hasNext()) {
                val e = it.next().value
                if (isAgedOut(e)) {
                    e.box = GRAD_BOX
                    changed = true
                }
                if (!e.pinned && e.box >= GRAD_BOX &&
                    (e.lastMissAt == 0L || (System.currentTimeMillis() - e.lastMissAt) > AGE_OUT_MS)
                ) {
                    it.remove()
                    changed = true
                }
            }
            if (changed) store.saveStats(m)
        }
    }

    /** Remove every graduated (non-pinned) word. Manual "tidy up". */
    fun clearMastered(store: WordStore): Int {
        synchronized(WordStore.LOCK) {
            val m = store.stats()
            var n = 0
            val it = m.entries.iterator()
            while (it.hasNext()) {
                val e = it.next().value
                if (!e.pinned && !onList(e)) {
                    it.remove()
                    n++
                }
            }
            if (n > 0) store.saveStats(m)
            return n
        }
    }

    fun boxDots(box: Int): String {
        val filled = box.coerceIn(0, MAX_BOX)
        return "●".repeat(filled) + "○".repeat(MAX_BOX - filled)
    }

    /** Merge two stat maps: highest box wins, pinned OR-s, counters/timestamps take the max. */
    fun merge(local: Map<String, Entry>, remote: Map<String, Entry>): MutableMap<String, Entry> {
        val out = LinkedHashMap<String, Entry>()
        for (k in (local.keys + remote.keys)) {
            val l = local[k]
            val r = remote[k]
            when {
                l != null && r == null -> out[k] = l
                r != null && l == null -> out[k] = r
                // The side with the more recent miss decides the box: a miss
                // resets it to 1, and taking the max would let the other
                // device's older, higher box silently erase that miss. Same
                // last miss -> keep the higher box (progress or "mastered"
                // made after that shared miss).
                l != null && r != null -> out[k] = Entry(
                    text = if (l.text.length >= r.text.length) l.text else r.text,
                    box = when {
                        l.lastMissAt > r.lastMissAt -> l.box
                        r.lastMissAt > l.lastMissAt -> r.box
                        else -> maxOf(l.box, r.box)
                    },
                    seen = maxOf(l.seen, r.seen),
                    miss = maxOf(l.miss, r.miss),
                    lastMissAt = maxOf(l.lastMissAt, r.lastMissAt),
                    pinned = l.pinned || r.pinned,
                )
            }
        }
        return out
    }
}
