package com.readingnotes.app.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * Two-image pipeline (DESIGN.md §5): a high-res archive image kept for human
 * proofreading, and a downscaled JPEG sent to the LLM. Spike 1 showed a 768px
 * long edge at JPEG q85 matches full-res accuracy at ~40% of the size.
 */
object ImageProcessing {

    const val OCR_LONG_EDGE = 768
    const val OCR_QUALITY = 85
    const val ARCHIVE_LONG_EDGE = 2000
    const val ARCHIVE_QUALITY = 90

    fun decode(bytes: ByteArray): Bitmap =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: error("Could not decode image")

    /** JPEG bytes for OCR: downscaled to [OCR_LONG_EDGE]. */
    fun toOcrJpeg(src: Bitmap): ByteArray =
        encodeJpeg(scaleLongEdge(src, OCR_LONG_EDGE), OCR_QUALITY)

    /** WEBP bytes for the archive copy: downscaled to [ARCHIVE_LONG_EDGE]. */
    fun toArchiveWebp(src: Bitmap): ByteArray {
        val scaled = scaleLongEdge(src, ARCHIVE_LONG_EDGE)
        val out = ByteArrayOutputStream()
        @Suppress("DEPRECATION")
        scaled.compress(Bitmap.CompressFormat.WEBP, ARCHIVE_QUALITY, out)
        return out.toByteArray()
    }

    private fun scaleLongEdge(src: Bitmap, longEdge: Int): Bitmap {
        val le = maxOf(src.width, src.height)
        if (le <= longEdge) return src
        val s = longEdge.toFloat() / le
        return Bitmap.createScaledBitmap(
            src,
            (src.width * s).roundToInt(),
            (src.height * s).roundToInt(),
            true,
        )
    }

    private fun encodeJpeg(bmp: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }
}
