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
    private var voiceActive = false
    private val recordAudioRequest = 4102
    private val openDocumentRequest = 4103
    private lateinit var documentPreview: TextView
    private lateinit var plannerApiKey: EditText
    private lateinit var plannerModel: EditText
    private lateinit var plannerRequest: EditText
    private lateinit var plannerButton: Button
    private lateinit var plannerOutput: TextView

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
            text = "انتخاب و پیش‌نمایش فایل متنی"
            backgroundTintList = ColorStateList.valueOf(Color.rgb(13, 33, 25))
            setTextColor(Color.rgb(53, 214, 160))
            setOnClickListener {
                runCatching {
                    startActivityForResult(AndroidDocumentAccess.createOpenDocumentIntent(), openDocumentRequest)
                }.onFailure { setStatus(it.message ?: "بازکردن انتخاب‌گر فایل ناموفق بود.") }
            }
        }, buttonParams())
        documentPreview = TextView(this).apply {
            text = "فایل فقط پس از انتخاب مستقیم شما خوانده می‌شود. پیش‌نمایش متنی حداکثر ۶۴ کیلوبایت است و به Cloud ارسال نمی‌شود."
            textSize = 13f
            setTextColor(Color.rgb(210, 226, 217))
            setTextIsSelectable(true)
        }
        root.addView(documentPreview, fieldParams())
        root.addView(TextView(this).apply {
            text = "برنامه‌ریز هوشمند · تبدیل درخواست به مراحل ساختاریافته"
            textSize = 17f
            setTextColor(Color.rgb(53, 214, 160))
        }, fieldParams())
        plannerApiKey = EditText(this).apply {
            hint = "کلید OpenAI API (ذخیره‌شده به‌صورت رمزگذاری‌شده)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(145, 170, 158))
            setSingleLine(true)
        }
        root.addView(plannerApiKey, fieldParams())
        root.addView(Button(this).apply {
            text = "ذخیره امن کلید API"
            setOnClickListener {
                runCatching { store.saveCloudApiKey(plannerApiKey.text.toString()) }
                    .onSuccess {
                        plannerApiKey.text.clear()
                        setStatus("کلید در محفظهٔ رمزگذاری‌شدهٔ Android ذخیره شد.")
                    }
                    .onFailure { setStatus(it.message ?: "ذخیرهٔ کلید ناموفق بود.") }
            }
        }, buttonParams())
        root.addView(Button(this).apply {
            text = "حذف کلید API"
            setOnClickListener {
                runCatching { store.clearCloudApiKey() }
                    .onSuccess {
                        plannerApiKey.text.clear()
                        setStatus("کلید API از حافظهٔ رمزگذاری‌شده حذف شد.")
                    }
                    .onFailure { setStatus(it.message ?: "حذف کلید ناموفق بود.") }
            }
        }, buttonParams())
        plannerModel = EditText(this).apply {
            hint = "شناسهٔ مدل فعال در حساب API"
            setText("gpt-6.1-terra")
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(145, 170, 158))
            setSingleLine(true)
        }
        root.addView(plannerModel, fieldParams())
        root.addView(TextView(this).apply {
            text = "درخواست فقط برای ساخت طرح به API ارسال می‌شود؛ فایل، تصویر، صدای میکروفون و حافظه ارسال نمی‌شوند. استفاده از API ممکن است هزینهٔ جداگانه داشته باشد."
            textSize = 12f
            setTextColor(Color.rgb(190, 205, 196))
        }, fieldParams())
        plannerRequest = EditText(this).apply {
            hint = "درخواستت را بنویس؛ مثلاً: مرورگر را باز کن و این صفحه را نمایش بده."
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(145, 170, 158))
            minLines = 3
            maxLines = 5
        }
        root.addView(plannerRequest, fieldParams())
        plannerButton = Button(this).apply {
            text = "ساخت برنامهٔ اجرایی (بدون اجرا)"
            backgroundTintList = ColorStateList.valueOf(Color.rgb(19, 185, 129))
            setTextColor(Color.BLACK)
            setOnClickListener { generatePlan() }
        }
        root.addView(plannerButton, buttonParams())
        plannerOutput = TextView(this).apply {
            text = "طرح ساخته‌شده اینجا نمایش داده می‌شود. هیچ مرحله‌ای صرفاً با تولید طرح اجرا نخواهد شد."
            textSize = 14f
            setTextColor(Color.rgb(232, 245, 239))
            setTextIsSelectable(true)
        }
        root.addView(plannerOutput, fieldParams())
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


    private fun generatePlan() {
        val requestText = plannerRequest.text.toString()
        if (requestText.isBlank() || requestText.length > 4_000) {
            setStatus("درخواست باید بین ۱ تا ۴۰۰۰ نویسه باشد.")
            return
        }
        val key = runCatching { store.cloudApiKey() }.getOrElse {
            setStatus("خواندن کلید امن API ناموفق بود.")
            return
        }
        if (key.isBlank()) {
            setStatus("ابتدا کلید API خود را در همین برنامه ذخیره کنید.")
            return
        }
        val model = plannerModel.text.toString().trim()
        plannerButton.isEnabled = false
        plannerOutput.text = "در حال ساخت طرح ساختاریافته…"
        setStatus("درخواست متنی به مدل آنلاین ارسال شد.")
        executor.execute {
            val result = runCatching {
                val provider = OpenAiChatProvider(key, model)
                val raw = provider.chatJson(
                    AgentPlanPrompt.SYSTEM_PROMPT,
                    AgentPlanPrompt.userMessage(requestText)
                )
                AgentPlanParser.parse(raw)
            }
            runOnUiThread {
                plannerButton.isEnabled = true
                result.onSuccess { plan ->
                    plannerOutput.text = AgentPlanPolicy.render(plan)
                    setStatus("طرح دریافت و اعتبارسنجی شد؛ هیچ مرحله‌ای اجرا نشده است.")
                }.onFailure { error ->
                    plannerOutput.text = "ساخت یا اعتبارسنجی طرح ناموفق بود: ${error.message ?: "خطای نامشخص"}"
                    setStatus("طرح نامعتبر یا سرویس آنلاین ناموفق بود.")
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != openDocumentRequest || resultCode != RESULT_OK) return
        val uri = data?.data ?: run {
            setStatus("فایلی انتخاب نشد.")
            return
        }
        val resultFlags = data?.flags ?: 0
        setBusy("در حال خواندن فایل انتخاب‌شده به‌صورت محلی…")
        executor.execute {
            val result = runCatching {
                val preview = AndroidDocumentAccess.readTextPreview(contentResolver, uri)
                val persisted = runCatching {
                    AndroidDocumentAccess.persistReadPermission(contentResolver, uri, resultFlags)
                }.getOrDefault(false)
                preview to persisted
            }
            runOnUiThread {
                result.onSuccess { (preview, persisted) ->
                    documentPreview.text = "نوع فایل: ${preview.mimeType}\nحجم خوانده‌شده: ${preview.bytesRead} بایت\nدسترسی پایدار: ${if (persisted) "بله" else "خیر؛ فقط دسترسی موقت انتخاب‌گر"}\n\n${preview.text}"
                    setStatus("پیش‌نمایش فایل فقط روی همین دستگاه آماده شد؛ هیچ محتوایی ارسال نشد.")
                }.onFailure { error ->
                    documentPreview.text = "پیش‌نمایش در دسترس نیست: ${error.message ?: "خطای نامشخص"}"
                    setStatus("خواندن فایل ناموفق بود.")
                }
            }
        }
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
