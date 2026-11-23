package com.txlnk.sdk.core

import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpResponseData
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdTechClientRematchTest {
    private val sentTokens = mutableListOf<String?>()
    private val callbacks = mutableListOf<String>()

    @Test
    fun newToken_afterNonOrganicMatch_matchesOnceAndDeliversNewDeepLink() {
        runBlocking {
            val client = client { token ->
                matched("clk_$token", "app://$token")
            }

            client.setDeferredLinkToken("tok-1")
            val first = client.matchDevice()
            client.setDeferredLinkToken("tok-2")
            val second = client.matchDevice()
            val third = client.matchDevice()

            assertEquals(listOf<String?>("tok-1", "tok-2"), sentTokens)
            assertTrue(first.wasFreshMatch)
            assertTrue(second.wasFreshMatch)
            assertFalse(second.isOrganic)
            assertEquals("clk_tok-2", second.clickId)
            assertEquals("app://tok-2", second.deepLink)
            assertEquals(2, callbacks.size)
            assertFalse(third.wasFreshMatch)
            assertEquals("app://tok-2", third.deepLink)
        }
    }

    @Test
    fun sameToken_neverMatchesTwice() {
        runBlocking {
            val client = client { token -> matched("clk_$token", "app://$token") }

            client.setDeferredLinkToken("tok-1")
            client.matchDevice()
            client.setDeferredLinkToken("tok-1")
            val again = client.matchDevice()

            assertEquals(listOf<String?>("tok-1"), sentTokens)
            assertFalse(again.wasFreshMatch)
            assertEquals("clk_tok-1", again.clickId)
        }
    }

    @Test
    fun sameToken_isRememberedAcrossClientInstances() {
        runBlocking {
            val settings = MapSettings()
            val first = client(settings) { token -> matched("clk_$token", "app://$token") }
            first.setDeferredLinkToken("tok-1")
            first.matchDevice()

            val restarted = client(settings) { error("must not call the network") }
            restarted.setDeferredLinkToken("tok-1")
            val result = restarted.matchDevice()

            assertFalse(result.wasFreshMatch)
            assertEquals("clk_tok-1", result.clickId)
        }
    }

    @Test
    fun newToken_afterOrganicFirstOpen_canBecomeNonOrganic() {
        runBlocking {
            val client = client { token ->
                if (token == null) notFound() else matched("clk_$token", "app://$token")
            }

            val organic = client.matchDevice()
            client.setDeferredLinkToken("tok-1")
            val attributed = client.matchDevice()

            assertTrue(organic.isOrganic)
            assertTrue(attributed.wasFreshMatch)
            assertFalse(attributed.isOrganic)
            assertEquals("clk_tok-1", client.getStoredClickId())
        }
    }

    @Test
    fun newToken_thatDoesNotMatch_keepsPreviousAttribution() {
        runBlocking {
            val client = client { token ->
                if (token == "tok-1") matched("clk_1", "app://one") else notFound()
            }

            client.setDeferredLinkToken("tok-1")
            client.matchDevice()
            client.setDeferredLinkToken("tok-expired")
            val result = client.matchDevice()
            val after = client.matchDevice()

            assertEquals(listOf<String?>("tok-1", "tok-expired"), sentTokens)
            assertFalse(result.wasFreshMatch)
            assertFalse(result.isOrganic)
            assertEquals("clk_1", result.clickId)
            assertEquals("app://one", client.getStoredDeepLink())
            assertFalse(after.wasFreshMatch)
        }
    }

    @Test
    fun withoutNewToken_firstOpenStateStaysAsStored() {
        runBlocking {
            val client = client { notFound() }

            val first = client.matchDevice()
            val second = client.matchDevice()

            assertEquals(1, sentTokens.size)
            assertNull(sentTokens.single())
            assertTrue(first.isOrganic)
            assertTrue(second.isOrganic)
            assertFalse(second.wasFreshMatch)
        }
    }

    @Test
    fun failedMatch_doesNotConsumeTheToken() {
        runBlocking {
            var fail = true
            val client = client { token ->
                if (fail) {
                    respond("{}", HttpStatusCode.InternalServerError)
                } else {
                    matched("clk_$token", "app://$token")
                }
            }

            client.setDeferredLinkToken("tok-1")
            runCatching { client.matchDevice() }
            fail = false
            val retried = client.matchDevice()

            assertEquals(listOf<String?>("tok-1", "tok-1"), sentTokens)
            assertTrue(retried.wasFreshMatch)
            assertEquals("clk_tok-1", retried.clickId)
        }
    }

    private fun client(
        settings: MapSettings = MapSettings(),
        reply: MockRequestHandleScope.(String?) -> HttpResponseData,
    ): AdTechClient {
        val engine = MockEngine { request ->
            val body = (request.body as TextContent).text
            val token = Json.parseToJsonElement(body).jsonObject["token"]?.jsonPrimitive?.content
            sentTokens.add(token)
            reply(token)
        }
        val http = HttpClient(engine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        return AdTechClient(
            httpClient = http,
            settings = settings,
            onAttributionData = { callbacks.add(it) },
        ).apply {
            init("pk_test", "https://collector.example.test")
        }
    }

    private fun MockRequestHandleScope.matched(clickId: String, deepLink: String): HttpResponseData {
        return respond(
            content = """{"click_id":"$clickId","deep_link":"$deepLink"}""",
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )
    }

    private fun MockRequestHandleScope.notFound(): HttpResponseData {
        return respond("{}", HttpStatusCode.NotFound)
    }
}
