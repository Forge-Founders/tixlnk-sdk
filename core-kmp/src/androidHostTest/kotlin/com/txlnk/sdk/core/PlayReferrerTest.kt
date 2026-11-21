package com.txlnk.sdk.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayReferrerTest {
    @Test
    fun readsTheTokenParameter() {
        assertEquals("abc", PlayReferrer.parseToken("tixlnk_token=abc"))
    }

    @Test
    fun readsAnEncodedTokenAmongOtherParameters() {
        assertEquals(
            "abc+1",
            PlayReferrer.parseToken("utm_source=ad&tixlnk_token=abc%2B1&utm_medium=cpc"),
        )
    }

    @Test
    fun ignoresBlankAndUnrelatedReferrers() {
        assertNull(PlayReferrer.parseToken(null))
        assertNull(PlayReferrer.parseToken(""))
        assertNull(PlayReferrer.parseToken("utm_source=ad"))
        assertNull(PlayReferrer.parseToken("tixlnk_token="))
        assertNull(PlayReferrer.parseToken("tixlnk_token=&utm_source=ad"))
    }
}
