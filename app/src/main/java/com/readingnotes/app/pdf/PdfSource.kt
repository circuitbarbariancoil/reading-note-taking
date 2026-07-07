package com.readingnotes.app.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * Wraps a [PdfRenderer] over a content [Uri]. PdfRenderer only allows one page
 * open at a time and is not thread-safe, so all rendering is serialized behind
 * a [Mutex]. The PDF file itself is never copied into app storage — we only
 * hold a read-only file descriptor while rendering, and [close] releases it.
 */
class PdfSource private constructor(
    private val fd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
) {
    val pageCount: Int get() = renderer.pageCount

    private val mutex = Mutex()

    /** Render page [index] (0-based) into a fresh bitmap scaled to [targetLongEdge]. */
    suspend fun renderBitmap(index: Int, targetLongEdge: Int): Bitmap = mutex.withLock {
        renderer.openPage(index).use { page ->
            val longEdge = maxOf(page.width, page.height).coerceAtLeast(1)
            val scale = targetLongEdge.toFloat() / longEdge
            val w = (page.width * scale).roundToInt().coerceAtLeast(1)
            val h = (page.height * scale).roundToInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            // White background so transparent/vector PDFs don't render as black.
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp
        }
    }

    /** Render page [index] and encode to JPEG bytes, recycling the bitmap. */
    suspend fun renderJpeg(index: Int, targetLongEdge: Int, quality: Int = 92): ByteArray {
        val bmp = renderBitmap(index, targetLongEdge)
        return try {
            ByteArrayOutputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
                out.toByteArray()
            }
        } finally {
            bmp.recycle()
        }
    }

    fun close() {
        runCatching { renderer.close() }
        runCatching { fd.close() }
    }

    companion object {
        fun open(context: Context, uri: Uri): PdfSource {
            val fd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: error("无法打开 PDF 文件")
            return try {
                PdfSource(fd, PdfRenderer(fd))
            } catch (t: Throwable) {
                runCatching { fd.close() }
                throw t
            }
        }
    }
}
