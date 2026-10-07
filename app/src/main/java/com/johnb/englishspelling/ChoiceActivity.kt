package com.johnb.englishspelling

import android.content.res.ColorStateList
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

/** "Pick the Spelling" — hear the word, pick the correct spelling out of four. */
class ChoiceActivity : AppCompatActivity() {

    private lateinit var store: WordStore
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private var words: MutableList<String> = mutableListOf()
    private var order: MutableList<Int> = mutableListOf()
    private var pos = 0
    private var score = 0
    private var aided = 0

    private lateinit var progressView: TextView
    private lateinit var instructionView: TextView
    private lateinit var emptyView: TextView
    private lateinit var listenBtn: Button
    private lateinit var feedbackView: TextView
    private lateinit var nextBtn: Button
    private val options = ArrayList<Button>(4)

    private var correctIndex = -1
    private var solved = false
    private var wrongThisWord = false

    private val reviewMode by lazy { intent.getBooleanExtra(HomeActivity.EXTRA_REVIEW, false) }

    private val optIdle = 0xFFB5D4F4.toInt()
    private val optIdleText = 0xFF042C53.toInt()
    private val green = 0xFF2E7D32.toInt()
    private val red = 0xFFC62828.toInt()
    private val onStrong = 0xFFFFFFFF.toInt()

    companion object {
        private const val KEY_WORDS = "state_words"
        private const val KEY_ORDER = "state_order"
        private const val KEY_POS = "state_pos"
        private const val KEY_SCORE = "state_score"
        private const val KEY_AIDED = "state_aided"
        private const val KEY_SOLVED = "state_solved"
        private const val KEY_WRONG = "state_wrong"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_choice)
        padForSystemBars()
        supportActionBar?.title = if (reviewMode) "Pick the Spelling — review" else "Pick the Spelling"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        store = WordStore(this)
        progressView = findViewById(R.id.progressView)
        instructionView = findViewById(R.id.instructionView)
        emptyView = findViewById(R.id.emptyView)
        listenBtn = findViewById(R.id.listenBtn)
        feedbackView = findViewById(R.id.feedbackView)
        nextBtn = findViewById(R.id.nextBtn)
        options.add(findViewById(R.id.opt0))
        options.add(findViewById(R.id.opt1))
        options.add(findViewById(R.id.opt2))
        options.add(findViewById(R.id.opt3))

        listenBtn.setOnClickListener { speakWord() }
        findViewById<Button>(R.id.slowBtn).setOnClickListener {
            if (order.isNotEmpty()) Voice.speakSlow(tts, ttsReady, currentWord(), store.rate())
        }
        nextBtn.setOnClickListener { nextWord() }
        options.forEachIndexed { i, b -> b.setOnClickListener { onPick(i) } }

        if (savedInstanceState != null) restoreRoundState(savedInstanceState)

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale.US)
                ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                if (ttsReady) runOnUiThread { if (order.isNotEmpty() && !solved) speakWord() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (order.isNotEmpty() && reviewMode) return
        val latest = if (reviewMode) Stats.poolWords(store).toMutableList() else store.words()
        if (latest != words || order.isEmpty()) {
            words = latest
            if (words.isEmpty()) {
                if (reviewMode) emptyView.text = "No words to review right now. 🎉"
                showEmpty(true)
                order = mutableListOf()
                return
            }
            showEmpty(false)
            order = MutableList(words.size) { it }
            order.shuffle()
            pos = 0
            score = 0
            aided = 0
            setupRound()
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
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
            outState.putInt(KEY_AIDED, aided)
            // Without these a rotation forgets a wrong pick (the miss never
            // reaches "Words to review") and re-asks a solved word (scored twice).
            outState.putBoolean(KEY_SOLVED, solved)
            outState.putBoolean(KEY_WRONG, wrongThisWord)
        }
    }

    /** Restores the in-progress round (word position, order, score) saved by
     *  [onSaveInstanceState] and rebuilds the four answer choices for the
     *  current word (a fresh distractor set — whichever option she'd already
     *  picked wrong for that one word is not restorable, but the round
     *  position and score are). */
    private fun restoreRoundState(state: Bundle) {
        val savedWords = state.getStringArrayList(KEY_WORDS) ?: return
        val savedOrder = state.getIntegerArrayList(KEY_ORDER) ?: return
        val savedPos = state.getInt(KEY_POS, 0)
        if (savedOrder.isEmpty() || savedOrder.any { it !in savedWords.indices } || savedPos !in savedOrder.indices) return

        words = savedWords
        order = savedOrder
        pos = savedPos
        score = state.getInt(KEY_SCORE, 0)
        aided = state.getInt(KEY_AIDED, 0)
        showEmpty(false)
        setupRound()
        wrongThisWord = state.getBoolean(KEY_WRONG, false)
        if (state.getBoolean(KEY_SOLVED, false)) showSolved()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showEmpty(empty: Boolean) {
        emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        instructionView.visibility = if (empty) View.GONE else View.VISIBLE
        for (b in options) b.visibility = if (empty) View.GONE else View.VISIBLE
        findViewById<View>(R.id.listenRow).visibility = if (empty) View.GONE else View.VISIBLE
        if (empty) {
            progressView.text = ""
            feedbackView.text = ""
            nextBtn.visibility = View.GONE
        }
    }

    private fun currentWord(): String = words[order[pos]]

    private fun setupRound() {
        solved = false
        wrongThisWord = false
        feedbackView.text = ""
        nextBtn.visibility = View.GONE

        val target = currentWord()
        val choices = (SpellingVariants.distractors(target, 3) + target).distinct().toMutableList()
        while (choices.size < 4) choices.add(target + "s".repeat(choices.size))
        choices.shuffle()
        correctIndex = choices.indexOf(target)
        if (correctIndex < 0) {
            choices[0] = target
            correctIndex = 0
        }

        for (i in options.indices) {
            options[i].text = choices[i]
            options[i].isEnabled = true
            options[i].backgroundTintList = ColorStateList.valueOf(optIdle)
            options[i].setTextColor(optIdleText)
        }
        progressView.text = "Word ${pos + 1} / ${order.size}     Score: $score" +
            (if (aided > 0) "   ·   with help: $aided" else "")
        if (ttsReady) speakWord()
    }

    /** Marks the current word solved on screen, without scoring or recording it. */
    private fun showSolved() {
        solved = true
        options[correctIndex].backgroundTintList = ColorStateList.valueOf(green)
        options[correctIndex].setTextColor(onStrong)
        for (b in options) b.isEnabled = false
        feedbackView.setTextColor(green)
        feedbackView.text = if (wrongThisWord) "Great job! (with help)" else "Great job! 🎉"
        nextBtn.visibility = View.VISIBLE
    }

    private fun onPick(i: Int) {
        if (solved || !options[i].isEnabled) return
        if (i == correctIndex) {
            showSolved()
            if (wrongThisWord) aided++ else score++
            progressView.text = "Word ${pos + 1} / ${order.size}     Score: $score" +
                (if (aided > 0) "   ·   with help: $aided" else "")
            Stats.record(store, currentWord(), wrongThisWord)
            Feedback.correct(this, tts, ttsReady)
            Celebrate.correct(this)
        } else {
            wrongThisWord = true
            options[i].isEnabled = false
            options[i].backgroundTintList = ColorStateList.valueOf(red)
            options[i].setTextColor(onStrong)
            feedbackView.setTextColor(red)
            feedbackView.text = "Try again."
            Feedback.wrong(this, tts, ttsReady)
            Celebrate.reset()
        }
    }

    private fun nextWord() {
        if (pos + 1 >= order.size) {
            AlertDialog.Builder(this)
                .setTitle("All done!")
                .setMessage("Without help: $score / ${order.size}\nWith help: $aided")
                .setPositiveButton("Play again") { _, _ ->
                    order.shuffle()
                    pos = 0
                    score = 0
                    aided = 0
                    setupRound()
                }
                .setNegativeButton("Back", null)
                .setCancelable(false)
                .show()
            return
        }
        pos++
        setupRound()
    }

    private fun speakWord() {
        val t = tts ?: return
        if (!ttsReady || order.isEmpty()) return
        t.setSpeechRate(store.rate())
        t.speak(currentWord(), TextToSpeech.QUEUE_FLUSH, null, "w")
    }
}
