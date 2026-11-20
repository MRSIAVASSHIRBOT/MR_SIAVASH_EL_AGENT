package ir.mrsiavash.elagent

import android.Manifest
import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.content.pm.PackageManager
import android.os.Build
import android.view.ViewGroup
import android.widget.Button
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
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
    private lateinit var voiceController: LocalVoiceController
    private lateinit var voiceButton: Button
    private lateinit var cloudApiKey: EditText
    private lateinit var cloudModel: EditText
    private lateinit var chatPrompt: EditText
    private lateinit var chatMode: Spinner
    private lateinit var chatOutput: TextView
    private lateinit var chatSendButton: Button
    private var voiceActive = false
    private val recordAudioRequest = 4102

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
            text = "Android companion · v0.4.0"
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
        voiceButton = Button(this).apply {
            text = "شروع فرمان صوتی (محلی)"
            backgroundTintList = ColorStateList.valueOf(Color.rgb(19, 185, 129))
            setTextColor(Color.BLACK)
            setOnClickListener { toggleVoice() }
        }
        root.addView(voiceButton, buttonParams())

        root.addView(TextView(this).apply {
            text = "دستیار هوشمند · آفلاین / آنلاین / ترکیبی"
            textSize = 17f
            setTextColor(Color.rgb(53, 214, 160))
        }, fieldParams())
        cloudApiKey = EditText(this).apply {
            hint = "OpenAI API key (stored encrypted on this device)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(145, 170, 158))
            setSingleLine(true)
        }
        root.addView(cloudApiKey, fieldParams())
        root.addView(Button(this).apply {
            text = "ذخیره امن کلید آنلاین"
            setOnClickListener {
                runCatching { store.saveCloudApiKey(cloudApiKey.text.toString()) }
                    .onSuccess { cloudApiKey.text.clear(); setStatus("کلید در Android Keystore رمزگذاری و ذخیره شد.") }
                    .onFailure { setStatus(it.message ?: "ذخیره کلید ناموفق بود.") }
            }
        }, buttonParams())
        root.addView(Button(this).apply {
            text = "حذف کلید آنلاین"
            setOnClickListener {
                store.clearCloudApiKey()
                cloudApiKey.text.clear()
                setStatus("کلید آنلاین از حافظه رمزگذاری‌شده حذف شد.")
            }
        }, buttonParams())
        cloudModel = EditText(this).apply {
            hint = "Cloud model (default: gpt-4o-mini)"
            setText("gpt-4o-mini")
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(145, 170, 158))
            setSingleLine(true)
        }
        root.addView(cloudModel, fieldParams())
        chatMode = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Offline", "Online", "Hybrid")
            )
        }
        root.addView(chatMode, fieldParams())
        root.addView(TextView(this).apply {
            text = "Online/Hybrid فقط متن واردشده را ارسال می‌کند؛ هزینه API ممکن است جداگانه محاسبه شود."
            textSize = 12f
            setTextColor(Color.rgb(190, 205, 196))
        }, fieldParams())
        chatPrompt = EditText(this).apply {
            hint = "پیام یا فرمانت را بنویس…"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(145, 170, 158))
            minLines = 2
            maxLines = 4
        }
        root.addView(chatPrompt, fieldParams())
        chatSendButton = Button(this).apply {
            text = "ارسال درخواست"
            backgroundTintList = ColorStateList.valueOf(Color.rgb(19, 185, 129))
            setTextColor(Color.BLACK)
            setOnClickListener { sendChat() }
        }
        root.addView(chatSendButton, buttonParams())
        chatOutput = TextView(this).apply {
            text = "Offline: اجرای فرمان‌های محلی مجاز. Online: پردازش ابری با انتخاب صریح. Hybrid: آنلاین و سپس fallback محلی."
            textSize = 14f
            setTextColor(Color.rgb(232, 245, 239))
            setTextIsSelectable(true)
        }
        root.addView(chatOutput, fieldParams())

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
        voiceController = LocalVoiceController(this, ::setStatus, ::handleVoiceTranscript, ::finishVoiceUi)
        if (HubClient(store).isPaired()) {
            setStatus("Paired with Hub. Periodic heartbeat is scheduled.")
            scheduleHeartbeat()
        }
    }


    private fun sendChat() {
        val prompt = chatPrompt.text.toString().trim()
        if (prompt.isBlank()) {
            setStatus("ابتدا پیام یا فرمان را وارد کن.")
            return
        }
        val mode = chatMode.selectedItem?.toString() ?: "Offline"
        val model = cloudModel.text.toString().trim()
        chatSendButton.isEnabled = false
        chatOutput.text = "در حال پردازش درخواست در حالت $mode…"
        executor.execute {
            val result = when (mode) {
                "Offline" -> runOfflineCommand(prompt)
                "Online" -> runCatching {
                    OpenAiChatProvider(store.cloudApiKey(), model).chat(prompt)
                }.getOrElse { "خطای سرویس آنلاین: ${it.message ?: "unknown error"}" }
                else -> runCatching {
                    OpenAiChatProvider(store.cloudApiKey(), model).chat(prompt)
                }.getOrElse { error ->
                    val local = runOfflineCommand(prompt)
                    if (local.startsWith("OFFLINE_UNSUPPORTED")) {
                        "سرویس آنلاین در دسترس نبود (${error.message ?: "error"}). مدل زبانی آفلاین روی گوشی نصب نشده؛ فقط فرمان‌های محلی مجاز قابل اجرا هستند."
                    } else {
                        "سرویس آنلاین در دسترس نبود؛ fallback محلی انجام شد. $local"
                    }
                }
            }
            runOnUiThread {
                chatOutput.text = result
                chatSendButton.isEnabled = true
                setStatus("پردازش درخواست پایان یافت · $mode")
            }
        }
    }

    private fun runOfflineCommand(prompt: String): String {
        val alias = VoiceIntentResolver.resolve(prompt)
            ?: return "OFFLINE_UNSUPPORTED: مدل زبانی آفلاین روی این نسخه نصب نیست؛ در حالت Offline فقط فرمان‌های صریح و مجاز پشتیبانی می‌شوند."
        runOnUiThread {
            runCatching { launchAllowlisted(alias) }
                .onFailure { setStatus(it.message ?: "اجرای فرمان محلی ناموفق بود.") }
        }
        return if (alias == "chatgpt") "فرمان محلی مجاز: ChatGPT باز می‌شود."
        else "فرمان محلی مجاز: مرورگر باز می‌شود."
    }

    private fun toggleVoice() {
        if (voiceActive) {
            voiceController.stop()
            voiceButton.isEnabled = false
            voiceButton.text = "در حال پردازش…"
            setStatus("ضبط متوقف شد؛ نتیجه در حال پردازش محلی است.")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            setStatus("فرمان صوتی کاملاً محلی به Android 12 یا جدیدتر نیاز دارد.")
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), recordAudioRequest)
            return
        }
        beginVoice()
    }

    private fun beginVoice() {
        voiceActive = voiceController.start()
        voiceButton.text = if (voiceActive) "توقف فرمان صوتی" else "شروع فرمان صوتی (محلی)"
        if (!voiceActive) setStatus("تشخیص گفتار محلی در دسترس نیست؛ هیچ صدایی به Cloud ارسال نشد.")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == recordAudioRequest) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) beginVoice()
            else setStatus("مجوز میکروفون داده نشد؛ قابلیت صوتی غیرفعال ماند.")
        }
    }

    private fun finishVoiceUi() {
        voiceActive = false
        if (::voiceButton.isInitialized) {
            voiceButton.isEnabled = true
            voiceButton.text = "شروع فرمان صوتی (محلی)"
        }
    }

    private fun handleVoiceTranscript(transcript: String) {
        voiceActive = false
        voiceButton.text = "شروع فرمان صوتی (محلی)"
        setStatus("متن تشخیص‌داده‌شده: " + transcript)
        val alias = VoiceIntentResolver.resolve(transcript)
        if (alias == null) {
            val message = "این فرمان در فهرست مجاز نیست؛ هیچ عملیاتی اجرا نشد."
            setStatus(message)
            voiceController.speak(message)
            return
        }
        runCatching { launchAllowlisted(alias) }
            .onSuccess {
                val message = if (alias == "chatgpt") "ChatGPT باز شد." else "مرورگر باز شد."
                setStatus(message)
                voiceController.speak(message)
            }
            .onFailure {
                val message = "اجرای فرمان ناموفق بود: " + (it.message ?: "خطای نامشخص")
                setStatus(message)
                voiceController.speak(message)
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

    override fun onPause() {
        if (voiceActive) {
            voiceController.cancel()
            voiceActive = false
            if (::voiceButton.isInitialized) {
                voiceButton.isEnabled = true
                voiceButton.text = "شروع فرمان صوتی (محلی)"
            }
        }
        super.onPause()
    }

    override fun onDestroy() {
        if (::voiceController.isInitialized) voiceController.close()
        executor.shutdown()
        super.onDestroy()
    }
}
