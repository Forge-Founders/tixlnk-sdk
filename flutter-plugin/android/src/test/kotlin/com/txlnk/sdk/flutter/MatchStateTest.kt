package com.txlnk.sdk.flutter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchStateTest {
    @Test
    fun firstOpenAlwaysMatches() {
        assertTrue(MatchState().shouldMatch(null))
        assertTrue(MatchState().shouldMatch("tok-1"))
    }

    @Test
    fun newTokenAfterNonOrganicMatchMatchesOnce() {
        val first = MatchState().afterMatched("tok-1", "clk_1", "app://one", null)

        assertTrue(first.shouldMatch("tok-2"))
        val second = first.afterMatched("tok-2", "clk_2", "app://two", "{\"c\":\"b\"}")

        assertFalse(second.shouldMatch("tok-2"))
        assertFalse(second.isOrganic)
        assertEquals("clk_2", second.clickId)
        assertEquals("app://two", second.deepLink)
        assertEquals("{\"c\":\"b\"}", second.attributionData)
    }

    @Test
    fun sameTokenNeverMatchesTwice() {
        val state = MatchState().afterMatched("tok-1", "clk_1", "app://one", null)

        assertFalse(state.shouldMatch("tok-1"))
    }

    @Test
    fun withoutNewTokenTheStoredResultStays() {
        val organic = MatchState().afterNotFound(null)
        val attributed = MatchState().afterMatched("tok-1", "clk_1", "app://one", null)

        assertFalse(organic.shouldMatch(null))
        assertFalse(organic.shouldMatch(""))
        assertTrue(organic.isOrganic)
        assertFalse(attributed.shouldMatch(null))
        assertFalse(attributed.isOrganic)
    }

    @Test
    fun newTokenAfterOrganicFirstOpenCanAttribute() {
        val organic = MatchState().afterNotFound(null)

        assertTrue(organic.shouldMatch("tok-1"))
        val attributed = organic.afterMatched("tok-1", "clk_1", "app://one", null)
        assertFalse(attributed.isOrganic)
        assertFalse(attributed.shouldMatch("tok-1"))
    }

    @Test
    fun newTokenThatDoesNotMatchKeepsTheEarlierResult() {
        val attributed = MatchState().afterMatched("tok-1", "clk_1", "app://one", "{}")

        val after = attributed.afterNotFound("tok-expired")

        assertTrue(after.matchDone)
        assertFalse(after.isOrganic)
        assertEquals("clk_1", after.clickId)
        assertEquals("app://one", after.deepLink)
        assertFalse(after.shouldMatch("tok-expired"))
    }

    @Test
    fun organicFirstOpenWithoutTokenStoresNoToken() {
        val organic = MatchState().afterNotFound(null)

        assertTrue(organic.matchDone)
        assertTrue(organic.isOrganic)
        assertNull(organic.clickId)
        assertNull(organic.consumedToken)
    }
}
