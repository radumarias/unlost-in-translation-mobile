package com.example.platform

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Destination for `ActivityResultContracts.TakePicture`, which writes into a
 * Uri we provide rather than handing one back.
 *
 * The file lives in the app's own cache and is shared with the camera app
 * through a [FileProvider], because a raw `file://` Uri has been illegal to
 * pass across a process boundary since Android 7.
 */
internal object CameraCapture {

    private const val CAPTURE_DIR = "camera"

    fun newImageUri(context: Context): Uri {
        val dir = File(context.cacheDir, CAPTURE_DIR).apply { mkdirs() }
        // One fixed name: the photo is read, encoded and forgotten within the
        // same interaction, so keeping older captures around would only leave
        // the user's photos on disk for no benefit.
        val file = File(dir, "capture.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
}
