package com.example.platform

/** Android [AppLog] backed by `android.util.Log`. Moves to shared/androidMain in task 6. */
class AndroidAppLog : AppLog {
    override fun d(tag: String, message: String) {
        android.util.Log.d(tag, message)
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) android.util.Log.e(tag, message, throwable) else android.util.Log.e(tag, message)
    }
}
