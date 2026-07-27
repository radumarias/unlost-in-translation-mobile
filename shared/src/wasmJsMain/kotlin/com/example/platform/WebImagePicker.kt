package com.example.platform

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Web [ImagePicker] backed by a hidden `<input type="file">`. There is no
 * browser API to open a camera and hand back a still, so [ImageSource.CAMERA]
 * sets `capture="environment"`, which asks a mobile browser for the rear camera
 * and is simply ignored on desktop — where the file dialog is the right answer
 * anyway.
 *
 * FileReader yields a `data:` URI; the prefix is stripped here so what reaches
 * the API is the same bare base64 the Android side sends (`api/vision` in the
 * web build strips it server-side for the same reason).
 *
 * Resumes with null when the user closes the dialog without choosing. A browser
 * fires no event for that, so this cannot be detected — the coroutine simply
 * stays suspended until the caller cancels or a later pick supersedes it.
 */
class WebImagePicker(private val log: AppLog) : ImagePicker {

    private companion object {
        const val TAG = "WebImagePicker"
    }

    override suspend fun pick(source: ImageSource): PickedImage? =
        suspendCancellableCoroutine { continuation ->
            openImageInput(
                useCamera = source == ImageSource.CAMERA,
                onFile = { mimeType, dataUrl ->
                    val base64 = dataUrl.substringAfter("base64,", "")
                    if (base64.isEmpty()) {
                        log.e(TAG, "File reader returned an unexpected data URL")
                        continuation.resume(null)
                    } else {
                        continuation.resume(PickedImage(base64, mimeType.ifBlank { "image/jpeg" }))
                    }
                },
                onError = {
                    log.e(TAG, "File could not be read")
                    continuation.resume(null)
                },
            )
        }
}

/** True everywhere a file input exists, i.e. every browser this app can run in. */
internal fun imageInputAvailable(): Boolean = js("typeof document !== 'undefined'")

/**
 * Creates a detached file input, clicks it, and reads the chosen file as a
 * data URL. Detached rather than appended: it never has to be laid out, and
 * nothing has to remove it afterwards.
 */
private fun openImageInput(
    useCamera: Boolean,
    onFile: (String, String) -> Unit,
    onError: () -> Unit,
): Unit = js(
    """{
    var input = document.createElement('input');
    input.type = 'file';
    input.accept = 'image/*';
    if (useCamera) { input.capture = 'environment'; }
    input.onchange = function() {
        var file = input.files && input.files[0];
        if (!file) { onError(); return; }
        var reader = new FileReader();
        reader.onload = function() { onFile(String(file.type || ''), String(reader.result || '')); };
        reader.onerror = function() { onError(); };
        reader.readAsDataURL(file);
    };
    input.click();
}"""
)
