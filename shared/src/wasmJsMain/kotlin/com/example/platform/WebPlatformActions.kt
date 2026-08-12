package com.example.platform

/**
 * Web [PlatformActions]. Copy uses the async Clipboard API
 * (`navigator.clipboard.writeText`); share uses the Web Share API
 * (`navigator.share`) where available (mobile browsers, some desktop), falling
 * back to a clipboard copy so the button still does something useful on
 * browsers without navigator.share. Both are best-effort and swallow rejected
 * promises — a denied clipboard permission must not crash the UI.
 */
object WebPlatformActions : PlatformActions {
    override fun copyToClipboard(text: String) {
        copyViaBrowser(text)
    }

    override fun share(text: String, title: String) {
        shareViaBrowser(text, title)
    }
}

private fun copyViaBrowser(text: String) {
    js(
        """{
        if (navigator.clipboard) {
            navigator.clipboard.writeText(text).catch(function(e) {});
        }
    }"""
    )
}

private fun shareViaBrowser(text: String, title: String) {
    js(
        """{
        if (navigator.share) {
            navigator.share({ title: title, text: text }).catch(function(e) {});
        } else if (navigator.clipboard) {
            navigator.clipboard.writeText(text).catch(function(e) {});
        }
    }"""
    )
}
