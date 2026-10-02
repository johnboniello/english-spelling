package com.johnb.englishspelling

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/** Parent screen: enter/replace the weekly words, manage "Words to review", sync. */
class WordListActivity : AppCompatActivity() {

    private lateinit var store: WordStore
    private lateinit var input: EditText
    private lateinit var listContainer: LinearLayout
    private lateinit var countView: TextView
    private val items = mutableListOf<String>()

    private lateinit var reviewCard: View
    private lateinit var reviewHeading: TextView
    private lateinit var reviewContainer: LinearLayout

    private lateinit var syncCard: LinearLayout
    private lateinit var codeInput: EditText
    private lateinit var syncBtn: Button
    private lateinit var syncStatus: TextView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private var syncing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_word_list)
        padForSystemBars()
        supportActionBar?.title = "Manage Words"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        store = WordStore(this)
        input = findViewById(R.id.input)
        listContainer = findViewById(R.id.listContainer)
        countView = findViewById(R.id.countView)
        reviewCard = findViewById(R.id.reviewCard)
        reviewHeading = findViewById(R.id.reviewHeading)
        reviewContainer = findViewById(R.id.reviewContainer)

        findViewById<Button>(R.id.addBtn).setOnClickListener { onAdd(replace = false) }
        findViewById<Button>(R.id.replaceBtn).setOnClickListener { onAdd(replace = true) }
        findViewById<Button>(R.id.scanBtn).setOnClickListener {
            startActivity(Intent(this, ScanActivity::class.java))
        }
        findViewById<Button>(R.id.reviewCleanBtn).setOnClickListener {
            val n = Stats.clearMastered(store)
            renderReview()
            toast(if (n > 0) "$n word(s) removed." else "Nothing to remove.")
        }

        swipeRefresh = findViewById(R.id.swipeRefresh)
        swipeRefresh.setOnRefreshListener { onPullToRefresh() }

        setupSync()

        items.clear()
        items.addAll(store.words())
        redraw()
        renderReview()
    }

    override fun onResume() {
        super.onResume()
        val latest = store.words()
        if (latest != items) {
            items.clear()
            items.addAll(latest)
            redraw()
        }
        renderReview()
        autoSyncSilently()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun onAdd(replace: Boolean) {
        val parts = input.text.toString()
            .split(Regex("[\\n,;]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (parts.isEmpty()) {
            toast("Type at least one word.")
            return
        }
        if (replace) {
            AlertDialog.Builder(this)
                .setTitle("New week")
                .setMessage("Replace the list with these ${parts.size} words?\n\n“Words to review” are kept.")
                .setPositiveButton("Replace") { _, _ ->
                    Stats.prune(store)
                    store.clearDeletedWords()
                    val uniq = ArrayList<String>()
                    val seen = HashSet<String>()
                    for (p in parts) if (seen.add(p.lowercase())) uniq.add(p)
                    items.clear()
                    items.addAll(uniq)
                    store.replaceWords(uniq)
                    input.setText("")
                    redraw()
                    renderReview()
                    AlertDialog.Builder(this)
                        .setMessage("New list: ${uniq.size} words.\nWords to review: ${Stats.dueCount(store)}.")
                        .setPositiveButton("OK", null)
                        .show()
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        var added = 0
        for (p in parts) {
            if (items.none { it.equals(p, ignoreCase = true) }) {
                items.add(p)
                added++
            }
            store.unmarkDeleted(p)
        }
        store.save(items)
        input.setText("")
        redraw()
        toast(if (added == 0) "Those words are already in the list." else "$added word(s) added.")
    }

    private fun removeAt(index: Int) {
        val word = items.removeAt(index)
        store.save(items)
        store.markDeleted(word)
        redraw()
    }

    private fun redraw() {
        countView.text = when (items.size) {
            0 -> "No words"
            1 -> "1 word"
            else -> "${items.size} words"
        }
        listContainer.removeAllViews()
        items.forEachIndexed { index, word ->
            listContainer.addView(wordRow(word, deletable = true, index = index))
        }
    }

    private fun renderReview() {
        val list = Stats.listForManage(store)
        reviewCard.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        reviewHeading.text = "🔁  Words to review (${list.size})"
        reviewContainer.removeAllViews()
        for (it in list) {
            reviewContainer.addView(reviewRow(it))
        }
    }

    private fun rowFrame(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(0, dp(6), 0, dp(6))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun wordRow(word: String, deletable: Boolean, index: Int): View {
        val row = rowFrame()
        row.addView(TextView(this).apply {
            text = word
            textSize = 18f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(pinButton(word))
        if (deletable) {
            row.addView(Button(this).apply {
                text = "Delete"
                isAllCaps = false
                setOnClickListener { removeAt(index) }
            })
        }
        return row
    }

    private fun reviewRow(item: Stats.ManageItem): View {
        val row = rowFrame()
        row.addView(TextView(this).apply {
            text = item.text
            textSize = 18f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(this).apply {
            text = Stats.boxDots(item.box)
            textSize = 13f
            setPadding(dp(6), 0, dp(6), 0)
            setTextColor(0xFF0C447C.toInt())
        })
        row.addView(Button(this).apply {
            text = "✓"
            isAllCaps = false
            minWidth = 0
            setOnClickListener {
                Stats.master(store, item.text)
                renderReview()
            }
        })
        row.addView(pinButton(item.text))
        return row
    }

    private fun pinButton(word: String): Button = Button(this).apply {
        text = "📌"
        isAllCaps = false
        minWidth = 0
        alpha = if (Stats.isPinned(store, word)) 1f else 0.35f
        setOnClickListener {
            Stats.setPinned(store, word, !Stats.isPinned(store, word))
            redraw()
            renderReview()
        }
    }

    // ---- Family-code sync -------------------------------------------------

    private fun setupSync() {
        syncCard = findViewById(R.id.syncCard)
        codeInput = findViewById(R.id.codeInput)
        syncBtn = findViewById(R.id.syncBtn)
        syncStatus = findViewById(R.id.syncStatus)

        if (!Sync.isConfigured) {
            syncCard.visibility = View.GONE
            return
        }
        syncCard.visibility = View.VISIBLE
        codeInput.setText(store.familyCode())

        findViewById<Button>(R.id.genCodeBtn).setOnClickListener {
            val existing = Sync.normalizeCode(codeInput.text.toString())
            if (Sync.isValidCode(existing)) {
                AlertDialog.Builder(this)
                    .setTitle("Replace the code?")
                    .setMessage("A new code won't see the list already shared under “$existing”.")
                    .setPositiveButton("New code") { _, _ -> codeInput.setText(Sync.newCode()) }
                    .setNegativeButton("Keep", null)
                    .show()
            } else {
                codeInput.setText(Sync.newCode())
            }
        }

        findViewById<Button>(R.id.shareCodeBtn).setOnClickListener {
            val code = Sync.normalizeCode(codeInput.text.toString())
            if (!Sync.isValidCode(code)) {
                toast("Make a code first.")
                return@setOnClickListener
            }
            store.setFamilyCode(code)
            val msg = "Code for “Spelling EN”: $code\n\n" +
                "Open the app on the other phone → Manage Words → enter this code → Sync now."
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, msg)
                    },
                    "Send the code"
                )
            )
        }

        syncBtn.setOnClickListener { doSync() }
    }

    private fun doSync() {
        val code = Sync.normalizeCode(codeInput.text.toString())
        if (!Sync.isValidCode(code)) {
            setSyncStatus("Invalid code: 8 to 37 letters, numbers or dashes.")
            return
        }
        codeInput.setText(code)
        store.setFamilyCode(code)
        runSync(minIntervalMs = 0L, showErrors = true)
    }

    /** Quiet auto-sync on app resume: only touches the UI on success, and is
     *  skipped if we already synced very recently. */
    private fun autoSyncSilently() {
        if (!Sync.isValidCode(store.familyCode())) return
        runSync(minIntervalMs = 20_000L, showErrors = false)
    }

    private fun onPullToRefresh() {
        if (!Sync.isValidCode(store.familyCode())) {
            swipeRefresh.isRefreshing = false
            return
        }
        runSync(minIntervalMs = 0L, showErrors = true)
    }

    private fun runSync(minIntervalMs: Long, showErrors: Boolean) {
        if (syncing) {
            swipeRefresh.isRefreshing = false
            return
        }
        syncing = true
        syncBtn.isEnabled = false
        if (minIntervalMs == 0L) setSyncStatus("Syncing…")

        val started = Sync.autoSync(store, minIntervalMs) { result ->
            syncing = false
            syncBtn.isEnabled = true
            swipeRefresh.isRefreshing = false
            when (result) {
                is Sync.Result.Ok -> {
                    items.clear()
                    items.addAll(store.words())
                    redraw()
                    renderReview()
                    setSyncStatus(result.message)
                }
                is Sync.Result.Error -> if (showErrors) setSyncStatus("Failed: ${result.message}")
            }
        }
        if (!started) {
            syncing = false
            syncBtn.isEnabled = true
            swipeRefresh.isRefreshing = false
        }
    }

    private fun setSyncStatus(message: String) {
        syncStatus.text = message
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
