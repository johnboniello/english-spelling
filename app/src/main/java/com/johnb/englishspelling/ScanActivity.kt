package com.johnb.englishspelling

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File

/** Take a photo of a word list, run on-device OCR, review, and add to the list. */
class ScanActivity : AppCompatActivity() {

    private lateinit var store: WordStore
    private lateinit var reviewField: EditText
    private lateinit var progress: ProgressBar
    private lateinit var hintText: TextView
    private lateinit var countText: TextView
    private var photoUri: Uri? = null

    private val takePhoto =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            val uri = photoUri
            if (ok && uri != null) {
                cropPhoto.launch(Intent(this, CropActivity::class.java).setData(uri))
            } else {
                hintText.text = "Photo cancelled."
            }
        }

    // The photo is cropped first so stray words around the list never reach the OCR.
    private val cropPhoto =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val cropped = res.data?.data
            if (res.resultCode == RESULT_OK && cropped != null) runOcr(cropped)
            else hintText.text = "Photo cancelled."
        }

    private val camPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchCamera() else toast("Camera access denied.")
        }

    // The camera app runs in its own process; Android may destroy this screen
    // while it is open. Without this the photo comes back to a fresh instance
    // with no URI and is reported as cancelled.
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        photoUri?.let { outState.putParcelable(KEY_PHOTO_URI, it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        photoUri = savedInstanceState?.getParcelable(KEY_PHOTO_URI)
        setContentView(R.layout.activity_scan)
        padForSystemBars()
        supportActionBar?.title = "Scan a List"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        store = WordStore(this)
        reviewField = findViewById(R.id.reviewField)
        progress = findViewById(R.id.progress)
        hintText = findViewById(R.id.hintText)
        countText = findViewById(R.id.countText)
        reviewField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = updateCount()
        })
        updateCount()

        findViewById<Button>(R.id.photoBtn).setOnClickListener { onPhoto() }
        findViewById<Button>(R.id.addBtn).setOnClickListener { addWords() }
        findViewById<Button>(R.id.replaceBtn).setOnClickListener { replaceWords() }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun onPhoto() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            launchCamera()
        } else {
            camPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchCamera() {
        val dir = File(cacheDir, "images").apply { mkdirs() }
        val file = File(dir, "scan_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        photoUri = uri
        try {
            takePhoto.launch(uri)
        } catch (e: Exception) {
            toast("No camera app found.")
        }
    }

    private fun runOcr(uri: Uri) {
        progress.visibility = View.VISIBLE
        hintText.text = "Reading…"
        try {
            val image = InputImage.fromFilePath(this, uri)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    progress.visibility = View.GONE
                    if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) Log.d(TAG, "OCR raw text:\n${result.text}")
                    val found = extractWords(result.text)
                    if (found.isEmpty()) {
                        hintText.text =
                            "No words found. Try again with more light and the page flat."
                    } else {
                        hintText.text = "Check and fix them, then tap “New week”."
                        showScanned(found)
                    }
                }
                .addOnFailureListener { e ->
                    progress.visibility = View.GONE
                    hintText.text = "Couldn't read it: ${e.message}"
                }
        } catch (e: Exception) {
            progress.visibility = View.GONE
            hintText.text = "Couldn't open the image: ${e.message}"
        }
    }

    /** A second photo (a retake, or page two) shouldn't silently pile onto the
     *  first one's words: ask whether to keep them. */
    private fun showScanned(found: List<String>) {
        val existing = fieldWords()
        if (existing.isEmpty()) {
            setField(found)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Found ${found.size} words")
            .setMessage("There are already ${existing.size} scanned words above. Keep them and add these (for a second page), or replace them (for a retake)?")
            .setPositiveButton("Replace them") { _, _ -> setField(found) }
            .setNeutralButton("Keep both") { _, _ -> setField(existing + found) }
            .setCancelable(false)
            .show()
    }

    private fun setField(words: List<String>) {
        reviewField.setText(words.map { it.trim() }.filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }.joinToString("\n"))
    }

    private fun fieldWords(): List<String> =
        reviewField.text.toString().split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }

    private fun updateCount() {
        val n = fieldWords().size
        countText.text = when (n) {
            0 -> ""
            1 -> "1 word"
            else -> "$n words"
        }
    }

    /** Best-effort: one candidate word per line, list markers and stray punctuation removed. */
    private fun extractWords(raw: String): List<String> {
        val out = ArrayList<String>()
        for (lineRaw in raw.split("\n")) {
            var line = lineRaw.trim()
            if (line.isEmpty()) continue
            line = line.replace(Regex("^\\s*(\\d+\\s*[.)\\-–]|[-*•·–])\\s*"), "")
            for (partRaw in line.split(Regex("[,;/|]|\\s{2,}|\\s-\\s"))) {
                val part = partRaw.trim()
                    .trim('.', ',', ';', ':', '"', '\'', '(', ')', '!', '?', '·', '–', '-', '_')
                // A lone letter is almost always a list number OCR misread ("1." -> "l");
                // more than three words is a sentence or a heading, not a spelling word.
                val lone = part.length == 1 && part != "a" && part != "A" && part != "I"
                val sentence = part.split(Regex("\\s+")).size > 3
                if (part.length in 1..30 && part.any { it.isLetter() } && part.none { it.isDigit() } &&
                    !lone && !sentence
                ) {
                    out.add(part)
                }
            }
        }
        return out.distinctBy { it.lowercase() }
    }

    /** Appends to the current list. Says what the total will be first, because
     *  adding a new week's scan to last week's list doubles it. */
    private fun addWords() {
        val parts = fieldWords()
        if (parts.isEmpty()) {
            toast("Nothing to add.")
            return
        }
        val current = store.words()
        val newOnes = parts.count { p -> current.none { it.equals(p, ignoreCase = true) } }
        if (newOnes == 0) {
            toast("Those words are already in the list.")
            return
        }
        if (current.isEmpty()) {
            doAdd(parts)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Add to this week's list?")
            .setMessage("The list already has ${current.size} words. Adding these $newOnes makes ${current.size + newOnes}.\n\nIf these are a new week's words, use New week instead: it replaces last week's list.")
            .setPositiveButton("Add") { _, _ -> doAdd(parts) }
            .setNeutralButton("New week instead") { _, _ -> replaceWords() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doAdd(parts: List<String>) {
        val added = store.addWords(parts)
        toast(if (added == 0) "Those words are already in the list." else "$added word(s) added.")
        finish()
    }

    private fun replaceWords() {
        val uniq = fieldWords()
        if (uniq.isEmpty()) {
            toast("Nothing to add.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("New week")
            .setMessage("Replace the list with these ${uniq.size} words?\n\n“Words to review” are kept.")
            .setPositiveButton("Replace") { _, _ ->
                store.startNewWeek(uniq)
                toast("New list: ${uniq.size} word(s).")
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private companion object {
        const val KEY_PHOTO_URI = "photo_uri"
        const val TAG = "SpellingScan"
    }
}
