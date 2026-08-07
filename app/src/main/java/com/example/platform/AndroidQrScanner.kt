package com.example.platform

import android.app.Activity
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Reads a pairing QR with the GMS Code Scanner (issue #33).
 *
 * Chosen over CameraX + ML Kit for one reason that matters more than any other
 * here: it needs **no `CAMERA` permission**. This app has no runtime-permission UI
 * at all — the camera works by handing off to the camera app, the gallery through
 * the permissionless photo picker — and adding a permission flow for one feature
 * would change the app's posture for every user, including the ones who never pair
 * a second phone. Google supplies the scanning UI; this is the whole integration.
 *
 * It needs an **Activity**, not an application context, which is why the container
 * binds `UnavailableQrScanner` and the live path is built in `MainActivity`, exactly
 * as the voice and image launchers are.
 */
class AndroidQrScanner(private val activity: Activity, private val log: AppLog) : QrScanner {

    private val scanner by lazy {
        GmsBarcodeScanning.getClient(
            activity,
            GmsBarcodeScannerOptions.Builder()
                // Only QR: allowing every symbology would have the scanner lock onto
                // a barcode on the table instead of the code on the other phone.
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build(),
        )
    }

    /**
     * Returns the decoded payload, or null when the user backs out or the module
     * is unavailable. Null rather than an exception: not scanning is an ordinary
     * outcome, and the typed-code path is right there on the same dialog.
     */
    override suspend fun scan(): String? =
        suspendCancellableCoroutine { continuation ->
            scanner.startScan()
                .addOnSuccessListener { barcode ->
                    if (continuation.isActive) continuation.resume(barcode.rawValue) {}
                }
                .addOnCanceledListener {
                    if (continuation.isActive) continuation.resume(null) {}
                }
                .addOnFailureListener { error ->
                    // The scanner module downloads on demand and can genuinely be
                    // unavailable — on a device with no Play services, or with no
                    // network the first time. That is not a crash; it is a reason
                    // to type the six characters instead.
                    log.e(TAG, "QR scan failed", error)
                    if (continuation.isActive) continuation.resume(null) {}
                }
        }

    private companion object {
        const val TAG = "AndroidQrScanner"
    }
}
