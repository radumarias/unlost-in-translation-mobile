package com.example.platform

/**
 * Logging seam (Phase 1 task 6, design §3). Lets shared code log without a direct
 * `android.util.Log` dependency; the Android impl forwards to Logcat, a web impl
 * would forward to the console. Only the levels actually used in the app are
 * exposed.
 */
interface AppLog {
    fun d(tag: String, message: String)
    fun e(tag: String, message: String, throwable: Throwable? = null)
}

/** Android [AppLog] backed by `android.util.Log`. */
class AndroidAppLog : AppLog {
    override fun d(tag: String, message: String) {
        android.util.Log.d(tag, message)
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) android.util.Log.e(tag, message, throwable) else android.util.Log.e(tag, message)
    }
}
