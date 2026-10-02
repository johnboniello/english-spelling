package com.johnb.englishspelling

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)
        padForSystemBars()
        supportActionBar?.title = "Scan a List"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        store = WordStore(this)
        reviewField = findViewById(R.id.reviewField)
        progress = findViewById(R.id.progress)
        hintText = findViewById(R.id.hintText)

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
                    val found = extractWords(result.text)
                    if (found.isEmpty()) {
                        hintText.text =
                            "No words found. Try again with more light and the page flat."
                    } else {
                        hintText.text = "Check and fix them, then tap “Add to the list”."
                        val existing = reviewField.text.toString().trim()
                        val merged = (if (existing.isEmpty()) emptyList() else existing.split("\n")) + found
                        reviewField.setText(merged.map { it.trim() }.filter { it.isNotEmpty() }
                            .distinctBy { it.lowercase() }.joinToString("\n"))
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
                if (part.length in 1..30 && part.any { it.isLetter() } && part.none { it.isDigit() }) {
                    out.add(part)
                }
            }
        }
        return out.distinctBy { it.lowercase() }
    }

    private fun addWords() {
        val parts = reviewField.text.toString().split("\n")
            .map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) {
            toast("Nothing to add.")
            return
        }
        var added = 0
        synchronized(WordStore.LOCK) {   // a background sync may be merging the list
            val current = store.words()
            for (p in parts) {
                if (current.none { it.equals(p, ignoreCase = true) }) {
                    current.add(p)
                    added++
                }
                // A word re-scanned after being individually deleted in a past
                // week must lose its tombstone, or the next sync's merge will
                // filter it right back out (union minus deleted).
                store.unmarkDeleted(p)
            }
            store.save(current)
        }
        toast(if (added == 0) "Those words are already in the list." else "$added word(s) added.")
        finish()
    }

    private fun replaceWords() {
        val parts = reviewField.text.toString().split("\n")
            .map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) {
            toast("Nothing to add.")
            return
        }
        val uniq = ArrayList<String>()
        val seen = HashSet<String>()
        for (p in parts) if (seen.add(p.lowercase())) uniq.add(p)

        AlertDialog.Builder(this)
            .setTitle("New week")
            .setMessage("Replace the list with these ${uniq.size} words?\n\n“Words to review” are kept.")
            .setPositiveButton("Replace") { _, _ ->
                Stats.prune(store)
                store.clearDeletedWords()
                store.replaceWords(uniq)
                toast("New list: ${uniq.size} word(s).")
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
