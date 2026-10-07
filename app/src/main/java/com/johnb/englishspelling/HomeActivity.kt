package com.johnb.englishspelling

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/** Landing screen: pick a game or manage the word list. */
class HomeActivity : AppCompatActivity() {

    private lateinit var store: WordStore
    private lateinit var countView: TextView
    private lateinit var reviewBtn: Button
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private var syncing = false
    private lateinit var update: InAppUpdate

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        padForSystemBars()
        supportActionBar?.title = "Spelling EN"

        store = WordStore(this)
        countView = findViewById(R.id.countView)
        reviewBtn = findViewById(R.id.reviewBtn)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        swipeRefresh.setOnRefreshListener { sync(force = true) }

        findViewById<Button>(R.id.scrambleBtn).setOnClickListener { open(ScrambleActivity::class.java) }
        findViewById<Button>(R.id.choiceBtn).setOnClickListener { open(ChoiceActivity::class.java) }
        findViewById<Button>(R.id.dictationBtn).setOnClickListener { open(MainActivity::class.java) }
        findViewById<Button>(R.id.wordsBtn).setOnClickListener { open(WordListActivity::class.java) }
        findViewById<Button>(R.id.scanBtn).setOnClickListener { open(ScanActivity::class.java) }
        reviewBtn.setOnClickListener { chooseReviewGame() }

        update = InAppUpdate(this)
        update.checkForUpdate()
    }

    override fun onDestroy() {
        update.stop()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        refreshCounts()
        sync(force = false)
        update.resume()
    }

    private fun refreshCounts() {
        val n = store.words().size
        countView.text = when (n) {
            0 -> "No words in the list"
            1 -> "1 word in the list"
            else -> "$n words in the list"
        }
        val due = Stats.dueCount(store)
        reviewBtn.visibility = if (due == 0) android.view.View.GONE else android.view.View.VISIBLE
        reviewBtn.text = "🔁  Words to review ($due)"
    }

    /** [force] = true for an explicit pull-to-refresh; false for the quiet
     *  auto-sync on app resume, which is skipped if we just synced. */
    private fun sync(force: Boolean) {
        if (syncing) {
            swipeRefresh.isRefreshing = false
            return
        }
        syncing = true
        val started = Sync.autoSync(store, minIntervalMs = if (force) 0L else 20_000L) { result ->
            syncing = false
            swipeRefresh.isRefreshing = false
            if (result is Sync.Result.Ok) refreshCounts()
        }
        if (!started) {
            syncing = false
            swipeRefresh.isRefreshing = false
        }
    }

    private fun chooseReviewGame() {
        val labels = arrayOf("🔤  Scrambled Letters", "🎯  Pick the Spelling", "✏️  Write the Word")
        val classes = arrayOf(
            ScrambleActivity::class.java, ChoiceActivity::class.java, MainActivity::class.java
        )
        AlertDialog.Builder(this)
            .setTitle("Review — how?")
            .setItems(labels) { _, which ->
                startActivity(Intent(this, classes[which]).putExtra(EXTRA_REVIEW, true))
            }
            .show()
    }

    private fun open(cls: Class<*>) = startActivity(Intent(this, cls))

    companion object {
        const val EXTRA_REVIEW = "review_mode"
    }
}
