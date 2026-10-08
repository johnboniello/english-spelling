package com.johnb.englishspelling

import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.UnderlineSpan
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.GridLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

/** "Write the Word": listen, write on paper, type it in, get corrected. */
class MainActivity : AppCompatActivity() {

    private lateinit var store: WordStore
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private var words: MutableList<String> = mutableListOf()
    private var order: MutableList<Int> = mutableListOf()
    private var pos = 0
    private var score = 0
    private var aidedCount = 0
    private val guess = StringBuilder()
    private var scoredThisWord = false
    private var recordedThisWord = false
    private var aidedThisWord = false
    private var revealCount = 0
    private var attempts = 0
    private var answerRevealed = false
    private val charge by lazy { RoundCharge(store, PlayLimit.Game.DICTATION) }

    private val reviewMode by lazy { intent.getBooleanExtra(HomeActivity.EXTRA_REVIEW, false) }

    private lateinit var emptyView: TextView
    private lateinit var emptyAddBtn: Button
    private lateinit var practiceBox: View
    private lateinit var progressView: TextView
    private lateinit var listenBtn: Button
    private lateinit var repeatBtn: Button
    private lateinit var hintBtn: Button
    private lateinit var spellBtn: Button
    private lateinit var hintView: TextView
    private lateinit var guessView: TextView
    private lateinit var letterGrid: GridLayout
    private lateinit var backspaceBtn: Button
    private lateinit var clearBtn: Button
    private lateinit var checkBtn: Button
    private lateinit var resultBox: View
    private lateinit var answerRow: View
    private lateinit var targetView: TextView
    private lateinit var yourView: TextView
    private lateinit var summaryView: TextView
    private lateinit var hearAnswerBtn: Button
    private lateinit var retryBtn: Button
    private lateinit var revealAnswerBtn: Button
    private lateinit var nextBtn: Button

    private val green = 0xFF2E7D32.toInt()
    private val red = 0xFFC62828.toInt()
    private val grey = 0xFF9E9E9E.toInt()

    private val revealThreshold = 3

    companion object {
        private const val KEY_WORDS = "state_words"
        private const val KEY_ORDER = "state_order"
        private const val KEY_POS = "state_pos"
        private const val KEY_SCORE = "state_score"
        private const val KEY_AIDED = "state_aided"
        private const val KEY_GUESS = "state_guess"
        private const val KEY_ATTEMPTS = "state_attempts"
        private const val KEY_REVEAL_COUNT = "state_reveal_count"
        private const val KEY_AIDED_WORD = "state_aided_word"
        private const val KEY_REVEALED = "state_revealed"
        private const val KEY_SCORED = "state_scored"
        private const val KEY_RECORDED = "state_recorded"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        padForSystemBars()
        supportActionBar?.title = if (reviewMode) "Write the Word — review" else "Write the Word"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        store = WordStore(this)

        emptyView = findViewById(R.id.emptyView)
        emptyAddBtn = findViewById(R.id.emptyAddBtn)
        practiceBox = findViewById(R.id.practiceBox)
        progressView = findViewById(R.id.progressView)
        listenBtn = findViewById(R.id.listenBtn)
        repeatBtn = findViewById(R.id.repeatBtn)
        hintBtn = findViewById(R.id.hintBtn)
        spellBtn = findViewById(R.id.spellBtn)
        hintView = findViewById(R.id.hintView)
        guessView = findViewById(R.id.guessView)
        letterGrid = findViewById(R.id.letterGrid)
        backspaceBtn = findViewById(R.id.backspaceBtn)
        clearBtn = findViewById(R.id.clearBtn)
        checkBtn = findViewById(R.id.checkBtn)
        resultBox = findViewById(R.id.resultBox)
        answerRow = findViewById(R.id.answerRow)
        targetView = findViewById(R.id.targetView)
        yourView = findViewById(R.id.yourView)
        summaryView = findViewById(R.id.summaryView)
        hearAnswerBtn = findViewById(R.id.hearAnswerBtn)
        retryBtn = findViewById(R.id.retryBtn)
        revealAnswerBtn = findViewById(R.id.revealAnswerBtn)
        nextBtn = findViewById(R.id.nextBtn)

        buildLetterKeys()

        emptyAddBtn.setOnClickListener { openWordList() }
        listenBtn.setOnClickListener { speakWord() }
        findViewById<Button>(R.id.slowBtn).setOnClickListener {
            if (words.isNotEmpty()) Voice.speakSlow(tts, ttsReady, currentWord(), store.rate())
        }
        repeatBtn.setOnClickListener { speakWord() }
        backspaceBtn.setOnClickListener {
            if (guess.isNotEmpty()) {
                guess.deleteCharAt(guess.length - 1)
                refreshGuess()
            }
        }
        clearBtn.setOnClickListener {
            guess.clear()
            refreshGuess()
        }
        checkBtn.setOnClickListener { onCheck() }
        retryBtn.setOnClickListener { resultBox.visibility = View.GONE }
        nextBtn.setOnClickListener { nextWord() }
        hearAnswerBtn.setOnClickListener { speakSpelledOut() }
        revealAnswerBtn.setOnClickListener { revealAnswer() }
        hintBtn.setOnClickListener { revealHint() }
        spellBtn.setOnClickListener {
            if (words.isNotEmpty()) Voice.spellSlowly(tts, ttsReady, currentWord(), store.rate())
        }

        if (savedInstanceState != null) restoreRoundState(savedInstanceState)

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale.US)
                ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                if (!ttsReady) runOnUiThread {
                    toast("No English voice installed. Add one in Settings > System > Languages > Text-to-speech output.")
                }
            } else {
                runOnUiThread { toast("Text-to-speech isn't available on this device.") }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (order.isEmpty() || !reviewMode) {
            val latest = if (reviewMode) Stats.poolWords(store).toMutableList() else store.words()
            if (order.isEmpty() || latest != words) {
                words = latest
                initRound()
            }
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        LetterAudio.release()
        Feedback.release()
        super.onDestroy()
    }

    /** Without this, a rotation or the OS reclaiming a backgrounded activity
     *  recreates a fresh instance with `order` empty, and `onResume` silently
     *  restarts the round from word 1. */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (order.isNotEmpty()) {
            outState.putStringArrayList(KEY_WORDS, ArrayList(words))
            outState.putIntegerArrayList(KEY_ORDER, ArrayList(order))
            outState.putInt(KEY_POS, pos)
            outState.putInt(KEY_SCORE, score)
            outState.putInt(KEY_AIDED, aidedCount)
            outState.putString(KEY_GUESS, guess.toString())
            // Per-word state: without it a rotation forgets the wrong tries (so
            // a missed word is recorded as a clean pass and skips "Words to
            // review") and lets an already-solved word score again.
            outState.putInt(KEY_ATTEMPTS, attempts)
            outState.putInt(KEY_REVEAL_COUNT, revealCount)
            outState.putBoolean(KEY_AIDED_WORD, aidedThisWord)
            outState.putBoolean(KEY_REVEALED, answerRevealed)
            outState.putBoolean(KEY_SCORED, scoredThisWord)
            outState.putBoolean(KEY_RECORDED, recordedThisWord)
            charge.save(outState)
        }
    }

    /** Restores the in-progress round (word position, order, score, current
     *  guess) saved by [onSaveInstanceState]. Falls back to a normal fresh
     *  round (via `onResume`) if the saved state doesn't line up with the
     *  current word list. */
    private fun restoreRoundState(state: Bundle) {
        val savedWords = state.getStringArrayList(KEY_WORDS) ?: return
        val savedOrder = state.getIntegerArrayList(KEY_ORDER) ?: return
        val savedPos = state.getInt(KEY_POS, 0)
        if (savedOrder.isEmpty() || savedOrder.any { it !in savedWords.indices } || savedPos !in savedOrder.indices) return

        words = savedWords
        order = savedOrder
        pos = savedPos
        score = state.getInt(KEY_SCORE, 0)
        aidedCount = state.getInt(KEY_AIDED, 0)
        charge.restore(state)
        guess.append(state.getString(KEY_GUESS, ""))

        emptyView.visibility = View.GONE
        emptyAddBtn.visibility = View.GONE
        practiceBox.visibility = View.VISIBLE
        render()
        attempts = state.getInt(KEY_ATTEMPTS, 0)
        revealCount = state.getInt(KEY_REVEAL_COUNT, 0)
        aidedThisWord = state.getBoolean(KEY_AIDED_WORD, false)
        answerRevealed = state.getBoolean(KEY_REVEALED, false)
        scoredThisWord = state.getBoolean(KEY_SCORED, false)
        recordedThisWord = state.getBoolean(KEY_RECORDED, false)
        updateHintView()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { finish(); true }
        R.id.action_words -> { openWordList(); true }
        R.id.action_rate -> { Voice.rateDialog(this, store, tts); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun openWordList() {
        ParentLock.unlock(this, store) { startActivity(Intent(this, WordListActivity::class.java)) }
    }

    private fun initRound() {
        guess.clear()
        resultBox.visibility = View.GONE
        scoredThisWord = false
        recordedThisWord = false
        aidedThisWord = false
        revealCount = 0
        attempts = 0
        answerRevealed = false
        pos = 0
        score = 0
        aidedCount = 0

        if (words.isEmpty()) {
            emptyView.text =
                if (reviewMode) "No words to review right now. 🎉"
                else "No words yet. Add this week's spelling words to start."
            emptyView.visibility = View.VISIBLE
            emptyAddBtn.visibility = if (reviewMode) View.GONE else View.VISIBLE
            practiceBox.visibility = View.GONE
            return
        }
        if (charge.blocked()) {
            emptyView.text = PlayLimit.blockedText(store, PlayLimit.Game.DICTATION)
            emptyView.visibility = View.VISIBLE
            emptyAddBtn.visibility = View.GONE
            practiceBox.visibility = View.GONE
            order = mutableListOf()
            return
        }
        emptyView.visibility = View.GONE
        emptyAddBtn.visibility = View.GONE
        practiceBox.visibility = View.VISIBLE

        order = MutableList(words.size) { it }
        order.shuffle()
        render()
    }

    private fun currentWord(): String = words[order[pos]]

    private fun render() {
        revealCount = 0
        recordedThisWord = false
        aidedThisWord = false
        attempts = 0
        answerRevealed = false
        progressView.text = progressText()
        refreshGuess()
        updateHintView()
        resultBox.visibility = View.GONE
    }

    private fun progressText(): String =
        "Word ${pos + 1} / ${order.size}     Score: $score" +
            (if (aidedCount > 0) "   ·   with help: $aidedCount" else "")

    private fun refreshGuess() {
        guessView.text = if (guess.isEmpty()) "— — —" else spaced(guess.toString())
    }

    private fun spaced(s: String): String = s.toCharArray().joinToString("  ")

    private fun revealHint() {
        if (words.isEmpty()) return
        val w = currentWord()
        if (revealCount < w.length) revealCount++
        aidedThisWord = true
        updateHintView()
    }

    private fun updateHintView() {
        if (revealCount <= 0 || words.isEmpty()) {
            hintView.visibility = View.GONE
            return
        }
        val w = currentWord()
        val shown = buildString {
            for ((i, c) in w.withIndex()) {
                append(if (i < revealCount) c else '_')
                append(' ')
            }
        }
        hintView.text = "Hint: ${shown.trimEnd()}"
        hintView.visibility = View.VISIBLE
    }

    private fun buildLetterKeys() {
        val keys = ('a'..'z').map { it.toString() } +
            listOf("'", "-", " ")
        val cols = 7
        letterGrid.columnCount = cols
        val m = dp(3)
        for (k in keys) {
            val isSpace = k == " "
            val b = Button(this)
            b.text = if (isSpace) "space" else k
            b.isAllCaps = false
            b.textSize = if (isSpace) 13f else 18f
            b.setPadding(0, dp(6), 0, dp(6))
            b.minWidth = 0
            b.minimumWidth = 0
            val span = if (isSpace) 2 else 1
            val lp = GridLayout.LayoutParams()
            lp.width = 0
            lp.height = GridLayout.LayoutParams.WRAP_CONTENT
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, span, span.toFloat())
            lp.setMargins(m, m, m, m)
            b.layoutParams = lp
            b.setOnClickListener {
                guess.append(k)
                refreshGuess()
                LetterAudio.play(this, tts, ttsReady, k[0])
            }
            letterGrid.addView(b)
        }
    }

    private fun speakWord() {
        val t = tts
        if (t == null || !ttsReady) {
            toast("The voice isn't ready yet.")
            return
        }
        t.setSpeechRate(store.rate())
        t.speak(currentWord(), TextToSpeech.QUEUE_FLUSH, null, "word")
    }

    private fun speakSpelledOut() {
        val t = tts ?: return
        if (!ttsReady) return
        t.setSpeechRate(store.rate())
        t.speak(currentWord(), TextToSpeech.QUEUE_FLUSH, null, "word")
        val letters = currentWord().toCharArray().joinToString(", ") { LetterAudio.nameFor(it) }
        t.speak(letters, TextToSpeech.QUEUE_ADD, null, "spell")
    }

    private fun onCheck() {
        if (guess.isEmpty()) {
            toast("Type your spelling first.")
            return
        }
        charge.charge()
        val res = SpellingChecker.check(currentWord(), guess.toString())
        yourView.text = renderRow(res, targetRow = false)

        if (res.correct) {
            if (!scoredThisWord) {
                if (aidedThisWord || answerRevealed) aidedCount++ else score++
                scoredThisWord = true
            }
            answerRow.visibility = View.VISIBLE
            targetView.text = renderRow(res, targetRow = true)
            summaryView.setTextColor(green)
            summaryView.text =
                if (aidedThisWord || answerRevealed) "Great job! 🎉  (with help)"
                else "Great job! 🎉  Perfect spelling."
            hearAnswerBtn.visibility = View.VISIBLE
            retryBtn.visibility = View.GONE
            revealAnswerBtn.visibility = View.GONE
            nextBtn.visibility = View.VISIBLE
            progressView.text = progressText()
            if (!recordedThisWord) {
                Stats.record(store, currentWord(), attempts > 0 || answerRevealed)
                recordedThisWord = true
            }
            Feedback.correct(this, tts, ttsReady)
            Celebrate.correct(this)
        } else {
            attempts++
            if (!answerRevealed) answerRow.visibility = View.GONE
            summaryView.setTextColor(red)
            summaryView.text = "Try again — fix the red letters."
            hearAnswerBtn.visibility = View.GONE
            retryBtn.visibility = View.VISIBLE
            revealAnswerBtn.visibility =
                if (attempts >= revealThreshold && !answerRevealed) View.VISIBLE else View.GONE
            nextBtn.visibility = View.VISIBLE
            Feedback.wrong(this, tts, ttsReady)
            Celebrate.reset()
        }
        resultBox.visibility = View.VISIBLE
    }

    private fun revealAnswer() {
        answerRevealed = true
        aidedThisWord = true
        answerRow.visibility = View.VISIBLE
        targetView.text = greenWord(currentWord())
        summaryView.setTextColor(0xFF1A1A1A.toInt())
        summaryView.text = "The right answer: ${currentWord()}"
        revealAnswerBtn.visibility = View.GONE
    }

    private fun nextWord() {
        if (!recordedThisWord && attempts > 0) {
            Stats.record(store, currentWord(), true)
            recordedThisWord = true
        }
        if (pos + 1 >= order.size) {
            showEndDialog()
            return
        }
        pos++
        guess.clear()
        scoredThisWord = false
        render()
    }

    private fun showEndDialog() {
        PlayLimit.showEndDialog(this, store, PlayLimit.Game.DICTATION, "All done!",
            "Without help: $score / ${order.size}\nWith help: $aidedCount", "Close") {
            charge.reset()
            initRound()
        }
    }

    private fun greenWord(w: String): CharSequence {
        val sb = SpannableStringBuilder()
        for (c in w) {
            val s = sb.length
            sb.append(c)
            sb.append("  ")
            sb.setSpan(ForegroundColorSpan(green), s, s + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    private fun renderRow(res: SpellingChecker.Result, targetRow: Boolean): CharSequence {
        val sb = SpannableStringBuilder()
        for (op in res.ops) {
            val ch: String
            val color: Int
            var strike = false
            var underline = false
            when (op.kind) {
                SpellingChecker.Kind.MATCH -> {
                    ch = (if (targetRow) op.target else op.guess).toString()
                    color = green
                }
                SpellingChecker.Kind.SUB -> {
                    ch = (if (targetRow) op.target else op.guess).toString()
                    color = red
                }
                SpellingChecker.Kind.MISSING -> {
                    if (targetRow) {
                        ch = op.target.toString(); color = red; underline = true
                    } else {
                        ch = "_"; color = grey
                    }
                }
                SpellingChecker.Kind.EXTRA -> {
                    if (targetRow) {
                        ch = "·"; color = grey
                    } else {
                        ch = op.guess.toString(); color = red; strike = true
                    }
                }
            }
            val start = sb.length
            sb.append(ch)
            sb.append("  ")
            sb.setSpan(ForegroundColorSpan(color), start, start + ch.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (strike) sb.setSpan(StrikethroughSpan(), start, start + ch.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (underline) sb.setSpan(UnderlineSpan(), start, start + ch.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
