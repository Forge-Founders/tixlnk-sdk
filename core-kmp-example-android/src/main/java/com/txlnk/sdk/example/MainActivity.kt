package com.txlnk.sdk.example

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.russhwolf.settings.SharedPreferencesSettings
import com.txlnk.sdk.core.AdTechClient
import com.txlnk.sdk.core.AdTechEvent
import com.txlnk.sdk.core.PlayInstallReferrer
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var statusView: TextView
    private lateinit var timelineView: TextView
    private lateinit var apiKeyInput: EditText
    private lateinit var endpointInput: EditText
    private lateinit var deferredTokenInput: EditText
    private lateinit var adTechClient: AdTechClient

    private val localEndpoint = "http://10.0.2.2:58080"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val settings = SharedPreferencesSettings(getSharedPreferences("adtech_example", MODE_PRIVATE))
        adTechClient = AdTechClient(settings = settings) { attributionJson ->
            append("Deferred attribution callback: $attributionJson")
        }

        val root = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val intro = TextView(this).apply {
            text = "KMP Example App\n\n" +
                "Enter an app key from TixLnk > Apps. matchDevice reads the Play install " +
                "referrer. Pasting a token is optional."
        }

        apiKeyInput = EditText(this).apply {
            hint = "App API key (pk_live_...)"
            isSingleLine = true
        }
        endpointInput = EditText(this).apply {
            hint = "API endpoint"
            setText(localEndpoint)
            isSingleLine = true
        }
        deferredTokenInput = EditText(this).apply {
            hint = "Optional match token"
            isSingleLine = true
        }

        val initButton = Button(this).apply {
            text = "Init SDK"
            setOnClickListener {
                runInit()
            }
        }

        val matchButton = Button(this).apply {
            text = "Run matchDevice (deferred flow)"
            setOnClickListener {
                runMatchDevice()
            }
        }

        val trackButton = Button(this).apply {
            text = "Track purchase"
            setOnClickListener {
                runTrackPurchase()
            }
        }

        statusView = TextView(this).apply {
            text = "Status: Idle"
        }

        timelineView = TextView(this).apply {
            text = "Timeline:\n"
        }

        content.addView(intro)
        content.addView(apiKeyInput)
        content.addView(endpointInput)
        content.addView(deferredTokenInput)
        content.addView(initButton)
        content.addView(matchButton)
        content.addView(trackButton)
        content.addView(statusView)
        content.addView(timelineView)
        root.addView(content)
        setContentView(root)
    }

    private fun runInit() {
        runCatching {
            adTechClient.init(
                apiKey = apiKeyInput.text.toString().trim(),
                endpoint = endpointInput.text.toString().trim(),
            )
        }.onSuccess {
            append("SDK initialized at ${endpointInput.text.toString().trim()}")
        }.onFailure { error ->
            append("Init failed: ${error.message}")
        }
    }

    private fun runMatchDevice() {
        val pasted = deferredTokenInput.text.toString().trim()
        lifecycleScope.launch {
            if (pasted.isNotEmpty()) {
                adTechClient.setDeferredLinkToken(pasted)
                append("Using the token entered on screen")
            } else {
                val token = PlayInstallReferrer.readToken(applicationContext)
                if (token.isNullOrBlank()) {
                    append("No Play referrer. The match continues without a token")
                } else {
                    adTechClient.setDeferredLinkToken(token)
                    append("Using the Play install referrer")
                }
            }
            runCatching {
                adTechClient.matchDevice()
            }.onSuccess { result ->
                append(
                    "matchDevice -> deepLink=${result.deepLink}, clickId=${result.clickId}, " +
                    "isOrganic=${result.isOrganic}, fresh=${result.wasFreshMatch}",
                )
            }.onFailure { error ->
                append("matchDevice failed: ${error.message}")
            }
        }
    }

    private fun runTrackPurchase() {
        lifecycleScope.launch {
            runCatching {
                adTechClient.track(
                    AdTechEvent(
                        name = "purchase",
                        properties = mapOf(
                            "value" to "29.90",
                            "currency" to "BRL",
                            "sku" to "ticket_vip",
                        ),
                    ),
                )
            }.onSuccess {
                append("Track purchase sent")
            }.onFailure { error ->
                append("Track failed: ${error.message}")
            }
        }
    }

    private fun append(message: String) {
        statusView.text = "Status: $message"
        val line = "${System.currentTimeMillis()} | $message"
        timelineView.text = "$line\n${timelineView.text}"
    }
}
