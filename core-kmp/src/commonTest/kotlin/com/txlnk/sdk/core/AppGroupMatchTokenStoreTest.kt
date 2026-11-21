package com.txlnk.sdk.core

import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppGroupMatchTokenStoreTest {
    @Test
    fun consume_readsOnceAndDeletes() {
        val shared = MapSettings()
        shared.putString(AppGroupMatchTokenStore.PENDING_MATCH_TOKEN_KEY, " tok-clip ")
        val store = AppGroupMatchTokenStore(shared)

        assertEquals("tok-clip", store.consume())
        assertNull(store.consume())
        assertNull(shared.getStringOrNull(AppGroupMatchTokenStore.PENDING_MATCH_TOKEN_KEY))
    }

    @Test
    fun consume_blankValueIsRemovedAndIgnored() {
        val shared = MapSettings()
        shared.putString(AppGroupMatchTokenStore.PENDING_MATCH_TOKEN_KEY, "   ")
        val store = AppGroupMatchTokenStore(shared)

        assertNull(store.consume())
        assertNull(shared.getStringOrNull(AppGroupMatchTokenStore.PENDING_MATCH_TOKEN_KEY))
    }

    @Test
    fun save_ignoresBlankTokens() {
        val shared = MapSettings()
        val store = AppGroupMatchTokenStore(shared)

        store.save("  ")
        assertNull(shared.getStringOrNull(AppGroupMatchTokenStore.PENDING_MATCH_TOKEN_KEY))

        store.save(" tok-1 ")
        assertEquals("tok-1", shared.getStringOrNull(AppGroupMatchTokenStore.PENDING_MATCH_TOKEN_KEY))
    }

    @Test
    fun matchDevice_consumesPendingTokenWhenNoneInMemory() {
        runBlocking {
            val shared = MapSettings()
            AppGroupMatchTokenStore(shared).save("tok-clip")
            val sent = mutableListOf<String?>()
            val client = clientRecordingTokens(sent)
            client.setAppGroupMatchTokenStore(AppGroupMatchTokenStore(shared))

            client.init("pk_test", "https://collector.txlnk.com")
            client.matchDevice()

            assertEquals(listOf<String?>("tok-clip"), sent)
            assertNull(shared.getStringOrNull(AppGroupMatchTokenStore.PENDING_MATCH_TOKEN_KEY))
        }
    }

    @Test
    fun matchDevice_tokenInMemoryWinsAndSharedValueStays() {
        runBlocking {
            val shared = MapSettings()
            val sent = mutableListOf<String?>()
            val client = clientRecordingTokens(sent)
            client.setAppGroupMatchTokenStore(AppGroupMatchTokenStore(shared))

            // The clip sets the token: it is kept in memory and shared with the full app.
            client.setDeferredLinkToken("tok-link")
            client.init("pk_test", "https://collector.txlnk.com")
            client.matchDevice()

            assertEquals(listOf<String?>("tok-link"), sent)
            assertEquals("tok-link", shared.getStringOrNull(AppGroupMatchTokenStore.PENDING_MATCH_TOKEN_KEY))
        }
    }

    @Test
    fun matchDevice_withoutStoreSendsNoToken() {
        runBlocking {
            val sent = mutableListOf<String?>()
            val client = clientRecordingTokens(sent)

            client.init("pk_test", "https://collector.txlnk.com")
            client.matchDevice()

            assertEquals(listOf<String?>(null), sent)
        }
    }

    private fun clientRecordingTokens(sent: MutableList<String?>): AdTechClient {
        val engine = MockEngine { request ->
            val body = (request.body as TextContent).text
            sent.add(Json.parseToJsonElement(body).jsonObject["token"]?.jsonPrimitive?.content)
            respond(
                content = """{"click_id":"clk_1","deep_link":"tixlnk://x"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val httpClient = HttpClient(engine) {
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
        return AdTechClient(
            httpClient = httpClient,
            settings = MapSettings(),
        )
    }
}
