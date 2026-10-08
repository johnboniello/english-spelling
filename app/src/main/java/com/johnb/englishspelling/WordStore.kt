package com.johnb.englishspelling

import android.content.Context
import org.json.JSONArray

/** Persists the practice word list, review stats, and voice settings in SharedPreferences. */
class WordStore(context: Context) {

    private val prefs = context.getSharedPreferences("english_spelling", Context.MODE_PRIVATE)

    fun words(): MutableList<String> {
        val raw = prefs.getString(KEY_WORDS, null) ?: return defaultWords()
        return try {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { arr.getString(it) }
        } catch (e: Exception) {
            defaultWords()
        }
    }

    /** Local edit: save and stamp "now" so sync knows this device is ahead. */
    fun save(list: List<String>) {
        writeWords(list, System.currentTimeMillis(), wordsReplacedAt())
    }

    /** "New week": replace the whole list and mark a new generation. */
    fun replaceWords(list: List<String>) {
        val now = System.currentTimeMillis()
        writeWords(list, now, now)
    }

    /** Sync adopted a list from the server: save it with the server's timestamps. */
    fun saveFromSync(list: List<String>, updatedAt: Long, replacedAt: Long) {
        writeWords(list, updatedAt, replacedAt)
        prefs.edit().putLong(KEY_SYNCED_AT, System.currentTimeMillis()).apply()
    }

    private fun writeWords(list: List<String>, updatedAt: Long, replacedAt: Long) {
        val arr = JSONArray()
        for (w in list) arr.put(w)
        prefs.edit()
            .putString(KEY_WORDS, arr.toString())
            .putLong(KEY_WORDS_AT, updatedAt)
            .putLong(KEY_WORDS_REPLACED_AT, replacedAt)
            .apply()
    }

    /** Adds the words that aren't already in the list (case-insensitive) and
     *  clears any tombstone on them. Returns how many were new. */
    fun addWords(words: List<String>): Int {
        synchronized(LOCK) {
            val current = words()
            var added = 0
            for (w in words) {
                if (current.none { it.equals(w, ignoreCase = true) }) {
                    current.add(w)
                    added++
                }
                // A word deleted earlier must lose its tombstone, or the next
                // sync's merge filters it right back out.
                unmarkDeleted(w)
            }
            save(current)
            return added
        }
    }

    /** Removes one word and tombstones it so sync won't bring it back. */
    fun deleteWord(word: String) {
        synchronized(LOCK) {
            val current = words()
            if (current.removeAll { it.equals(word, ignoreCase = true) }) save(current)
            markDeleted(word)
        }
    }

    /** "New week": the given list replaces the old one on every device. */
    fun startNewWeek(words: List<String>) {
        synchronized(LOCK) {
            Stats.prune(this)
            clearDeletedWords()
            replaceWords(words)
        }
    }

    /** When the local list was last changed (epoch millis). 0 if never. */
    fun wordsUpdatedAt(): Long = prefs.getLong(KEY_WORDS_AT, 0L)

    /** When "New week" last replaced the list (epoch millis). 0 if never. */
    fun wordsReplacedAt(): Long = prefs.getLong(KEY_WORDS_REPLACED_AT, 0L)

    // ---- deleted-word tombstones ----
    // A word removed one at a time (not via "New week") is remembered
    // here so a sync can't resurrect it from another device's older copy of
    // the list — a plain word union has no way to represent a removal.
    //
    // Tombstones alone can't represent putting a word *back*: the server keeps
    // its copy, so a re-added word would be deleted again on the next sync.
    // So each device also journals what it deleted and re-added since its last
    // successful sync; Sync applies the journal on top of the server's
    // tombstones and clears it once the server has the result.

    fun deletedWords(): MutableList<String> {
        val raw = prefs.getString(KEY_DELETED, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { arr.getString(it) }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun writeDeletedWords(list: List<String>) {
        val arr = JSONArray()
        for (w in list) arr.put(w)
        prefs.edit().putString(KEY_DELETED, arr.toString()).apply()
    }

    /** Remember a single word removed via "Delete" so sync won't bring it back. */
    fun markDeleted(word: String) {
        synchronized(LOCK) {
            val cur = deletedWords()
            if (cur.none { it.equals(word, ignoreCase = true) }) {
                cur.add(word)
                writeDeletedWords(cur)
            }
            journal(KEY_PENDING_UNDELETED, remove = word)
            journal(KEY_PENDING_DELETED, add = word)
        }
    }

    /** A word typed or scanned in is no longer considered deleted — here, and
     *  (via the journal) on the server and the other devices at the next sync. */
    fun unmarkDeleted(word: String) {
        synchronized(LOCK) {
            val cur = deletedWords()
            if (cur.removeAll { it.equals(word, ignoreCase = true) }) writeDeletedWords(cur)
            journal(KEY_PENDING_DELETED, remove = word)
            journal(KEY_PENDING_UNDELETED, add = word)
        }
    }

    /** "New week" declares a fresh, authoritative list: old tombstones no longer apply. */
    fun clearDeletedWords() {
        synchronized(LOCK) {
            writeDeletedWords(emptyList())
            clearJournal()
        }
    }

    /** Words deleted here since the last successful sync. */
    fun pendingDeleted(): List<String> = readList(KEY_PENDING_DELETED) ?: deletedWords()

    /** Words re-added here since the last successful sync. */
    fun pendingUndeleted(): List<String> = readList(KEY_PENDING_UNDELETED) ?: emptyList()

    /** The server now holds these changes: drop them from the journal. A word
     *  deleted or re-added again while the sync ran stays in, because it moved
     *  between the two lists after the snapshot was taken. */
    fun clearJournal(syncedDeleted: List<String>, syncedUndeleted: List<String>) {
        synchronized(LOCK) {
            val d = pendingDeleted().filter { w -> syncedDeleted.none { it.equals(w, ignoreCase = true) } }
            val u = pendingUndeleted().filter { w -> syncedUndeleted.none { it.equals(w, ignoreCase = true) } }
            writeList(KEY_PENDING_DELETED, d)
            writeList(KEY_PENDING_UNDELETED, u)
        }
    }

    fun clearJournal() {
        writeList(KEY_PENDING_DELETED, emptyList())
        writeList(KEY_PENDING_UNDELETED, emptyList())
    }

    private fun journal(key: String, add: String? = null, remove: String? = null) {
        // Before the first sync with this version there is no journal yet: the
        // local tombstones themselves are the pending deletes (the old behaviour).
        val cur = (readList(key) ?: if (key == KEY_PENDING_DELETED) deletedWords() else emptyList()).toMutableList()
        if (remove != null) cur.removeAll { it.equals(remove, ignoreCase = true) }
        if (add != null && cur.none { it.equals(add, ignoreCase = true) }) cur.add(add)
        writeList(key, cur)
    }

    private fun readList(key: String): List<String>? {
        val raw = prefs.getString(key, null) ?: return null
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { arr.getString(it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun writeList(key: String, list: List<String>) {
        val arr = JSONArray()
        for (w in list) arr.put(w)
        prefs.edit().putString(key, arr.toString()).apply()
    }

    fun saveDeletedWordsFromSync(list: List<String>) = writeDeletedWords(list)

    // ---- game limits (see PlayLimit) ----
    // Plays are only counted while a game is limited, and each count belongs to
    // one week's list (wordsReplacedAt), so a new week starts it over.

    /** Rounds allowed per week's list; 0 = unlimited. */
    fun playLimit(game: String): Int = prefs.getInt(KEY_LIMIT + game, 0)

    fun setPlayLimit(game: String, limit: Int) {
        prefs.edit().putInt(KEY_LIMIT + game, limit).apply()
    }

    private fun playsUsed(game: String): Int =
        if (prefs.getLong(KEY_PLAYS_WEEK + game, -1L) == wordsReplacedAt()) prefs.getInt(KEY_PLAYS_USED + game, 0) else 0

    /** Rounds left this week, or null when the game is unlimited. */
    fun playsLeft(game: String): Int? {
        val limit = playLimit(game)
        return if (limit == 0) null else (limit - playsUsed(game)).coerceAtLeast(0)
    }

    fun usePlay(game: String) {
        if (playLimit(game) == 0) return
        prefs.edit()
            .putLong(KEY_PLAYS_WEEK + game, wordsReplacedAt())
            .putInt(KEY_PLAYS_USED + game, playsUsed(game) + 1)
            .apply()
    }

    // ---- parent PIN (see ParentLock); a hash, never the PIN itself ----

    fun parentPinHash(): String? = prefs.getString(KEY_PIN, null)

    fun setParentPinHash(hash: String?) {
        prefs.edit().apply { if (hash == null) remove(KEY_PIN) else putString(KEY_PIN, hash) }.apply()
    }

    // ---- review stats ("Words to review") ----

    fun stats(): MutableMap<String, Stats.Entry> = Stats.fromJson(prefs.getString(KEY_STATS, null))

    fun saveStats(map: Map<String, Stats.Entry>) {
        prefs.edit()
            .putString(KEY_STATS, Stats.toJson(map))
            .putLong(KEY_STATS_AT, System.currentTimeMillis())
            .apply()
    }

    fun saveStatsFromSync(map: Map<String, Stats.Entry>, updatedAt: Long) {
        prefs.edit()
            .putString(KEY_STATS, Stats.toJson(map))
            .putLong(KEY_STATS_AT, updatedAt)
            .apply()
    }

    fun statsUpdatedAt(): Long = prefs.getLong(KEY_STATS_AT, 0L)

    /** When this device last completed a sync (epoch millis). 0 if never. */
    fun lastSyncedAt(): Long = prefs.getLong(KEY_SYNCED_AT, 0L)

    fun markSyncedNow() {
        prefs.edit().putLong(KEY_SYNCED_AT, System.currentTimeMillis()).apply()
    }

    fun familyCode(): String = prefs.getString(KEY_CODE, "") ?: ""

    fun setFamilyCode(code: String) {
        prefs.edit().putString(KEY_CODE, code).apply()
    }

    fun rate(): Float = prefs.getFloat(KEY_RATE, 0.9f)

    fun setRate(r: Float) {
        prefs.edit().putFloat(KEY_RATE, r).apply()
    }

    /** Sample words shown on first launch, before the parent enters their own. */
    private fun defaultWords(): MutableList<String> = mutableListOf(
        "because", "friend", "said", "would", "people", "school", "Wednesday", "beautiful"
    )

    companion object {
        /**
         * Guards read-modify-write of the stored list, tombstones and stats.
         * Sync merges on a background thread while screens keep editing on the
         * main thread; every WordStore instance shares this one lock.
         */
        val LOCK = Any()

        private const val KEY_WORDS = "words"
        private const val KEY_WORDS_AT = "words_updated_at"
        private const val KEY_WORDS_REPLACED_AT = "words_replaced_at"
        private const val KEY_DELETED = "deleted_words"
        private const val KEY_PENDING_DELETED = "pending_deleted"
        private const val KEY_PENDING_UNDELETED = "pending_undeleted"
        private const val KEY_STATS = "review_stats"
        private const val KEY_STATS_AT = "review_stats_at"
        private const val KEY_SYNCED_AT = "last_synced_at"
        private const val KEY_CODE = "family_code"
        private const val KEY_RATE = "rate"
        private const val KEY_LIMIT = "play_limit_"
        private const val KEY_PIN = "parent_pin_hash"
        private const val KEY_PLAYS_WEEK = "plays_week_"
        private const val KEY_PLAYS_USED = "plays_used_"
    }
}
