package com.johnb.englishspelling

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import kotlin.random.Random

/**
 * Optional cross-device sync, keyed by a "family code" — no accounts, no
 * personal data. Syncs two things under the same code:
 *   /list/<code>   the current word list (+ a "new week" generation marker)
 *   /stats/<code>  the "Words to review" practice record
 * Talks to the Cloudflare Worker in the french-dictee repo (`worker/`), shared
 * with Dictée FR; see SERVER_PREFIX.
 * Runs on a background thread; the result is delivered on the main thread.
 */
object Sync {

    /** No trailing slash. Empty = feature hidden. */
    const val SYNC_BASE_URL = "https://dictee-sync.johnboniello.workers.dev"

    /** Same worker as Dictée FR. Every code is stored under "en-<code>" so an
     *  English list never merges with a French list that has the same code.
     *  The worker caps keys at 40 chars, so codes here are at most 37. */
    private const val SERVER_PREFIX = "en-"

    val isConfigured: Boolean get() = SYNC_BASE_URL.isNotBlank()

    sealed class Result {
        data class Ok(val message: String) : Result()
        data class Error(val message: String) : Result()
    }

    private val main = Handler(Looper.getMainLooper())

    private val ADJ = listOf(
        "blue", "red", "green", "gold", "pink", "gray", "brown", "black",
        "tiny", "big", "happy", "wise", "quick", "calm", "brave", "sunny"
    )
    private val NOUN = listOf(
        "owl", "cat", "dog", "lion", "bear", "wolf", "deer", "fox",
        "apple", "pear", "plum", "rose", "tree", "book", "chalk", "pen"
    )

    private const val ALNUM = "abcdefghijklmnopqrstuvwxyz0123456789"
    private val secure = SecureRandom()

    /** A readable prefix so a parent recognises "their" code, plus 6 random
     *  chars so it isn't guessable: e.g. "owl-blue-h7k2m9" (~40 bits). */
    fun newCode(): String {
        val a = ADJ[Random.nextInt(ADJ.size)]
        val n = NOUN[Random.nextInt(NOUN.size)]
        val rand = buildString { repeat(6) { append(ALNUM[secure.nextInt(ALNUM.length)]) } }
        return "$n-$a-$rand"
    }

    fun normalizeCode(raw: String): String =
        raw.trim().lowercase().replace(Regex("[^a-z0-9-]"), "")

    fun isValidCode(code: String): Boolean = Regex("^[a-z0-9-]{8,37}$").matches(code)

    /**
     * Sync triggered by app-resume or pull-to-refresh rather than a button
     * tap: no-ops (returns false, never calls [onResult]) if syncing isn't
     * configured, no family code has been saved yet, or — for the passive
     * resume case — we already synced within [minIntervalMs]. Pass 0 to
     * force it, e.g. for an explicit pull-to-refresh gesture.
     */
    fun autoSync(store: WordStore, minIntervalMs: Long, onResult: (Result) -> Unit): Boolean {
        val code = store.familyCode()
        if (!isConfigured || !isValidCode(code)) return false
        if (minIntervalMs > 0 && System.currentTimeMillis() - store.lastSyncedAt() < minIntervalMs) return false
        syncAll(store, code, onResult)
        return true
    }

    /** Pull + merge + push both list and stats. One background pass, one result. */
    fun syncAll(store: WordStore, code: String, onResult: (Result) -> Unit) {
        Thread {
            val r = try {
                val listMsg = syncList(store, code)
                val statsNote = syncStats(store, code)
                store.markSyncedNow()
                Result.Ok("Up to date: $listMsg." + (if (statsNote != null) " $statsNote" else ""))
            } catch (e: Exception) {
                Result.Error(e.message ?: "Network error")
            }
            main.post { onResult(r) }
        }.start()
    }

    // ---- list ----

    private fun syncList(store: WordStore, code: String): String {
        val now = System.currentTimeMillis()

        // Fetch first, then read local state and merge+save under the lock.
        // Reading local before the network call let a word added during the
        // request get overwritten by the merged list built without it.
        val remote = httpGet("$SYNC_BASE_URL/list/$SERVER_PREFIX$code")
        if (remote == null || !remote.has("words")) {
            val local: List<String>
            val localDeleted: List<String>
            val localRep: Long
            val pendingDel: List<String>
            val pendingUndel: List<String>
            synchronized(WordStore.LOCK) {
                local = store.words()
                localDeleted = store.deletedWords()
                localRep = store.wordsReplacedAt()
                pendingDel = store.pendingDeleted()
                pendingUndel = store.pendingUndeleted()
            }
            httpPut("$SYNC_BASE_URL/list/$SERVER_PREFIX$code", JSONObject().apply {
                put("words", JSONArray(local))
                put("deleted", JSONArray(localDeleted))
                put("updatedAt", now)
                put("replacedAt", localRep)
            })
            store.clearJournal(pendingDel, pendingUndel)
            return "sent ${local.size} word(s)"
        }

        val remoteWords = jsonToList(remote.optJSONArray("words"))
        val remoteDeleted = jsonToList(remote.optJSONArray("deleted"))
        val remoteRep = remote.optLong("replacedAt", 0L)

        val local: List<String>
        val merged: List<String>
        val mergedDeleted: List<String>
        val replacedAt: Long
        val pendingDel: List<String>
        val pendingUndel: List<String>
        var adopted = false
        synchronized(WordStore.LOCK) {
            local = store.words()
            val localRep = store.wordsReplacedAt()
            val localDeleted = store.deletedWords()
            pendingDel = store.pendingDeleted()
            pendingUndel = store.pendingUndeleted()
            when {
                remoteRep > localRep -> {        // other device started a new week: adopt it whole
                    merged = remoteWords
                    mergedDeleted = remoteDeleted
                    replacedAt = remoteRep
                    adopted = true
                }
                localRep > remoteRep -> {        // this device started a new week: its list wins
                    merged = local
                    mergedDeleted = localDeleted
                    replacedAt = localRep
                }
                else -> {                        // same generation -> union, minus what's deleted.
                    // The server's tombstones are the shared truth; on top of them go
                    // the deletes made here since the last sync, and out come the
                    // words re-added here. A stale local tombstone no longer counts,
                    // so a word re-added on any device stays added everywhere.
                    val undeletedKeys = pendingUndel.map { it.lowercase() }.toHashSet()
                    mergedDeleted = union(remoteDeleted.filter { it.lowercase() !in undeletedKeys }, pendingDel)
                    val deletedKeys = mergedDeleted.map { it.lowercase() }.toHashSet()
                    merged = union(local, remoteWords).filter { it.lowercase() !in deletedKeys }
                    replacedAt = localRep
                }
            }

            store.saveFromSync(merged, now, replacedAt)
            store.saveDeletedWordsFromSync(mergedDeleted)
        }
        httpPut("$SYNC_BASE_URL/list/$SERVER_PREFIX$code", JSONObject().apply {
            put("words", JSONArray(merged))
            put("deleted", JSONArray(mergedDeleted))
            put("updatedAt", now)
            put("replacedAt", replacedAt)
        })
        // The server has it now. (Adopting another device's new week also
        // retires this device's journal: those edits were to last week's list.)
        store.clearJournal(pendingDel, pendingUndel)

        if (adopted) return "new list: ${merged.size} word(s)"
        val received = merged.count { w -> local.none { it.equals(w, ignoreCase = true) } }
        return if (received > 0) "${merged.size} word(s) (+$received received)" else "${merged.size} word(s)"
    }

    // ---- stats ----

    private fun syncStats(store: WordStore, code: String): String? {
        val now = System.currentTimeMillis()

        val remote = try {
            httpGet("$SYNC_BASE_URL/stats/$SERVER_PREFIX$code")
        } catch (e: Exception) {
            return "review words not synced (server needs updating)"
        }
        val remoteStats = if (remote != null && remote.has("stats"))
            Stats.fromJson(remote.getJSONObject("stats").toString())
        else
            mutableMapOf()

        // Read local stats only now, after the request, so a round finished
        // while it was in flight isn't overwritten by an older snapshot.
        val merged = synchronized(WordStore.LOCK) {
            Stats.merge(store.stats(), remoteStats).also { store.saveStatsFromSync(it, now) }
        }

        return try {
            httpPut("$SYNC_BASE_URL/stats/$SERVER_PREFIX$code", JSONObject().apply {
                put("stats", JSONObject(Stats.toJson(merged)))
                put("updatedAt", now)
            })
            null
        } catch (e: Exception) {
            "review words merged (upload will retry)"
        }
    }

    // ---- helpers ----

    private fun union(primary: List<String>, other: List<String>): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>(primary.size + other.size)
        for (w in primary) if (seen.add(w.lowercase())) out.add(w)
        for (w in other) if (seen.add(w.lowercase())) out.add(w)
        return out
    }

    private fun jsonToList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optString(i).trim()
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }

    /** Blocking GET. Returns null on 404, throws on other failures. */
    private fun httpGet(url: String): JSONObject? {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12000
            readTimeout = 12000
        }
        try {
            val status = c.responseCode
            if (status == 404) return null
            if (status !in 200..299) throw RuntimeException("Server: $status")
            val body = c.inputStream.bufferedReader().use(BufferedReader::readText)
            return JSONObject(body)
        } finally {
            c.disconnect()
        }
    }

    /** Blocking PUT. Throws on non-2xx. */
    private fun httpPut(url: String, body: JSONObject) {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            connectTimeout = 12000
            readTimeout = 12000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val status = c.responseCode
            if (status !in 200..299) throw RuntimeException("Server: $status")
            c.inputStream.bufferedReader().use(BufferedReader::readText)
        } finally {
            c.disconnect()
        }
    }
}
