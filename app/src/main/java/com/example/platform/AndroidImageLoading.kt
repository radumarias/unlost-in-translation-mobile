package com.example.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Turns a picked image [Uri] into the base64 JPEG Gemini takes inline.
 *
 * Downscaled and re-compressed first: a modern phone camera produces several
 * megabytes, base64 adds a third on top, and the whole thing has to go up a
 * mobile connection before any translation can start. [MAX_EDGE_PX] is well
 * above what OCR needs to read a menu or a sign.
 *
 * Returns null when the image cannot be decoded, so callers can say so rather
 * than sending an empty request.
 */
internal object AndroidImageLoading {

    private const val MAX_EDGE_PX = 1536
    private const val JPEG_QUALITY = 85
    const val MIME_TYPE = "image/jpeg"

    fun toBase64Jpeg(context: Context, uri: Uri): String? {
        val bitmap = decodeDownscaled(context, uri) ?: return null
        return try {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Two passes, the standard Android idiom: measure with `inJustDecodeBounds`
     * so the full-size bitmap never has to fit in memory, then decode with a
     * power-of-two `inSampleSize`.
     */
    private fun decodeDownscaled(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: return null

        val longestEdge = maxOf(bounds.outWidth, bounds.outHeight)
        if (longestEdge <= 0) return null

        var sampleSize = 1
        while (longestEdge / (sampleSize * 2) >= MAX_EDGE_PX) {
            sampleSize *= 2
        }

        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }
}
