package com.txlnk.sdk.core

import com.russhwolf.settings.NSUserDefaultsSettings
import platform.Foundation.NSBundle
import platform.Foundation.NSUserDefaults

/**
 * Leitura do App Group compartilhado com o App Clip, igual ao plugin Flutter.
 */
object IosAppGroup {
    /**
     * Monta o [AppGroupMatchTokenStore] do App Group nomeado pela chave
     * `TixlnkAppGroup` do Info.plist. Sem a chave, ou com valor vazio, devolve
     * `null` e nada é lido nem gravado.
     *
     * Uso: `client.setAppGroupMatchTokenStore(IosAppGroup.matchTokenStore())`
     * antes do primeiro [AdTechClient.matchDevice], no app e no App Clip.
     */
    fun matchTokenStore(bundle: NSBundle = NSBundle.mainBundle): AppGroupMatchTokenStore? {
        val group = (bundle.objectForInfoDictionaryKey(AppGroupMatchTokenStore.INFO_PLIST_KEY) as? String)
            ?.trim()
        if (group.isNullOrEmpty()) {
            return null
        }
        val defaults = NSUserDefaults(suiteName = group)
        return AppGroupMatchTokenStore(NSUserDefaultsSettings(defaults))
    }
}
