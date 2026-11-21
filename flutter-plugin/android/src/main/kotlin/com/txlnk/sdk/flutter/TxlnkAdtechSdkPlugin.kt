package com.txlnk.sdk.flutter

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

class TxlnkAdtechSdkPlugin : FlutterPlugin, MethodChannel.MethodCallHandler {
    private lateinit var methodChannel: MethodChannel
    private lateinit var deepLinkChannel: EventChannel
    private lateinit var attributionChannel: EventChannel
    private var deepLinkSink: EventChannel.EventSink? = null
    private var attributionSink: EventChannel.EventSink? = null
    private lateinit var mainHandler: Handler
    private lateinit var prefs: SharedPreferences
    private var appContext: Context? = null

    private var apiKey: String? = null
    private var endpoint: String? = null
    private var tenantId: String? = null
    private var appId: String? = null
    private var deferredToken: String? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        mainHandler = Handler(Looper.getMainLooper())
        appContext = binding.applicationContext
        prefs = binding.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        methodChannel = MethodChannel(binding.binaryMessenger, "com.txlnk.sdk/adtech")
        methodChannel.setMethodCallHandler(this)

        deepLinkChannel = EventChannel(binding.binaryMessenger, "com.txlnk.sdk/adtech/deeplink")
        deepLinkChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                deepLinkSink = events
            }

            override fun onCancel(arguments: Any?) {
                deepLinkSink = null
            }
        })

        attributionChannel = EventChannel(binding.binaryMessenger, "com.txlnk.sdk/adtech/attribution")
        attributionChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                attributionSink = events
                prefs.getString(KEY_ATTRIBUTION_DATA, null)?.let { cached ->
                    attributionSink?.success(cached)
                }
            }

            override fun onCancel(arguments: Any?) {
                attributionSink = null
            }
        })
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel.setMethodCallHandler(null)
        deepLinkChannel.setStreamHandler(null)
        attributionChannel.setStreamHandler(null)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "init" -> {
                apiKey = call.argument<String>("apiKey")
                endpoint = call.argument<String>("endpoint")
                tenantId = call.argument<String>("tenantId")
                appId = call.argument<String>("appId")
                if (apiKey.isNullOrBlank() || endpoint.isNullOrBlank()) {
                    result.error("invalid_args", "apiKey and endpoint are required", null)
                    return
                }
                beginMatch(result)
            }

            "setDeferredLinkToken" -> {
                call.argument<String>("token")?.trim()?.takeIf(String::isNotEmpty)?.let(::storeDeferredToken)
                result.success(null)
            }

            "setDeferredLinkUrl" -> resolveDeferredTokenFromUrl(
                call.argument<String>("url"),
                result,
            )

            "matchDevice" -> beginMatch(result)

            "getClickId" -> result.success(prefs.getString(KEY_CLICK_ID, null))

            "track" -> {
                if (apiKey.isNullOrBlank() || endpoint.isNullOrBlank()) {
                    result.error("not_initialized", "Call init() first", null)
                    return
                }

                val event = call.argument<String>("event")
                if (event.isNullOrBlank()) {
                    result.error("invalid_args", "event is required", null)
                    return
                }

                @Suppress("UNCHECKED_CAST")
                val metadata = call.argument<Map<String, Any?>>("metadata") ?: emptyMap()
                val clickId = call.argument<String>("click_id") ?: prefs.getString(KEY_CLICK_ID, null)
                val payload = JSONObject().apply {
                    put("name", event)
                    put("properties", JSONObject(metadata))
                    put("timestamp", System.currentTimeMillis())
                    if (!clickId.isNullOrBlank()) {
                        put("click_id", clickId)
                    }
                    call.argument<String>("event_id")?.let { put("event_id", it) }
                    (call.argument<Any>("value_cents") as? Number)?.let { put("value_cents", it.toLong()) }
                    call.argument<String>("currency")?.let { put("currency", it) }
                }

                sendTrack(payload, result)
            }

            "simulateDeferredDeepLink" -> {
                val deepLink = call.argument<String>("deepLink")
                deepLinkSink?.success(deepLink)
                result.success(null)
            }

            else -> result.notImplemented()
        }
    }

    private fun beginMatch(result: MethodChannel.Result) {
        if (prefs.getBoolean(KEY_MATCH_DONE, false) || !deferredToken.isNullOrBlank()) {
            matchDeviceIfNeeded(result)
            return
        }
        val context = appContext
        if (context == null) {
            matchDeviceIfNeeded(result)
            return
        }
        // A Universal/App Link token already stored wins. Play is only the
        // carrier for a first open after install, and a missing service must
        // not block the organic match. The referrer string is never logged.
        val delivered = AtomicBoolean(false)
        val client = InstallReferrerClient.newBuilder(context).build()
        var timeout: Runnable? = null
        fun finish(token: String?) {
            if (!delivered.compareAndSet(false, true)) {
                return
            }
            val pending = timeout
            if (pending != null) {
                mainHandler.removeCallbacks(pending)
            }
            runCatching { client.endConnection() }
            if (!token.isNullOrBlank() && deferredToken.isNullOrBlank()) {
                storeDeferredToken(token)
            }
            matchDeviceIfNeeded(result)
        }
        val scheduled = Runnable {
            Log.i(LOG_TAG, "Play install referrer timed out")
            finish(null)
        }
        timeout = scheduled
        try {
            client.startConnection(object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(responseCode: Int) {
                    Log.i(LOG_TAG, "Play install referrer setup finished code=$responseCode")
                    val token = if (responseCode == InstallReferrerClient.InstallReferrerResponse.OK) {
                        runCatching {
                            PlayReferrer.parseToken(client.installReferrer.installReferrer)
                        }.getOrNull()
                    } else {
                        null
                    }
                    mainHandler.post { finish(token) }
                }

                override fun onInstallReferrerServiceDisconnected() {
                    // The timeout still lets the match continue.
                }
            })
        } catch (_: Exception) {
            Log.i(LOG_TAG, "Play install referrer unavailable")
            finish(null)
            return
        }
        if (!delivered.get()) {
            mainHandler.postDelayed(scheduled, 2_500)
        }
    }

    private fun matchDeviceIfNeeded(result: MethodChannel.Result) {
        val endpoint = endpoint ?: run {
            result.error("not_initialized", "Call init() first", null)
            return
        }
        val apiKey = apiKey ?: run {
            result.error("not_initialized", "Call init() first", null)
            return
        }

        if (prefs.getBoolean(KEY_MATCH_DONE, false)) {
            prefs.getString(KEY_ATTRIBUTION_DATA, null)?.let { cached ->
                attributionSink?.success(cached)
            }
            result.success(null)
            return
        }

        val payload = JSONObject().apply {
            if (!deferredToken.isNullOrBlank()) {
                put("token", deferredToken)
            }
            if (!tenantId.isNullOrBlank()) {
                put("tenant_id", tenantId)
            }
            if (!appId.isNullOrBlank()) {
                put("app_id", appId)
            }
        }

        Thread {
            runCatching {
                val connection = openPost("${endpoint.trimEnd('/')}/v1/device/match", apiKey)
                OutputStreamWriter(connection.outputStream).use { writer ->
                    writer.write(payload.toString())
                    writer.flush()
                }

                when (connection.responseCode) {
                    200 -> {
                        val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                        val json = JSONObject(responseText)
                        val clickId = json.optString("click_id", "")
                        if (clickId.isBlank()) {
                            throw IllegalStateException("Device match succeeded without click_id")
                        }
                        val deepLink = json.optString("deep_link", "")
                        val attributionData = json.optJSONObject("data")?.toString()

                        prefs.edit()
                            .putBoolean(KEY_MATCH_DONE, true)
                            .putBoolean(KEY_IS_ORGANIC, false)
                            .putString(KEY_CLICK_ID, clickId)
                            .putString(KEY_DEEP_LINK, deepLink)
                            .putString(KEY_ATTRIBUTION_DATA, attributionData)
                            .apply()

                        if (deepLink.isNotBlank()) {
                            mainHandler.post { deepLinkSink?.success(deepLink) }
                        }
                        if (!attributionData.isNullOrBlank()) {
                            mainHandler.post { attributionSink?.success(attributionData) }
                        }
                    }

                    404 -> {
                        prefs.edit()
                            .putBoolean(KEY_MATCH_DONE, true)
                            .putBoolean(KEY_IS_ORGANIC, true)
                            .remove(KEY_CLICK_ID)
                            .remove(KEY_DEEP_LINK)
                            .remove(KEY_ATTRIBUTION_DATA)
                            .apply()
                    }

                    else -> {
                        val errorText = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                        throw IllegalStateException("HTTP ${connection.responseCode} during matchDevice: $errorText")
                    }
                }
            }.onSuccess {
                mainHandler.post { result.success(null) }
            }.onFailure { error ->
                mainHandler.post {
                    result.error("match_failed", error.message, null)
                }
            }
        }.start()
    }

    private fun resolveDeferredTokenFromUrl(rawUrl: String?, result: MethodChannel.Result) {
        val url = runCatching { URL(rawUrl?.trim()) }.getOrNull()
        if (url == null || (url.protocol != "http" && url.protocol != "https")) {
            result.error("invalid_link_url", "An HTTP(S) Universal/App Link URL is required", null)
            return
        }

        Thread {
            var connection: HttpURLConnection? = null
            runCatching {
                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = false
                    connectTimeout = 15_000
                    readTimeout = 15_000
                }
                val status = connection!!.responseCode
                val token = connection!!.getHeaderField("X-Tixlnk-Match-Token")
                if (status !in 300..399 || token.isNullOrBlank()) {
                    throw IllegalStateException(
                        "Link response HTTP $status did not include X-Tixlnk-Match-Token",
                    )
                }
                token
            }.onSuccess { token ->
                mainHandler.post {
                    storeDeferredToken(token)
                    result.success(null)
                }
            }.onFailure { error ->
                mainHandler.post {
                    result.error("link_token_resolve_failed", error.message, null)
                }
            }.also {
                connection?.disconnect()
            }
        }.start()
    }

    private fun storeDeferredToken(token: String) {
        deferredToken = token
        if (prefs.getBoolean(KEY_IS_ORGANIC, false)) {
            prefs.edit()
                .putBoolean(KEY_MATCH_DONE, false)
                .putBoolean(KEY_IS_ORGANIC, false)
                .apply()
        }
    }

    private fun sendTrack(payload: JSONObject, result: MethodChannel.Result) {
        val endpoint = endpoint ?: run {
            result.error("not_initialized", "Call init() first", null)
            return
        }
        val apiKey = apiKey ?: run {
            result.error("not_initialized", "Call init() first", null)
            return
        }

        Thread {
            runCatching {
                val connection = openPost("${endpoint.trimEnd('/')}/v1/s2s/events", apiKey)
                OutputStreamWriter(connection.outputStream).use { writer ->
                    writer.write(payload.toString())
                    writer.flush()
                }

                val status = connection.responseCode
                if (status !in 200..299) {
                    throw IllegalStateException("HTTP $status while tracking event")
                }
            }.onSuccess {
                mainHandler.post { result.success(null) }
            }.onFailure { error ->
                mainHandler.post {
                    result.error("track_failed", error.message, null)
                }
            }
        }.start()
    }

    private fun openPost(url: String, apiKey: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }
    }

    private companion object {
        private const val LOG_TAG = "TxlnkAdtech"
        private const val PREFS_NAME = "com.txlnk.sdk.adtech"
        private const val KEY_MATCH_DONE = "adtech.match_done"
        private const val KEY_CLICK_ID = "adtech.click_id"
        private const val KEY_DEEP_LINK = "adtech.deep_link"
        private const val KEY_ATTRIBUTION_DATA = "adtech.attribution_data"
        private const val KEY_IS_ORGANIC = "adtech.is_organic"
    }
}
