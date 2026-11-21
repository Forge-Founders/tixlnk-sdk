package com.txlnk.sdk.core

import android.content.Context
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Lê `tixlnk_token` do Play Install Referrer.
 *
 * A string do referrer não é registrada. Sem resposta da Play, o resultado é
 * nulo e o match segue orgânico. Um token já capturado por link instalado
 * continua tendo prioridade: quem chama decide se substitui.
 */
object PlayInstallReferrer {
    const val DEFAULT_TIMEOUT_MILLIS: Long = 2_500L

    suspend fun readToken(
        context: Context,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    ): String? {
        return withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                val client = InstallReferrerClient.newBuilder(context).build()
                continuation.invokeOnCancellation {
                    runCatching { client.endConnection() }
                }
                try {
                    client.startConnection(object : InstallReferrerStateListener {
                        override fun onInstallReferrerSetupFinished(responseCode: Int) {
                            val token = if (responseCode == InstallReferrerClient.InstallReferrerResponse.OK) {
                                runCatching {
                                    PlayReferrer.parseToken(client.installReferrer.installReferrer)
                                }.getOrNull()
                            } else {
                                null
                            }
                            runCatching { client.endConnection() }
                            if (continuation.isActive) {
                                continuation.resume(token)
                            }
                        }

                        override fun onInstallReferrerServiceDisconnected() {
                        }
                    })
                } catch (_: Exception) {
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }
            }
        }
    }
}
