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

    /** When the local list was last changed (epoch millis). 0 if never. */
    fun wordsUpdatedAt(): Long = prefs.getLong(KEY_WORDS_AT, 0L)

    /** When "New week" last replaced the list (epoch millis). 0 if never. */
    fun wordsReplacedAt(): Long = prefs.getLong(KEY_WORDS_REPLACED_AT, 0L)

    // ---- deleted-word tombstones ----
    // A word removed one at a time (not via "New week") is remembered
    // here so a sync can't resurrect it from another device's older copy of
    // the list — a plain word union has no way to represent a removal.

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
        }
    }

    /** A re-added word is no longer considered deleted. */
    fun unmarkDeleted(word: String) {
        synchronized(LOCK) {
            val cur = deletedWords()
            if (cur.removeAll { it.equals(word, ignoreCase = true) }) writeDeletedWords(cur)
        }
    }

    /** "New week" declares a fresh, authoritative list: old tombstones no longer apply. */
    fun clearDeletedWords() = writeDeletedWords(emptyList())

    fun saveDeletedWordsFromSync(list: List<String>) = writeDeletedWords(list)

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
        private const val KEY_STATS = "review_stats"
        private const val KEY_STATS_AT = "review_stats_at"
        private const val KEY_SYNCED_AT = "last_synced_at"
        private const val KEY_CODE = "family_code"
        private const val KEY_RATE = "rate"
    }
}
