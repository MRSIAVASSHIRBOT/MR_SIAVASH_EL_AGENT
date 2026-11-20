package ir.mrsiavash.elagent

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var store: SecureStore
    private lateinit var status: TextView
    private lateinit var hubUrl: EditText
    private lateinit var pairingCode: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SecureStore(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(7, 19, 15))
            setPadding(28, 36, 28, 28)
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        root.addView(TextView(this).apply {
            text = "MR SIAVASH EL AGENT"
            textSize = 24f
            setTextColor(Color.rgb(232, 245, 239))
        })
        root.addView(TextView(this).apply {
            text = "Android companion · v0.3.0"
            textSize = 14f
            setTextColor(Color.rgb(168, 196, 183))
        })
        root.addView(TextView(this).apply {
            text = "Device ID: ${store.deviceId()}"
            textSize = 12f
            setTextColor(Color.rgb(53, 214, 160))
            setTextIsSelectable(true)
        })
        hubUrl = EditText(this).apply {
            hint = "Hub HTTPS address (https://...)"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(145, 170, 158))
            setSingleLine(true)
        }
        pairingCode = EditText(this).apply {
            hint = "One-time pairing code"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(145, 170, 158))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        root.addView(hubUrl, fieldParams())
        root.addView(pairingCode, fieldParams())
        root.addView(Button(this).apply {
            text = "Pair with Hub"
            backgroundTintList = ColorStateList.valueOf(Color.rgb(19, 185, 129))
            setTextColor(Color.BLACK)
            setOnClickListener {
                val url = hubUrl.text.toString()
                val code = pairingCode.text.toString()
                setBusy("Pairing…")
                executor.execute {
                    runCatching { HubClient(store).pair(url, code) }
                        .onSuccess { message -> runOnUiThread { pairingCode.text.clear(); setStatus(message); scheduleHeartbeat() } }
                        .onFailure { error -> runOnUiThread { setStatus(error.message ?: "Pairing failed.") } }
                }
            }
        }, buttonParams())
        root.addView(Button(this).apply {
            text = "Send heartbeat now"
            backgroundTintList = ColorStateList.valueOf(Color.rgb(19, 185, 129))
            setTextColor(Color.BLACK)
            setOnClickListener {
                setBusy("Contacting Hub…")
                executor.execute {
                    runCatching { HubClient(store).heartbeat() }
                        .onSuccess { message -> runOnUiThread { setStatus(message) } }
                        .onFailure { error -> runOnUiThread { setStatus(error.message ?: "Heartbeat failed.") } }
                }
            }
        }, buttonParams())
        root.addView(Button(this).apply {
            text = "Open ChatGPT"
            backgroundTintList = ColorStateList.valueOf(Color.rgb(13, 33, 25))
            setTextColor(Color.rgb(53, 214, 160))
            setOnClickListener {
                runCatching { launchAllowlisted("chatgpt") }
                    .onFailure { setStatus(it.message ?: "Could not open ChatGPT.") }
            }
        }, buttonParams())
        root.addView(Button(this).apply {
            text = "Open browser"
            backgroundTintList = ColorStateList.valueOf(Color.rgb(13, 33, 25))
            setTextColor(Color.rgb(53, 214, 160))
            setOnClickListener {
                runCatching { launchAllowlisted("browser") }
                    .onFailure { setStatus(it.message ?: "Could not open browser.") }
            }
        }, buttonParams())
        root.addView(Button(this).apply {
            text = "Unpair locally"
            backgroundTintList = ColorStateList.valueOf(Color.rgb(74, 36, 31))
            setTextColor(Color.rgb(255, 220, 210))
            setOnClickListener {
                store.clearCredentialsPreservingIdentity()
                WorkManager.getInstance(this@MainActivity).cancelUniqueWork("el-agent-heartbeat")
                setStatus("Local credentials cleared. Revoke the device in Hub to fully disconnect.")
            }
        }, buttonParams())
        status = TextView(this).apply { text = "Not connected"; textSize = 14f; setTextColor(Color.rgb(168, 196, 183)) }
        root.addView(status, fieldParams())
        if (HubClient(store).isPaired()) {
            setStatus("Paired with Hub. Periodic heartbeat is scheduled.")
            scheduleHeartbeat()
        }
    }

    private fun launchAllowlisted(alias: String) {
        when (alias) {
            "chatgpt" -> {
                val appIntent = packageManager.getLaunchIntentForPackage("com.openai.chatgpt")
                if (appIntent != null) startActivity(appIntent)
                else startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/")))
            }
            "browser" -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/")))
            else -> throw IllegalArgumentException("Unsupported Android app alias.")
        }
    }

    private fun fieldParams() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        bottomMargin = 16
    }
    private fun buttonParams() = fieldParams()
    private fun setBusy(message: String) = runOnUiThread { status.text = message }
    private fun setStatus(message: String) { status.text = message }

    private fun scheduleHeartbeat() {
        val request = PeriodicWorkRequestBuilder<HeartbeatWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "el-agent-heartbeat", ExistingPeriodicWorkPolicy.KEEP, request
        )
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}
