package com.readingnotes.app.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

fun ImageProxy.toJpegBytes(): ByteArray {
    val jpegBytes = when (format) {
        ImageFormat.JPEG -> planes[0].buffer.toByteArray()
        else -> {
            val nv21 = toNv21()
            ByteArrayOutputStream().use { out ->
                YuvImage(nv21, ImageFormat.NV21, width, height, null).compressToJpeg(
                    Rect(0, 0, width, height),
                    100,
                    out,
                )
                out.toByteArray()
            }
        }
    }
    if (imageInfo.rotationDegrees == 0) return jpegBytes

    val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        ?: error("Failed to decode captured JPEG")
    val rotated = Bitmap.createBitmap(
        bitmap,
        0,
        0,
        bitmap.width,
        bitmap.height,
        Matrix().apply { postRotate(imageInfo.rotationDegrees.toFloat()) },
        true,
    )
    return ByteArrayOutputStream().use { out ->
        rotated.compress(Bitmap.CompressFormat.JPEG, 100, out)
        out.toByteArray()
    }
}

private fun java.nio.ByteBuffer.toByteArray(): ByteArray {
    val bytes = ByteArray(remaining())
    duplicate().apply { rewind() }.get(bytes)
    return bytes
}

private fun ImageProxy.toNv21(): ByteArray {
    val yPlane = planes[0].buffer.duplicate().apply { rewind() }
    val uPlane = planes[1].buffer.duplicate().apply { rewind() }
    val vPlane = planes[2].buffer.duplicate().apply { rewind() }

    val ySize = yPlane.remaining()
    val uSize = uPlane.remaining()
    val vSize = vPlane.remaining()
    val nv21 = ByteArray(ySize + uSize + vSize)

    yPlane.get(nv21, 0, ySize)

    val chromaRowStride = planes[1].rowStride
    val chromaPixelStride = planes[1].pixelStride
    val chromaHeight = height / 2
    val chromaWidth = width / 2
    val uBuffer = planes[1].buffer.duplicate().apply { rewind() }
    val vBuffer = planes[2].buffer.duplicate().apply { rewind() }

    var offset = ySize
    val uBytes = ByteArray(uSize)
    val vBytes = ByteArray(vSize)
    uBuffer.get(uBytes)
    vBuffer.get(vBytes)
    for (row in 0 until chromaHeight) {
        val rowOffset = row * chromaRowStride
        for (col in 0 until chromaWidth) {
            val index = rowOffset + col * chromaPixelStride
            nv21[offset++] = vBytes[index]
            nv21[offset++] = uBytes[index]
        }
    }
    return nv21
}
