package com.example.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlin.math.roundToInt

/**
 * Turns a picked image [Uri] into the base64 JPEG Gemini takes inline.
 *
 * Downscaled and re-compressed first: a modern phone camera produces several
 * megabytes, base64 adds a third on top, and the whole thing has to go up a
 * mobile connection before any translation can start. [MAX_EDGE_PX] is well
 * above what OCR needs to read a menu or a sign.
 *
 * Returns null for every failure — an unreadable Uri, an undecodable image, a
 * failed re-encode — so callers can say so rather than sending an empty
 * request. Nothing here throws: the Uri comes from another app's picker and can
 * be revoked or deleted between the pick and the read.
 */
internal object AndroidImageLoading {

    private const val MAX_EDGE_PX = 1536
    private const val JPEG_QUALITY = 85
    const val MIME_TYPE = "image/jpeg"

    fun toBase64Jpeg(context: Context, uri: Uri): String? {
        val bitmap = decodeDownscaled(context, uri) ?: return null
        return try {
            val out = ByteArrayOutputStream()
            // A false return leaves `out` empty, and an empty base64 string is
            // not null — the caller would skip its error message and send
            // nothing at all.
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) return null
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Two passes, the standard Android idiom: measure with `inJustDecodeBounds`
     * so the full-size bitmap never has to fit in memory, then decode with a
     * power-of-two `inSampleSize` — followed by an exact scale, because powers
     * of two alone would leave the longest edge anywhere up to just under
     * `2 * MAX_EDGE_PX`.
     */
    private fun decodeDownscaled(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // The measuring pass returns no bitmap by contract, so only the stream
        // may be null-checked here — testing the decode result would abort every
        // image, valid ones included.
        val boundsStream = openStream(context, uri) ?: return null
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }

        val longestEdge = maxOf(bounds.outWidth, bounds.outHeight)
        if (longestEdge <= 0) return null

        var sampleSize = 1
        while (longestEdge / (sampleSize * 2) >= MAX_EDGE_PX) {
            sampleSize *= 2
        }

        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val sampled = openStream(context, uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null

        return scaledToCap(sampled)
    }

    /**
     * Brings the longest edge down to exactly [MAX_EDGE_PX]. Sampling alone
     * stops at the last power of two that stays at or above the cap, so a
     * 3071px photo would otherwise be uploaded untouched.
     */
    private fun scaledToCap(bitmap: Bitmap): Bitmap {
        val longestEdge = maxOf(bitmap.width, bitmap.height)
        if (longestEdge <= MAX_EDGE_PX) return bitmap
        val ratio = MAX_EDGE_PX.toFloat() / longestEdge
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).roundToInt().coerceAtLeast(1),
            (bitmap.height * ratio).roundToInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    /**
     * `openInputStream` throws rather than returning null when the Uri has gone
     * away or the grant has lapsed — a picked photo deleted before it is read,
     * a permission revoked. Both are ordinary outcomes of handing a Uri back
     * from another app, not crashes.
     */
    private fun openStream(context: Context, uri: Uri): InputStream? = try {
        context.contentResolver.openInputStream(uri)
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }
}
