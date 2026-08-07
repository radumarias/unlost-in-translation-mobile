package com.example.ui.screens

import com.example.link.LinkFailure
import com.example.link.LinkState
import com.example.ui.i18n.Strings
import com.example.ui.i18n.localeCatalogs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The connection banner's text, which is the only thing that tells a user why a session stopped. */
class LinkBannerTest {

    private val strings = Strings()

    @Test
    fun anIdleLinkShowsNothing() {
        assertNull(linkBannerText(LinkState.Idle, strings))
    }

    @Test
    fun everyFailureReasonHasSomethingToSay() {
        // An exhaustive `when` catches a *new* reason at compile time. Only this
        // catches one that compiles because it was wired to a blank string.
        LinkFailure.entries.forEach { reason ->
            val (message, isProblem) = assertNotNull(
                linkBannerText(LinkState.Failed(reason), strings),
                "no banner text for $reason",
            )
            assertTrue(message.isNotBlank(), "blank banner text for $reason")
            assertTrue(isProblem, "$reason should read as a problem")
        }
    }

    @Test
    fun everyFailureReasonHasSomethingToSayInEveryLocale() {
        localeCatalogs.forEach { (code, locale) ->
            LinkFailure.entries.forEach { reason ->
                val text = assertNotNull(linkBannerText(LinkState.Failed(reason), locale))
                assertTrue(text.first.isNotBlank(), "$code has a blank banner for $reason")
            }
        }
    }

    @Test
    fun connectingAndWaitingAreNotReportedAsProblems() {
        assertEquals(false, assertNotNull(linkBannerText(LinkState.Connecting, strings)).second)
        val open = assertNotNull(linkBannerText(LinkState.Connected(peerPresent = false), strings))
        assertEquals(strings.pairingWaiting, open.first)
        assertEquals(false, open.second)
    }

    @Test
    fun aConnectedPeerIsNamedByItsLanguage() {
        val text = assertNotNull(
            linkBannerText(LinkState.Connected(peerPresent = true, peerLang = "ro"), strings)
        ).first
        // The language *name*, not the code — "ro" means nothing to a user.
        assertTrue("Romanian" in text, text)
        assertTrue("{0}" !in text, "the placeholder must have been substituted")
    }

    @Test
    fun aPeerOnALanguageThisBuildDoesNotKnowStillGetsABanner() {
        val text = assertNotNull(
            linkBannerText(LinkState.Connected(peerPresent = true, peerLang = "sw"), strings)
        ).first
        assertTrue(text.isNotBlank())
        assertTrue("{0}" !in text)
    }
}
