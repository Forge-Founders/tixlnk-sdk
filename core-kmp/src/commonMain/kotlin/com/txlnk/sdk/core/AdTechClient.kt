package com.txlnk.sdk.core

import com.russhwolf.settings.Settings
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Cliente principal do SDK de atribuição para inicialização, matching de dispositivo e tracking de eventos.
 */
class AdTechClient(
    private val httpClient: HttpClient = defaultHttpClient(),
    // No device signals are collected: the match is by opaque click token only.
    private val settings: Settings,
    private val onAttributionData: (String) -> Unit = {},
) {
    private var apiKey: String? = null
    private var endpoint: String? = null
    private var tenantId: String? = null
    private var appId: String? = null
    private var deferredToken: String? = null
    private var appGroupMatchTokenStore: AppGroupMatchTokenStore? = null

    // Same engine as [httpClient], but the redirect to the store or fallback
    // must never be followed: the match token lives on the 3xx response.
    private val noRedirectHttpClient: HttpClient by lazy {
        httpClient.config {
            followRedirects = false
            expectSuccess = false
        }
    }

    /**
     * Inicializa o cliente com as credenciais e endpoint da API de atribuição.
     *
     * @param apiKey Chave pública da aplicação. Exemplo: `"pk_live_123"`.
     * @param endpoint Endpoint base da API. Exemplo: `"https://collector.adserver.com"`.
     * @param tenantId Tenant scoping opcional (enviado no match).
     * @param appId App scoping opcional (enviado no match).
     * @throws IllegalArgumentException Quando `apiKey` ou `endpoint` estiverem vazios.
     */
    fun init(apiKey: String, endpoint: String, tenantId: String? = null, appId: String? = null) {
        require(apiKey.isNotBlank()) { "apiKey must not be blank" }
        require(endpoint.isNotBlank()) { "endpoint must not be blank" }

        this.apiKey = apiKey
        this.endpoint = endpoint.trimEnd('/')
        this.tenantId = tenantId
        this.appId = appId
    }

    /**
     * Registra o token de match capturado do link (Universal/App Link no iOS,
     * Play Install Referrer no Android). Enviado como identificador primário no
     * device match, conforme o contrato do backend.
     */
    fun setDeferredLinkToken(token: String) {
        this.deferredToken = token
        appGroupMatchTokenStore?.save(token)
    }

    /**
     * Liga o App Group compartilhado com o App Clip (iOS). Com o store
     * definido, [setDeferredLinkToken] também grava o token nele, e o primeiro
     * [matchDevice] sem token em memória lê e apaga o token pendente, uma vez.
     * Um token já definido tem prioridade. `null` desliga o passo.
     *
     * No iOS, use `IosAppGroup.matchTokenStore()`, que lê `TixlnkAppGroup` do
     * Info.plist. Chame antes de [setDeferredLinkToken] e de [matchDevice].
     */
    fun setAppGroupMatchTokenStore(store: AppGroupMatchTokenStore?) {
        this.appGroupMatchTokenStore = store
    }

    /**
     * Resolve a URL de Universal Link (iOS) ou App Link (Android) recebida pelo
     * app no token de match de uso único e o registra via [setDeferredLinkToken].
     *
     * Mesmo contrato do plugin Flutter: faz GET na URL sem seguir o redirect,
     * lê `X-Tixlnk-Match-Token` da resposta 3xx e para antes do fallback. O
     * token fica só no header; ele não é copiado para nenhuma URL nem logado.
     * Chame antes de [matchDevice] no cold start, ou seguido de [matchDevice]
     * num link quente. Não exige [init].
     *
     * @param url URL HTTP(S) do link. Exemplo: `"https://go.exemplo.com/oferta"`.
     * @throws IllegalArgumentException Quando a URL não é HTTP(S).
     * @throws IllegalStateException Quando a resposta não é 3xx ou não traz o header.
     */
    suspend fun setDeferredLinkUrl(url: String) {
        val parsed = requireNotNull(parseHttpLinkUrl(url)) {
            "An HTTP(S) Universal/App Link URL is required"
        }

        val response = noRedirectHttpClient.get(parsed)
        val status = response.status.value
        val token = response.headers[MATCH_TOKEN_HEADER]?.trim()
        if (status !in 300..399 || token.isNullOrEmpty()) {
            throw IllegalStateException(
                "Link response HTTP $status did not include $MATCH_TOKEN_HEADER",
            )
        }
        setDeferredLinkToken(token)
    }

    /**
     * Executa o fluxo de device match no first-open e persiste o estado de atribuição.
     *
     * Regras:
     * - Executa apenas uma vez por instalação.
     * - O match é keyed pelo [deferredToken] (identificador primário) quando
     *   presente; sinais de dispositivo são enviados como contexto opcional.
     * - Sem token em memória, lê e apaga uma vez o token pendente do App Group
     *   ([setAppGroupMatchTokenStore]), quando configurado.
     * - Em `200`, salva `click_id` + `deep_link` e dispara callback [onAttributionData].
     * - Em `404`, marca instalação orgânica (`is_organic = true`).
     *
     * @return [MatchDeviceResult] com o estado final de atribuição.
     * @throws IllegalStateException Quando o SDK não foi inicializado via [init].
     */
    suspend fun matchDevice(): MatchDeviceResult {
        if (settings.getBoolean(KEY_MATCH_DONE, false)) {
            return loadStoredMatchResult(wasFreshMatch = false)
        }

        val config = requireConfig()
        consumeAppGroupTokenIfNeeded()
        val payload = DeviceMatchRequest(
            token = deferredToken,
            tenantId = tenantId,
            appId = appId,
        )

        val response = httpClient.post("${config.endpoint}/v1/device/match") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
            setBody(payload)
        }

        return when (response.status) {
            HttpStatusCode.OK -> {
                val body = response.body<MatchDeviceSuccessResponse>()
                val attributionJson = body.data?.let { json.encodeToString(it) } ?: "{}"

                settings.putBoolean(KEY_MATCH_DONE, true)
                settings.putBoolean(KEY_IS_ORGANIC, false)
                settings.putString(KEY_CLICK_ID, body.clickId)
                settings.putString(KEY_DEEP_LINK, body.deepLink)
                settings.putString(KEY_ATTRIBUTION_DATA, attributionJson)

                onAttributionData(attributionJson)

                MatchDeviceResult(
                    clickId = body.clickId,
                    deepLink = body.deepLink,
                    attributionData = body.data,
                    isOrganic = false,
                    wasFreshMatch = true,
                )
            }

            HttpStatusCode.NotFound -> {
                settings.putBoolean(KEY_MATCH_DONE, true)
                settings.putBoolean(KEY_IS_ORGANIC, true)
                settings.remove(KEY_CLICK_ID)
                settings.remove(KEY_DEEP_LINK)
                settings.remove(KEY_ATTRIBUTION_DATA)

                MatchDeviceResult(
                    clickId = null,
                    deepLink = null,
                    attributionData = null,
                    isOrganic = true,
                    wasFreshMatch = true,
                )
            }

            else -> {
                val rawBody = response.bodyAsText()
                throw IllegalStateException(
                    "Device match failed: HTTP ${response.status.value} - $rawBody",
                )
            }
        }
    }

    /**
     * Envia evento server-to-server para `/v1/s2s/events`.
     *
     * @param event Estrutura do evento contendo nome, timestamp e propriedades customizadas.
     * Exemplo: `AdTechEvent(name = "signup", properties = mapOf("method" to "email"))`.
     * `eventId`, `valueCents` e `currency` são opcionais. Os relatórios de
     * receita contam só `purchase`/`refund` enviados pelo servidor com a chave
     * de servidor (`sk_live_`); um `purchase` enviado daqui fica com
     * `source=client`.
     *
     * @throws IllegalStateException Quando o SDK não foi inicializado via [init].
     * @throws IllegalArgumentException Quando `event.name` estiver vazio, `eventId`
     * tiver mais de 128 bytes ou espaço nas pontas, ou `valueCents`/`currency`
     * vierem sem o par, com valor negativo ou moeda fora do ISO 4217 em maiúsculas.
     */
    suspend fun track(event: AdTechEvent) {
        require(event.name.isNotBlank()) { "event.name must not be blank" }
        validateEventId(event.eventId)
        validateEventValue(event.valueCents, event.currency)
        val config = requireConfig()

        httpClient.post("${config.endpoint}/v1/s2s/events") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
            setBody(event)
        }
    }

    /**
     * Lê o `click_id` persistido para enriquecimento de eventos de conversão no wrapper Flutter.
     */
    fun getStoredClickId(): String? = settings.getStringOrNull(KEY_CLICK_ID)

    /**
     * Lê o deep link resolvido persistido no match.
     */
    fun getStoredDeepLink(): String? = settings.getStringOrNull(KEY_DEEP_LINK)

    /**
     * Lê o `attribution_data` persistido em JSON bruto.
     */
    fun getStoredAttributionDataJson(): String? = settings.getStringOrNull(KEY_ATTRIBUTION_DATA)

    private fun loadStoredMatchResult(wasFreshMatch: Boolean): MatchDeviceResult {
        val clickId = settings.getStringOrNull(KEY_CLICK_ID)
        val deepLink = settings.getStringOrNull(KEY_DEEP_LINK)
        val attributionJson = settings.getStringOrNull(KEY_ATTRIBUTION_DATA)
        val attributionData = attributionJson?.let {
            runCatching { json.parseToJsonElement(it) }.getOrNull()
        }

        return MatchDeviceResult(
            clickId = clickId,
            deepLink = deepLink,
            attributionData = attributionData,
            isOrganic = settings.getBoolean(KEY_IS_ORGANIC, clickId == null),
            wasFreshMatch = wasFreshMatch,
        )
    }

    private fun validateEventId(eventId: String?) {
        if (eventId == null) {
            return
        }
        require(eventId.isNotEmpty()) { "eventId must not be empty" }
        require(eventId.trim() == eventId) { "eventId must not have surrounding whitespace" }
        require(eventId.encodeToByteArray().size <= MAX_EVENT_ID_BYTES) {
            "eventId must have at most $MAX_EVENT_ID_BYTES characters"
        }
    }

    private fun validateEventValue(valueCents: Long?, currency: String?) {
        if (valueCents == null && currency == null) {
            return
        }
        require(valueCents != null && currency != null) { "valueCents and currency go together" }
        require(valueCents >= 0) { "valueCents must not be negative" }
        require(isIsoCurrencyCode(currency)) { "currency must be an uppercase ISO 4217 code such as BRL" }
    }

    private fun isIsoCurrencyCode(currency: String): Boolean {
        if (currency.length != 3) {
            return false
        }
        for (char in currency) {
            if (char !in 'A'..'Z') {
                return false
            }
        }
        return true
    }

    private fun consumeAppGroupTokenIfNeeded() {
        if (!deferredToken.isNullOrBlank()) {
            return
        }
        val store = appGroupMatchTokenStore ?: return
        val pending = store.consume() ?: return
        deferredToken = pending
    }

    private fun parseHttpLinkUrl(raw: String): Url? {
        val trimmed = raw.trim()
        val scheme = trimmed.substringBefore("://", missingDelimiterValue = "").lowercase()
        if (scheme != URLProtocol.HTTP.name && scheme != URLProtocol.HTTPS.name) {
            return null
        }
        // Ktor fills a missing host with "localhost"; require one in the raw URL.
        val authority = trimmed.substringAfter("://").takeWhile { it != '/' && it != '?' && it != '#' }
        if (authority.substringAfterLast('@').isBlank()) {
            return null
        }
        val parsed = runCatching { Url(trimmed) }.getOrNull() ?: return null
        if (parsed.host.isBlank()) {
            return null
        }
        return parsed
    }

    private fun requireConfig(): ClientConfig {
        val apiKey = checkNotNull(apiKey) { "AdTechClient not initialized. Call init(apiKey, endpoint)." }
        val endpoint = checkNotNull(endpoint) { "AdTechClient not initialized. Call init(apiKey, endpoint)." }
        return ClientConfig(apiKey = apiKey, endpoint = endpoint)
    }

    private data class ClientConfig(
        val apiKey: String,
        val endpoint: String,
    )

    private companion object {
        private const val KEY_MATCH_DONE = "adtech.match_done"
        private const val KEY_CLICK_ID = "adtech.click_id"
        private const val KEY_DEEP_LINK = "adtech.deep_link"
        private const val KEY_ATTRIBUTION_DATA = "adtech.attribution_data"
        private const val KEY_IS_ORGANIC = "adtech.is_organic"
        private const val MATCH_TOKEN_HEADER = "X-Tixlnk-Match-Token"
        private const val MAX_EVENT_ID_BYTES = 128
        private val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            isLenient = true
        }
    }
}

/**
 * Evento de tracking para envio no endpoint S2S.
 *
 * @property eventId Id idempotente do evento (`event_id`), até 128 caracteres,
 * sem espaço nas pontas. Exemplo: o id do pedido.
 * @property valueCents Valor em centavos (`value_cents`), não negativo. Vai junto com [currency].
 * @property currency Moeda ISO 4217 em maiúsculas (`currency`), por exemplo `"BRL"`.
 * Vai junto com [valueCents].
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AdTechEvent(
    @SerialName("name") val name: String,
    @SerialName("timestamp") val timestamp: Long = currentEpochMillis(),
    @SerialName("properties") val properties: Map<String, String> = emptyMap(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("event_id") val eventId: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("value_cents") val valueCents: Long? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("currency") val currency: String? = null,
)

/**
 * Resultado do device match com estado persistido.
 */
data class MatchDeviceResult(
    val clickId: String?,
    val deepLink: String? = null,
    val attributionData: JsonElement?,
    val isOrganic: Boolean,
    val wasFreshMatch: Boolean,
)

@Serializable
private data class MatchDeviceSuccessResponse(
    @SerialName("click_id") val clickId: String,
    @SerialName("deep_link") val deepLink: String,
    @SerialName("data") val data: JsonElement? = null,
)

@Serializable
private data class DeviceMatchRequest(
    @SerialName("token") val token: String? = null,
    @SerialName("tenant_id") val tenantId: String? = null,
    @SerialName("app_id") val appId: String? = null,
)

private fun defaultHttpClient(): HttpClient = HttpClient {
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
                isLenient = true
            },
        )
    }
}

internal expect fun currentEpochMillis(): Long
