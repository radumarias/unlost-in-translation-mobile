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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * Covers the camera/gallery downscale-and-encode step that feeds Gemini's
 * inline image part. The launchers around it need a real device, but this
 * function does not — and it is the part with arithmetic in it.
 *
 * NATIVE graphics are required: under the legacy Robolectric graphics shadows
 * BitmapFactory hands back placeholder bitmaps and the sampling maths would be
 * asserted against fiction. Source bitmaps are kept as thin as the assertion
 * allows — the maths only reads the longest edge — because a full 6200x4000
 * ARGB_8888 source is ~99 MB of real allocation per test.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class AndroidImageLoadingTest {

  @get:Rule
  val temporaryFolder = TemporaryFolder()

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
  private fun uriServing(bytes: ByteArray): Uri {
    val uri = Uri.parse("content://com.example.test/image/${bytes.size}")
    shadowOf(context.contentResolver).registerInputStreamSupplier(uri) {
      ByteArrayInputStream(bytes) as InputStream?
    }
    return uri
  }

  private fun decodedSize(base64: String): Pair<Int, Int> {
    val bytes = Base64.decode(base64, Base64.NO_WRAP)
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    assertNotNull("result must decode back to a bitmap", bitmap)
    val size = bitmap.width to bitmap.height
    bitmap.recycle()
    return size
  }

  @Test
  fun `a camera-sized image comes back at exactly the cap`() {
    val result = AndroidImageLoading.toBase64Jpeg(context, uriServing(jpegBytes(4000, 3000)))

    assertNotNull(result)
    // Sampling alone (inSampleSize = 2) would stop at 2000x1500: powers of two
    // never drop below the cap. The scale pass finishes the job.
    assertEquals(1536 to 1152, decodedSize(result!!))
  }

  @Test
  fun `an image just under twice the cap is still brought down to it`() {
    // The case power-of-two sampling misses entirely: 3070 / 2 is below the
    // cap, so inSampleSize stays 1 and the photo would upload untouched.
    val result = AndroidImageLoading.toBase64Jpeg(context, uriServing(jpegBytes(3070, 400)))

    assertNotNull(result)
    assertEquals(1536 to 200, decodedSize(result!!))
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
  fun `a uri whose file has gone away yields null instead of throwing`() {
    // openInputStream throws FileNotFoundException here rather than returning
    // null — the picked photo deleted between the pick and the read. Reaching
    // this test through a real file: Uri is deliberate: a content: Uri with no
    // provider gets Robolectric's UnregisteredInputStream, which is not null
    // and so would not exercise the same path.
    val missing = File(temporaryFolder.root, "gone.jpg")
    val result = AndroidImageLoading.toBase64Jpeg(context, Uri.fromFile(missing))

    assertNull(result)
  }

  @Test
  fun `a readable file uri is decoded like any other source`() {
    // The counterpart to the test above: proves that path fails for the file
    // being missing, not for the scheme.
    val file = temporaryFolder.newFile("photo.jpg")
    file.writeBytes(jpegBytes(800, 600))

    val result = AndroidImageLoading.toBase64Jpeg(context, Uri.fromFile(file))

    assertNotNull(result)
    assertEquals(800 to 600, decodedSize(result!!))
  }
}
