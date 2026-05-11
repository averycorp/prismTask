package com.averycorp.prismtask.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Helpers for the smart-screenshot-import flow (G).
 *
 * Loads a user-picked image, optionally compresses it down to fit within
 * the backend Vision endpoint's accepted payload, and produces the
 * base64-encoded JPEG string the backend wants.
 *
 * Privacy: raw image bytes never leave this object except via the return
 * value of [encodeForVision]; no logs, no diagnostics events, no caching.
 */
object ScreenshotEncoder {

    // Backend caps the base64 payload at 6MB. We aim for a smaller post-base64
    // string (~3.5MB) to leave room for the JSON envelope and stay well under
    // the FastAPI default request-body limit on slow networks.
    private const val MAX_ENCODED_BYTES = 4 * 1024 * 1024

    // Initial quality target. The encoder steps down quality if the encoded
    // bytes still exceed [MAX_ENCODED_BYTES] after the first pass.
    private const val INITIAL_JPEG_QUALITY = 80

    private const val MIN_JPEG_QUALITY = 30

    // Long-edge cap for the source bitmap. Phone screenshots are commonly
    // 2400px tall — downscaling to 1600px halves the byte budget while
    // staying readable for Claude's vision pipeline.
    private const val MAX_LONG_EDGE_PX = 1600

    /**
     * Result of an encode attempt. [base64] is the raw base64 string suitable
     * for the backend ``image_base64`` field; [mediaType] is always "image/jpeg"
     * since we re-encode through JPEG to control output size.
     */
    data class Encoded(val base64: String, val mediaType: String)

    /**
     * Result type for [encodeForVision].
     */
    sealed interface EncodeResult {
        data class Success(val encoded: Encoded) : EncodeResult

        /** The picked URI could not be opened or decoded as an image. */
        data object UnreadableImage : EncodeResult

        /** Even at minimum quality, the encoded payload exceeds the backend cap. */
        data object TooLarge : EncodeResult
    }

    fun encodeForVision(context: Context, sourceUri: Uri): EncodeResult {
        val bitmap = loadAndDownscale(context, sourceUri) ?: return EncodeResult.UnreadableImage

        var quality = INITIAL_JPEG_QUALITY
        while (true) {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            val bytes = out.toByteArray()
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            if (base64.length <= MAX_ENCODED_BYTES) {
                bitmap.recycle()
                return EncodeResult.Success(Encoded(base64 = base64, mediaType = "image/jpeg"))
            }
            quality -= 10
            if (quality < MIN_JPEG_QUALITY) {
                bitmap.recycle()
                return EncodeResult.TooLarge
            }
        }
    }

    private fun loadAndDownscale(context: Context, sourceUri: Uri): Bitmap? {
        // First pass: bounds-only decode so we can compute the inSampleSize
        // without decoding the full bitmap into RAM.
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            BitmapFactory.decodeStream(input, null, boundsOptions)
        } ?: return null

        if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) return null

        val longEdge = maxOf(boundsOptions.outWidth, boundsOptions.outHeight)
        var sampleSize = 1
        while (longEdge / sampleSize > MAX_LONG_EDGE_PX) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(sourceUri)?.use { input ->
            BitmapFactory.decodeStream(input, null, decodeOptions)
        }
    }
}
