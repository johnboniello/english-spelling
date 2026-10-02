package com.johnb.englishspelling

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * Lets the user crop a photo (passed as the intent's data URI) so the OCR only sees the
 * words they want. The crop frame is dragged over a downsampled preview, then the chosen
 * region is cut from the full-resolution original so the text stays sharp for OCR.
 * Returns the cropped image's URI as the result's data, or RESULT_CANCELED.
 */
class CropActivity : AppCompatActivity() {

    private lateinit var cropView: CropImageView
    private lateinit var confirmBtn: Button
    private lateinit var progress: ProgressBar
    private var source: Uri? = null
    private var orientation = ExifInterface.ORIENTATION_NORMAL
    private var rawWidth = 0
    private var rawHeight = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crop)
        padForSystemBars()
        supportActionBar?.title = "Crop the photo"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        cropView = findViewById(R.id.cropView)
        confirmBtn = findViewById(R.id.confirmBtn)
        progress = findViewById(R.id.progress)
        findViewById<Button>(R.id.cancelBtn).setOnClickListener { cancel() }
        confirmBtn.setOnClickListener { confirm() }

        val uri = intent.data
        if (uri == null) {
            cancel()
            return
        }
        source = uri
        loadPreview(uri)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            cancel()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun cancel() {
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun loadPreview(uri: Uri) {
        Thread {
            val preview = try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                rawWidth = bounds.outWidth
                rawHeight = bounds.outHeight
                orientation = contentResolver.openInputStream(uri)?.use {
                    ExifInterface(it).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                    )
                } ?: ExifInterface.ORIENTATION_NORMAL

                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize(max(rawWidth, rawHeight), PREVIEW_SIDE)
                }
                val raw = contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                }
                raw?.let { upright(it, orientation) }
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                progress.visibility = View.GONE
                if (preview == null || rawWidth <= 0 || rawHeight <= 0) {
                    toast("Couldn't open the image.")
                    cancel()
                } else {
                    cropView.setBitmap(preview)
                    confirmBtn.isEnabled = true
                }
            }
        }.start()
    }

    private fun confirm() {
        val uri = source ?: return
        val frac = cropView.cropFraction()
        confirmBtn.isEnabled = false
        progress.visibility = View.VISIBLE
        Thread {
            val out = try {
                cropFromOriginal(uri, frac)
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) {
                null
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (out == null) {
                    progress.visibility = View.GONE
                    confirmBtn.isEnabled = true
                    toast("Couldn't crop the photo.")
                } else {
                    setResult(RESULT_OK, Intent().setData(out))
                    finish()
                }
            }
        }.start()
    }

    /** Cuts [frac] (fractions of the upright image) out of the full-resolution original. */
    @Suppress("DEPRECATION")
    private fun cropFromOriginal(uri: Uri, frac: RectF): Uri? {
        // Maps the file's raw pixels to the upright image; invert it to find the frame in raw pixels.
        val toUpright = orientationMatrix(orientation, rawWidth, rawHeight)
        val uprightBounds = RectF(0f, 0f, rawWidth.toFloat(), rawHeight.toFloat())
        toUpright.mapRect(uprightBounds)
        val frame = RectF(
            frac.left * uprightBounds.width(), frac.top * uprightBounds.height(),
            frac.right * uprightBounds.width(), frac.bottom * uprightBounds.height(),
        )
        val fromUpright = Matrix()
        toUpright.invert(fromUpright)
        fromUpright.mapRect(frame)

        val region = Rect(
            floor(frame.left).toInt().coerceIn(0, rawWidth - 1),
            floor(frame.top).toInt().coerceIn(0, rawHeight - 1),
            ceil(frame.right).toInt().coerceIn(1, rawWidth),
            ceil(frame.bottom).toInt().coerceIn(1, rawHeight),
        )
        if (region.width() < 1 || region.height() < 1) return null

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(max(region.width(), region.height()), OUTPUT_SIDE)
        }
        val decoder = contentResolver.openInputStream(uri)?.use {
            BitmapRegionDecoder.newInstance(it, false)
        } ?: return null
        val cropped = try {
            decoder.decodeRegion(region, opts)
        } finally {
            decoder.recycle()
        } ?: return null

        val result = upright(cropped, orientation)
        val dir = File(cacheDir, "images").apply { mkdirs() }
        val file = File(dir, "crop_${System.currentTimeMillis()}.jpg")
        file.outputStream().use { result.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        return FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    }

    private fun sampleSize(longSide: Int, target: Int): Int {
        var sample = 1
        while (longSide / (sample * 2) >= target) sample *= 2
        return sample
    }

    private fun upright(src: Bitmap, exif: Int): Bitmap {
        val m = orientationMatrix(exif, src.width, src.height)
        return if (m.isIdentity) src else Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /** Matrix turning a raw w×h image into its upright form, shifted to start at (0,0). */
    private fun orientationMatrix(exif: Int, w: Int, h: Int): Matrix {
        val m = Matrix()
        when (exif) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
        }
        val bounds = RectF(0f, 0f, w.toFloat(), h.toFloat())
        m.mapRect(bounds)
        m.postTranslate(-bounds.left, -bounds.top)
        return m
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private companion object {
        const val PREVIEW_SIDE = 2048   // longest side of the on-screen preview
        const val OUTPUT_SIDE = 4096    // never hand OCR more than this many pixels on a side
    }
}
