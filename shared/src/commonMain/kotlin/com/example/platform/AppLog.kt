package com.example.platform

/**
 * Logging seam (design §3). Lets shared code log without a direct
 * `android.util.Log` dependency; the Android impl forwards to Logcat, a web impl
 * would forward to the console. Only the levels actually used in the app are
 * exposed.
 */
interface AppLog {
    fun d(tag: String, message: String)
    fun e(tag: String, message: String, throwable: Throwable? = null)
}
