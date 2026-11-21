package com.txlnk.sdk.flutter

import java.net.URLDecoder

internal object PlayReferrer {
    fun parseToken(referrer: String?): String? {
        if (referrer.isNullOrBlank()) {
            return null
        }
        val parts = referrer.split('&')
        for (part in parts) {
            val separator = part.indexOf('=')
            if (separator <= 0) {
                continue
            }
            val key = decode(part.substring(0, separator))
            if (key != "tixlnk_token") {
                continue
            }
            val value = decode(part.substring(separator + 1)).trim()
            if (value.isNotEmpty()) {
                return value
            }
        }
        return null
    }

    private fun decode(value: String): String {
        return runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    }
}
