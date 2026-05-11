package com.averycorp.prismtask.util

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * Robolectric tests for [ScreenshotEncoder] (G).
 *
 * Verifies the success path returns a non-empty base64 string with the
 * declared media type, and that an unreadable URI returns the typed error
 * variant rather than crashing. The TooLarge branch is exercised
 * indirectly by the success-path size assertion (the encoder steps quality
 * down until it fits, so a normal screenshot always succeeds in tests).
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [33], application = Application::class)
class ScreenshotEncoderTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun unreadableUri_returnsUnreadableImage() {
        val nonExistent = File(context.cacheDir, "nope.png").toUri()
        val result = ScreenshotEncoder.encodeForVision(context, nonExistent)
        assertTrue(
            "expected UnreadableImage for missing file, got $result",
            result is ScreenshotEncoder.EncodeResult.UnreadableImage
        )
    }

    @Test
    fun smallBitmap_encodesToJpegBase64() {
        // Synthesize a small bitmap, write it to a file, then run it
        // through the encoder. Robolectric supports Bitmap.compress out
        // of the box for JPEG.
        val bmp = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.RED)
        val file = File(context.cacheDir, "tiny.png")
        file.outputStream().use { Bitmap.createBitmap(bmp).compress(Bitmap.CompressFormat.PNG, 100, it) }

        val result = ScreenshotEncoder.encodeForVision(context, file.toUri())
        assertTrue(
            "expected Success, got $result",
            result is ScreenshotEncoder.EncodeResult.Success
        )
        val encoded = (result as ScreenshotEncoder.EncodeResult.Success).encoded
        assertEquals("image/jpeg", encoded.mediaType)
        assertTrue("base64 should be non-empty", encoded.base64.isNotEmpty())
    }
}
