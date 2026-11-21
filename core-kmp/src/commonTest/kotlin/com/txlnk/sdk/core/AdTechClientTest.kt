package com.txlnk.sdk.core

import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdTechClientTest {
    @Test
    fun matchDevice_on200_persistsMatchAndCallback() {
        runBlocking {
            val settings = MapSettings()
            var callbackPayload: String? = null

            val engine = MockEngine {
                respond(
                    content = """{"click_id":"clk_123","deep_link":"tixlnk://product/1","data":{"campaign":"summer"}}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }

            val client = AdTechClient(
                httpClient = testClient(engine),
                settings = settings,
                onAttributionData = { callbackPayload = it },
            )

            client.init("pk_test", "https://collector.txlnk.com")
            client.setDeferredLinkToken("tok-abc")
            val result = client.matchDevice()

            assertEquals("clk_123", result.clickId)
            assertEquals("tixlnk://product/1", result.deepLink)
            assertFalse(result.isOrganic)
            assertTrue(result.wasFreshMatch)
            assertEquals("clk_123", client.getStoredClickId())
            assertEquals("tixlnk://product/1", client.getStoredDeepLink())
            assertNotNull(client.getStoredAttributionDataJson())
            assertNotNull(callbackPayload)
        }
    }

    @Test
    fun matchDevice_sendsTokenInPayload() {
        runBlocking {
            val settings = MapSettings()
            var sentToken: String? = null
            var sentKeys: Set<String> = emptySet()

            val engine = MockEngine { request ->
                val body = (request.body as TextContent).text
                val sent = Json.parseToJsonElement(body).jsonObject
                sentToken = sent["token"]?.jsonPrimitive?.content
                sentKeys = sent.keys
                respond(
                    content = """{"click_id":"clk_9","deep_link":"tixlnk://x"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }

            val client = AdTechClient(
                httpClient = testClient(engine),
                settings = settings,
            )

            client.init("pk_test", "https://collector.txlnk.com")
            client.setDeferredLinkToken("tok-match-1")
            client.matchDevice()

            assertEquals("tok-match-1", sentToken)
            // No device signals: the match must not fingerprint the device.
            assertEquals(setOf("token"), sentKeys)
        }
    }

    @Test
    fun matchDevice_on404_marksOrganicAndKeepsClickIdNull() {
        runBlocking {
            val settings = MapSettings()

            val engine = MockEngine {
                respond(
                    content = "{}",
                    status = HttpStatusCode.NotFound,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }

            val client = AdTechClient(
                httpClient = testClient(engine),
                settings = settings,
            )

            client.init("pk_test", "https://collector.txlnk.com")
            val result = client.matchDevice()

            assertTrue(result.isOrganic)
            assertNull(result.clickId)
            assertNull(client.getStoredClickId())
        }
    }

    @Test
    fun matchDevice_secondCall_usesStoredStateWithoutExtraNetwork() {
        runBlocking {
            val settings = MapSettings()
            var networkCount = 0

            val engine = MockEngine {
                networkCount += 1
                respond(
                    content = """{"click_id":"clk_abc","deep_link":"tixlnk://a"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }

            val client = AdTechClient(
                httpClient = testClient(engine),
                settings = settings,
            )

            client.init("pk_test", "https://collector.txlnk.com")

            val first = client.matchDevice()
            val second = client.matchDevice()

            assertEquals(1, networkCount)
            assertTrue(first.wasFreshMatch)
            assertFalse(second.wasFreshMatch)
            assertEquals("clk_abc", second.clickId)
        }
    }

    @Test
    fun matchDevice_whenLegacyStateExists_recoversStoredClickIdWithoutNetwork() {
        runBlocking {
            val settings = MapSettings().apply {
                putBoolean("adtech.match_done", true)
                putString("adtech.click_id", "legacy_clk_1")
                // older clients may not have set this key yet
                remove("adtech.is_organic")
            }

            var networkCount = 0
            val engine = MockEngine {
                networkCount += 1
                respond(
                    content = "{}",
                    status = HttpStatusCode.InternalServerError,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }

            val client = AdTechClient(
                httpClient = testClient(engine),
                settings = settings,
            )

            client.init("pk_test", "https://collector.txlnk.com")
            val result = client.matchDevice()

            assertEquals(0, networkCount)
            assertEquals("legacy_clk_1", result.clickId)
            assertFalse(result.isOrganic)
            assertFalse(result.wasFreshMatch)
        }
    }

    @Test
    fun matchDevice_whenStoredAttributionJsonIsMalformed_doesNotCrash() {
        runBlocking {
            val settings = MapSettings().apply {
                putBoolean("adtech.match_done", true)
                putString("adtech.click_id", "clk_x")
                putString("adtech.attribution_data", "{malformed")
            }

            val client = AdTechClient(
                httpClient = testClient(MockEngine { error("should not call network") }),
                settings = settings,
            )

            client.init("pk_test", "https://collector.txlnk.com")
            val result = client.matchDevice()

            assertEquals("clk_x", result.clickId)
            assertNull(result.attributionData)
            assertFalse(result.isOrganic)
            assertFalse(result.wasFreshMatch)
        }
    }

    @Test
    fun setDeferredLinkUrl_readsTokenFromRedirectWithoutFollowingIt() {
        runBlocking {
            val requested = mutableListOf<String>()
            var linkMethod: HttpMethod? = null
            var sentToken: String? = null

            val engine = MockEngine { request ->
                val url = request.url.toString()
                requested.add(url)
                if (url == "https://go.example.test/summer-sale") {
                    linkMethod = request.method
                    respond(
                        content = "",
                        status = HttpStatusCode.Found,
                        headers = headersOf(
                            HttpHeaders.Location to listOf("https://store.example.test/fallback"),
                            "X-Tixlnk-Match-Token" to listOf(" tok-from-link "),
                        ),
                    )
                } else if (url.endsWith("/v1/device/match")) {
                    val body = (request.body as TextContent).text
                    sentToken = Json.parseToJsonElement(body).jsonObject["token"]?.jsonPrimitive?.content
                    respond(
                        content = """{"click_id":"clk_link","deep_link":"tixlnk://offer/1"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                } else {
                    error("unexpected request to $url")
                }
            }

            val client = AdTechClient(
                httpClient = testClient(engine),
                settings = MapSettings(),
            )

            client.setDeferredLinkUrl("https://go.example.test/summer-sale")
            client.init("pk_test", "https://collector.txlnk.com")
            val result = client.matchDevice()

            assertEquals(HttpMethod.Get, linkMethod)
            assertEquals(
                listOf("https://go.example.test/summer-sale", "https://collector.txlnk.com/v1/device/match"),
                requested,
            )
            assertEquals("tok-from-link", sentToken)
            assertEquals("tixlnk://offer/1", result.deepLink)
        }
    }

    @Test
    fun setDeferredLinkUrl_rejectsNonHttpUrlsWithoutNetwork() {
        runBlocking {
            val client = AdTechClient(
                httpClient = testClient(MockEngine { error("should not call network") }),
                settings = MapSettings(),
            )

            val invalid = listOf("tixlnk://offer", "go.example.test/offer", "", "https://")
            for (url in invalid) {
                assertFailsWith<IllegalArgumentException>(url) {
                    client.setDeferredLinkUrl(url)
                }
            }
        }
    }

    @Test
    fun setDeferredLinkUrl_failsWhenResponseIsNotATokenRedirect() {
        runBlocking {
            var matchBody: String? = null
            val engine = MockEngine { request ->
                when (request.url.encodedPath) {
                    "/no-header" -> respond(
                        content = "",
                        status = HttpStatusCode.Found,
                        headers = headersOf(HttpHeaders.Location, "https://store.example.test"),
                    )

                    "/v1/device/match" -> {
                        matchBody = (request.body as TextContent).text
                        respond(content = "{}", status = HttpStatusCode.NotFound)
                    }

                    else -> respond(
                        content = "ok",
                        status = HttpStatusCode.OK,
                        headers = headersOf("X-Tixlnk-Match-Token", "tok-on-200"),
                    )
                }
            }

            val client = AdTechClient(
                httpClient = testClient(engine),
                settings = MapSettings(),
            )

            assertFailsWith<IllegalStateException> {
                client.setDeferredLinkUrl("https://go.example.test/no-header")
            }
            assertFailsWith<IllegalStateException> {
                client.setDeferredLinkUrl("https://go.example.test/plain-200")
            }

            client.init("pk_test", "https://collector.txlnk.com")
            val result = client.matchDevice()

            assertTrue(result.isOrganic)
            val sent = Json.parseToJsonElement(assertNotNull(matchBody)).jsonObject
            assertFalse(sent.containsKey("token"))
        }
    }

    @Test
    fun track_sendsEventIdAndValueWhenProvided() {
        runBlocking {
            var body: String? = null
            val engine = MockEngine { request ->
                body = (request.body as TextContent).text
                respond(content = "", status = HttpStatusCode.Created)
            }
            val client = AdTechClient(
                httpClient = testClient(engine),
                settings = MapSettings(),
            )

            client.init("pk_test", "https://collector.txlnk.com")
            client.track(
                AdTechEvent(
                    name = "purchase",
                    properties = mapOf("sku" to "ticket_vip"),
                    eventId = "order-42",
                    valueCents = 12990,
                    currency = "BRL",
                ),
            )

            val sent = Json.parseToJsonElement(assertNotNull(body)).jsonObject
            assertEquals("purchase", sent["name"]?.jsonPrimitive?.content)
            assertEquals("order-42", sent["event_id"]?.jsonPrimitive?.content)
            assertEquals("12990", sent["value_cents"]?.jsonPrimitive?.content)
            assertEquals("BRL", sent["currency"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun track_omitsOptionalFieldsEvenWhenJsonEncodesDefaultsAndNulls() {
        runBlocking {
            var body: String? = null
            val engine = MockEngine { request ->
                body = (request.body as TextContent).text
                respond(content = "", status = HttpStatusCode.Created)
            }
            val httpClient = HttpClient(engine) {
                install(ContentNegotiation) {
                    json(
                        Json {
                            encodeDefaults = true
                            explicitNulls = true
                        },
                    )
                }
            }
            val client = AdTechClient(
                httpClient = httpClient,
                settings = MapSettings(),
            )

            client.init("pk_test", "https://collector.txlnk.com")
            client.track(AdTechEvent(name = "signup", timestamp = 1L, properties = mapOf("method" to "email")))

            val sent = Json.parseToJsonElement(assertNotNull(body)).jsonObject
            assertEquals(setOf("name", "timestamp", "properties"), sent.keys)
        }
    }

    @Test
    fun track_rejectsInvalidEventIdAndValueWithoutNetwork() {
        runBlocking {
            val client = AdTechClient(
                httpClient = testClient(MockEngine { error("should not call network") }),
                settings = MapSettings(),
            )
            client.init("pk_test", "https://collector.txlnk.com")

            val invalid = listOf(
                AdTechEvent(name = "purchase", eventId = ""),
                AdTechEvent(name = "purchase", eventId = " order-1"),
                AdTechEvent(name = "purchase", eventId = "a".repeat(129)),
                AdTechEvent(name = "purchase", valueCents = 100),
                AdTechEvent(name = "purchase", currency = "BRL"),
                AdTechEvent(name = "purchase", valueCents = -1, currency = "BRL"),
                AdTechEvent(name = "purchase", valueCents = 100, currency = "brl"),
                AdTechEvent(name = "purchase", valueCents = 100, currency = "REAL"),
            )
            for (event in invalid) {
                assertFailsWith<IllegalArgumentException>(event.toString()) {
                    client.track(event)
                }
            }
        }
    }

    private fun testClient(engine: MockEngine): HttpClient {
        return HttpClient(engine) {
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
    }
}
