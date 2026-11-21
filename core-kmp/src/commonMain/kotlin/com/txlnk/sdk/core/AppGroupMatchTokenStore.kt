package com.txlnk.sdk.core

import com.russhwolf.settings.Settings

/**
 * Token de match pendente compartilhado entre o App Clip e o app completo.
 *
 * No iOS, o [settings] é o `NSUserDefaults(suiteName:)` do App Group nomeado
 * pela chave [INFO_PLIST_KEY] do Info.plist (veja `IosAppGroup.matchTokenStore`).
 * O clip grava o token em [PENDING_MATCH_TOKEN_KEY]; o app completo lê e apaga
 * esse valor uma vez, no primeiro match sem token em memória. O token não é
 * registrado em log.
 */
class AppGroupMatchTokenStore(private val settings: Settings) {
    /**
     * Grava o token para o outro alvo do App Group. Token vazio não é gravado.
     */
    fun save(token: String) {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) {
            return
        }
        settings.putString(PENDING_MATCH_TOKEN_KEY, trimmed)
    }

    /**
     * Lê e apaga o token pendente. Devolve `null` quando não há token.
     */
    fun consume(): String? {
        val stored = settings.getStringOrNull(PENDING_MATCH_TOKEN_KEY)?.trim()
        settings.remove(PENDING_MATCH_TOKEN_KEY)
        if (stored.isNullOrEmpty()) {
            return null
        }
        return stored
    }

    companion object {
        /** Chave do Info.plist com o identificador do App Group. */
        const val INFO_PLIST_KEY: String = "TixlnkAppGroup"

        /** Chave do token pendente no UserDefaults do App Group. */
        const val PENDING_MATCH_TOKEN_KEY: String = "tixlnk.pending_match_token"
    }
}
