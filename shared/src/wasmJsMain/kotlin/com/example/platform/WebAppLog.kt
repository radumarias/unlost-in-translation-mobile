package com.example.platform

/**
 * Web [AppLog]: forwards to the browser console. `d` → console.log, `e` →
 * console.error (with the throwable's message/stack appended when present).
 */
class WebAppLog : AppLog {
    override fun d(tag: String, message: String) {
        consoleLog("[$tag] $message")
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        val detail = throwable?.let { "\n${it.stackTraceToString()}" } ?: ""
        consoleError("[$tag] $message$detail")
    }
}

private fun consoleLog(message: String) {
    js("console.log(message)")
}

private fun consoleError(message: String) {
    js("console.error(message)")
}
