package com.example.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Covers the camera/gallery downscale-and-encode step that feeds Gemini's
 * inline image part. The launchers around it need a real device, but this
 * function does not — and it is the part with arithmetic in it.
 *
 * NATIVE graphics are required: under the legacy Robolectric graphics shadows
 * BitmapFactory hands back placeholder bitmaps and the sampling maths would be
 * asserted against fiction.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class AndroidImageLoadingTest {

  private val context = ApplicationProvider.getApplicationContext<Context>()

  private fun jpegBytes(width: Int, height: Int): ByteArray {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
    bitmap.recycle()
    return out.toByteArray()
  }

  /**
   * A *supplier*, not a single stream: the decode measures bounds first and
   * decodes second, so it opens the Uri twice.
   */
  private fun uriServing(bytes: ByteArray?): Uri {
    val uri = Uri.parse("content://com.example.test/image/${bytes?.size ?: "none"}")
    shadowOf(context.contentResolver).registerInputStreamSupplier(uri) {
      bytes?.let { ByteArrayInputStream(it) } as InputStream?
    }
    return uri
  }

  private fun decodedSize(base64: String): Pair<Int, Int> {
    val bytes = Base64.decode(base64, Base64.NO_WRAP)
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    assertNotNull("result must decode back to a bitmap", bitmap)
    return bitmap.width to bitmap.height
  }

  @Test
  fun `a camera-sized image is halved to the first power of two above the cap`() {
    val result = AndroidImageLoading.toBase64Jpeg(context, uriServing(jpegBytes(4000, 3000)))

    assertNotNull(result)
    // inSampleSize = 2. Sampling never drops below MAX_EDGE_PX (1536), so 4000
    // becomes 2000 rather than exactly 1536 — powers of two are all it can pick.
    assertEquals(2000 to 1500, decodedSize(result!!))
  }

  @Test
  fun `a larger image samples down by four`() {
    val result = AndroidImageLoading.toBase64Jpeg(context, uriServing(jpegBytes(6200, 4000)))

    assertNotNull(result)
    assertEquals(1550 to 1000, decodedSize(result!!))
  }

  @Test
  fun `an image already under the cap is left at its own size`() {
    val result = AndroidImageLoading.toBase64Jpeg(context, uriServing(jpegBytes(800, 600)))

    assertNotNull(result)
    assertEquals(800 to 600, decodedSize(result!!))
  }

  @Test
  fun `the encoding is unwrapped so it can sit in a JSON request body`() {
    val result = AndroidImageLoading.toBase64Jpeg(context, uriServing(jpegBytes(800, 600)))!!

    assertFalse("base64 must be NO_WRAP", result.contains('\n'))
    assertEquals("image/jpeg", AndroidImageLoading.MIME_TYPE)
  }

  @Test
  fun `bytes that are not an image yield null rather than an empty request`() {
    val result = AndroidImageLoading.toBase64Jpeg(context, uriServing("not an image".toByteArray()))

    assertNull(result)
  }

  @Test
  fun `a uri that cannot be opened yields null`() {
    val result = AndroidImageLoading.toBase64Jpeg(context, uriServing(null))

    assertNull(result)
  }
}
