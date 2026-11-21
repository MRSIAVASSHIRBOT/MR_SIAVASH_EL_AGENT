package ir.mrsiavash.elagent

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.text.InputType
import android.widget.EditText
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * Owner-facing offline model import screen.
 *
 * Whisper models are currently import-only. The Qwen model can use the optional
 * llama.cpp native runtime when the device has sufficient available memory.
 */
class OfflineModelsActivity : Activity() {
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var modelStore: OfflineModelStore
    private var pendingModelId: String? = null
    private var pendingWhisperModelId: String? = null
    @Volatile private var cancelWhisperCapture = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        modelStore = OfflineModelStore(this)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 28)
            setBackgroundColor(Color.rgb(13, 19, 30))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
        setContentView(scroll)

        content.addView(TextView(this).apply {
            text = "مدیریت موتورهای آفلاین"
            textSize = 23f
            setTextColor(Color.rgb(95, 202, 176))
        }, params())

        content.addView(TextView(this).apply {
            text = "فایل مدل را فقط از منبع رسمی درج‌شده تهیه کن و از اینجا انتخابش کن. فایل اصلی در محل خودش باقی می‌ماند و یک نسخهٔ خصوصی داخل برنامه ذخیره می‌شود."
            textSize = 14f
            setTextColor(Color.rgb(214, 225, 238))
        }, params())

        content.addView(TextView(this).apply {
            text = "موتورهای آفلاین آمادهٔ آزمایش‌اند. میکروفون فقط پس از فشردن دکمه و اعطای مجوز فعال می‌شود؛ ضبط حداکثر ۸ ثانیه است."
            textSize = 13f
            setTextColor(Color.rgb(255, 205, 120))
        }, params())

        OfflineModelCatalog.all.forEach { spec ->
            content.addView(TextView(this).apply {
                text = spec.displayName
                textSize = 17f
                setTextColor(Color.rgb(242, 246, 252))
            }, params())

            content.addView(TextView(this).apply {
                text = "نام فایل: ${spec.fileName}\nحجم مجاز: ${formatBytes(spec.minimumBytes)} تا ${formatBytes(spec.maximumBytes)}\nمنبع: ${spec.sourcePage}"
                textSize = 12f
                setTextColor(Color.rgb(161, 174, 195))
                setTextIsSelectable(true)
            }, params())

            content.addView(Button(this).apply {
                text = "انتخاب و واردکردن این مدل"
                isAllCaps = false
                setOnClickListener { chooseModelFile(spec.id) }
            }, params())

            when (spec.engine) {
                OfflineModelEngine.LLAMA_CPP -> content.addView(Button(this).apply {
                    text = "اجرای آزمایشی مدل فارسی (آفلاین)"
                    isAllCaps = false
                    setOnClickListener { showLlmPrompt() }
                }, params())
                OfflineModelEngine.WHISPER_CPP -> content.addView(Button(this).apply {
                    text = "ضبط و تبدیل گفتار فارسی · حداکثر ۸ ثانیه"
                    isAllCaps = false
                    setOnClickListener { requestWhisperPermission(spec.id) }
                }, params())
            }
        }

        status = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(95, 202, 176))
            setTextIsSelectable(true)
        }
        content.addView(status, params())

        content.addView(Button(this).apply {
            text = "بازبینی وضعیت مدل‌ها"
            isAllCaps = false
            setOnClickListener { refreshStatus() }
        }, params())

        refreshStatus()
        applyVisualRefresh(scroll)
    }

    private fun applyVisualRefresh(root: android.view.ViewGroup) {
        val scale = resources.displayMetrics.density
        fun dp(value: Int) = (value * scale).toInt()
        fun walk(group: android.view.ViewGroup) {
            for (i in 0 until group.childCount) {
                val view = group.getChildAt(i)
                when (view) {
                    is Button -> {
                        val primary = view.text?.toString().orEmpty().contains("اجرای آزمایشی") ||
                            view.text?.toString().orEmpty().contains("ضبط و تبدیل")
                        view.background = GradientDrawable(
                            GradientDrawable.Orientation.TOP_BOTTOM,
                            intArrayOf(if (primary) Color.rgb(61, 177, 149) else Color.rgb(32, 42, 59),
                                if (primary) Color.rgb(38, 132, 125) else Color.rgb(24, 31, 45))
                        ).apply { cornerRadius = dp(16).toFloat() }
                        view.setTextColor(if (primary) Color.rgb(8, 20, 24) else Color.rgb(229, 237, 248))
                        view.isAllCaps = false
                        view.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        view.textSize = 14f
                        view.minimumHeight = dp(52)
                        view.setPadding(dp(16), dp(12), dp(16), dp(12))
                    }
                    is EditText -> {
                        view.background = GradientDrawable().apply {
                            setColor(Color.rgb(22, 29, 43))
                            cornerRadius = dp(14).toFloat()
                            setStroke(dp(1), Color.rgb(48, 61, 81))
                        }
                        view.setPadding(dp(14), dp(12), dp(14), dp(12))
                    }
                    is TextView -> {
                        view.typeface = Typeface.create(
                            if (view.textSize / scale >= 18) "sans-serif-medium" else "sans-serif",
                            Typeface.NORMAL
                        )
                    }
                    is android.view.ViewGroup -> walk(view)
                }
            }
        }
        walk(root)
    }

    private fun chooseModelFile(modelId: String) {
        val spec = OfflineModelCatalog.find(modelId) ?: return
        pendingModelId = modelId
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_TITLE, spec.fileName)
        }
        runCatching { startActivityForResult(picker, REQUEST_MODEL_FILE) }
            .onFailure {
                pendingModelId = null
                status.text = "بازکردن انتخاب‌گر فایل ممکن نشد: ${it.message ?: "خطای نامشخص"}"
            }
    }

    @Deprecated("Uses the platform document picker for compatibility with the current Activity.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_MODEL_FILE) return
        val modelId = pendingModelId
        pendingModelId = null
        val uri: Uri = data?.data ?: run {
            status.text = "انتخاب فایل لغو شد؛ تغییری انجام نشد."
            return
        }
        if (resultCode != RESULT_OK || modelId == null) {
            status.text = "انتخاب فایل کامل نشد؛ تغییری انجام نشد."
            return
        }

        status.text = "در حال بررسی و واردکردن فایل…"
        ioExecutor.execute {
            val result = runCatching { modelStore.importFromUri(modelId, uri) }
            runOnUiThread {
                result.onSuccess { installed ->
                    status.text = "مدل ${installed.spec.displayName} با موفقیت در فضای خصوصی برنامه ذخیره شد.\nحجم: ${formatBytes(installed.sizeBytes)}\nبرای استفاده، دکمهٔ اجرای مربوط به همین مدل را بزن."
                }.onFailure { error ->
                    status.text = "واردکردن مدل ناموفق بود: ${error.message ?: "خطای نامشخص"}"
                }
                refreshStatus()
            }
        }
    }

    private fun requestWhisperPermission(modelId: String) {
        pendingWhisperModelId = modelId
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startWhisperCapture(modelId)
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_RECORD_AUDIO) return
        val modelId = pendingWhisperModelId
        pendingWhisperModelId = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && modelId != null) {
            startWhisperCapture(modelId)
        } else {
            status.text = "مجوز میکروفون داده نشد؛ هیچ صدایی ضبط نشد."
        }
    }

    private fun startWhisperCapture(modelId: String) {
        if (!NativeWhisperRuntime.isAvailable) {
            status.text = "کتابخانهٔ بومی Whisper در این نسخه بارگذاری نشده است."
            return
        }
        cancelWhisperCapture = false
        status.text = "در حال آماده‌سازی موتور تشخیص گفتار آفلاین…"
        ioExecutor.execute {
            val result = runCatching {
                val manager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
                val memory = ActivityManager.MemoryInfo()
                manager.getMemoryInfo(memory)
                if (memory.availMem < MIN_AVAILABLE_MEMORY_BYTES) {
                    throw IllegalStateException("حافظهٔ آزاد برای بارگذاری Whisper کافی نیست.")
                }
                val installed = modelStore.installedModels()
                    .firstOrNull { it.spec.id == modelId }
                    ?: throw IllegalStateException("ابتدا فایل مدل Whisper انتخاب‌شده را وارد کن.")
                NativeLlamaRuntime.unload()
                if (!NativeWhisperRuntime.loadModel(installed.file)) {
                    throw IllegalStateException("بارگذاری مدل Whisper ناموفق بود.")
                }
                try {
                    runOnUiThread {
                        status.text = "اکنون صحبت کن؛ میکروفون فعال است و ضبط حداکثر ۸ ثانیه ادامه دارد."
                    }
                    val samples = captureWhisperAudio()
                    if (samples.isEmpty()) throw IllegalStateException("ضبط صدا کامل نشد یا لغو شد.")
                    NativeWhisperRuntime.transcribe(samples, threads = 2)
                        .ifBlank { "گفتاری تشخیص داده نشد؛ در محیط آرام و با گفتار واضح دوباره امتحان کن." }
                } finally {
                    NativeWhisperRuntime.unload()
                }
            }
            runOnUiThread {
                result.onSuccess { transcript ->
                    status.text = "متن تشخیص‌داده‌شده:\n$transcript"
                }.onFailure { error ->
                    status.text = "تبدیل گفتار ناموفق بود: ${error.message ?: "خطای نامشخص"}"
                }
            }
        }
    }

    private fun captureWhisperAudio(): FloatArray {
        val sampleRate = 16_000
        val maxSamples = sampleRate * MAX_RECORD_SECONDS
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) throw IllegalStateException("تنظیم ضبط صدا روی این دستگاه پشتیبانی نمی‌شود.")
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, 4096)
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IllegalStateException("راه‌اندازی میکروفون ناموفق بود.")
        }

        val collected = ShortArray(maxSamples)
        var collectedCount = 0
        val buffer = ShortArray(2048)
        try {
            recorder.startRecording()
            while (collectedCount < maxSamples && !cancelWhisperCapture) {
                val count = recorder.read(buffer, 0, minOf(buffer.size, maxSamples - collectedCount))
                if (count == AudioRecord.ERROR_DEAD_OBJECT || count == AudioRecord.ERROR_INVALID_OPERATION ||
                    count == AudioRecord.ERROR_BAD_VALUE) {
                    throw IllegalStateException("خواندن داده از میکروفون ناموفق بود.")
                }
                if (count > 0) {
                    buffer.copyInto(collected, destinationOffset = collectedCount, startIndex = 0, endIndex = count)
                    collectedCount += count
                }
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
        if (cancelWhisperCapture || collectedCount < sampleRate / 2) return FloatArray(0)
        return FloatArray(collectedCount) { index -> collected[index] / 32768.0f }
    }

    private fun showLlmPrompt() {
        if (!NativeLlamaRuntime.isAvailable) {
            status.text = "موتور بومی LLM در این نسخه بارگذاری نشده است."
            return
        }
        val input = EditText(this).apply {
            hint = "یک پرسش کوتاه به فارسی بنویس…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            maxLines = 5
        }
        AlertDialog.Builder(this)
            .setTitle("گفت‌وگوی آزمایشی آفلاین")
            .setMessage("مدل فقط پس از فشردن اجرا بارگذاری می‌شود. برای کاهش مصرف حافظه، پاسخ کوتاه تولید خواهد شد.")
            .setView(input)
            .setNegativeButton("انصراف", null)
            .setPositiveButton("اجرا") { _, _ ->
                val prompt = input.text.toString().trim().take(1000)
                if (prompt.isBlank()) {
                    status.text = "متن پرسش خالی است."
                    return@setPositiveButton
                }
                status.text = "در حال بررسی حافظه و آماده‌سازی مدل محلی…"
                ioExecutor.execute {
                    val result = runCatching {
                        val manager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
                        val memory = ActivityManager.MemoryInfo()
                        manager.getMemoryInfo(memory)
                        if (memory.availMem < MIN_AVAILABLE_MEMORY_BYTES) {
                            throw IllegalStateException("حافظهٔ آزاد کافی نیست؛ برنامه‌های دیگر را نبند یا داده‌ای را حذف نکن. بعداً دوباره امتحان کن.")
                        }
                        val installed = modelStore.installedModels()
                            .firstOrNull { it.spec.id == "qwen3-06b-farsi-q4km" }
                            ?: throw IllegalStateException("ابتدا فایل Qwen را وارد کن.")
                        if (!NativeLlamaRuntime.loadModel(installed.file, contextTokens = 512, threads = 2)) {
                            throw IllegalStateException("بارگذاری مدل ناموفق بود.")
                        }
                        try {
                            val chatPrompt = "<|im_start|>system\nتو یک دستیار فارسی دقیق و مفید هستی. پاسخ را کوتاه و روشن بده. /no_think<|im_end|>\n" +
                                "<|im_start|>user\n$prompt<|im_end|>\n<|im_start|>assistant\n"
                            NativeLlamaRuntime.generate(chatPrompt, maxTokens = 80)
                                .ifBlank { "مدل پاسخی تولید نکرد؛ ممکن است قالب مدل یا حافظهٔ دستگاه محدودیت ایجاد کرده باشد." }
                        } finally {
                            NativeLlamaRuntime.unload()
                        }
                    }
                    runOnUiThread {
                        result.onSuccess { answer ->
                            status.text = "پاسخ مدل آفلاین:\n$answer"
                        }.onFailure { error ->
                            status.text = "اجرای مدل ناموفق بود: ${error.message ?: "خطای نامشخص"}"
                        }
                    }
                }
            }
            .show()
    }

    private fun refreshStatus() {
        ioExecutor.execute {
            val installed = modelStore.installedModels().associateBy { it.spec.id }
            val summary = OfflineModelCatalog.all.joinToString("\n\n") { spec ->
                val item = installed[spec.id]
                if (item == null) "${spec.displayName}: نصب نشده"
                else {
                    val runtimeReady = when (spec.engine) {
                        OfflineModelEngine.LLAMA_CPP -> NativeLlamaRuntime.isAvailable
                        OfflineModelEngine.WHISPER_CPP -> NativeWhisperRuntime.isAvailable
                    }
                    "${spec.displayName}: ذخیره شده · ${formatBytes(item.sizeBytes)} · " +
                        if (runtimeReady) "موتور بومی آمادهٔ اجرا" else "کتابخانهٔ بومی در دسترس نیست"
                }
            }
            runOnUiThread { status.text = summary }
        }
    }

    private fun formatBytes(bytes: Long): String =
        if (bytes >= 1024L * 1024L) String.format("%.0f MiB", bytes / (1024.0 * 1024.0))
        else "${bytes} B"

    private fun params() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = 16 }

    override fun onStop() {
        cancelWhisperCapture = true
        super.onStop()
    }

    override fun onDestroy() {
        cancelWhisperCapture = true
        ioExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_MODEL_FILE = 4201
        private const val REQUEST_RECORD_AUDIO = 4202
        private const val MAX_RECORD_SECONDS = 8
        private const val MIN_AVAILABLE_MEMORY_BYTES = 1200L * 1024L * 1024L
    }
}
