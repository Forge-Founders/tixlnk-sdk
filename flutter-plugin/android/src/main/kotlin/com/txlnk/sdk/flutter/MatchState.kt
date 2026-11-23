package com.txlnk.sdk.flutter

/**
 * Stored result of the device match plus the last token already sent in a
 * completed match. Kept free of Android types so the rules run in host tests.
 *
 * Rules:
 * - the first open matches once, with or without a token;
 * - afterwards, a token different from [consumedToken] allows exactly one new
 *   match, whatever the earlier result was (organic or not);
 * - the same token never matches twice;
 * - a new token that gets 404 keeps the earlier result.
 */
internal data class MatchState(
    val matchDone: Boolean = false,
    val isOrganic: Boolean = false,
    val clickId: String? = null,
    val deepLink: String? = null,
    val attributionData: String? = null,
    val consumedToken: String? = null,
) {
    fun shouldMatch(token: String?): Boolean {
        if (!matchDone) {
            return true
        }
        if (token.isNullOrBlank()) {
            return false
        }
        return token != consumedToken
    }

    fun afterMatched(
        token: String?,
        clickId: String,
        deepLink: String,
        attributionData: String?,
    ): MatchState {
        return MatchState(
            matchDone = true,
            isOrganic = false,
            clickId = clickId,
            deepLink = deepLink,
            attributionData = attributionData,
            consumedToken = consumedAfter(token),
        )
    }

    fun afterNotFound(token: String?): MatchState {
        if (matchDone) {
            return copy(consumedToken = consumedAfter(token))
        }
        return MatchState(
            matchDone = true,
            isOrganic = true,
            consumedToken = consumedAfter(token),
        )
    }

    private fun consumedAfter(token: String?): String? {
        if (token.isNullOrBlank()) {
            return consumedToken
        }
        return token
    }
}
