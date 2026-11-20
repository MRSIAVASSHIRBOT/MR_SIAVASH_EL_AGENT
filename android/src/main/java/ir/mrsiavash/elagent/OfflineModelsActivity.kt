package ir.mrsiavash.elagent

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        modelStore = OfflineModelStore(this)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 28)
            setBackgroundColor(Color.rgb(7, 22, 16))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
        setContentView(scroll)

        content.addView(TextView(this).apply {
            text = "مدیریت موتورهای آفلاین"
            textSize = 23f
            setTextColor(Color.rgb(88, 218, 165))
        }, params())

        content.addView(TextView(this).apply {
            text = "فایل مدل را فقط از منبع رسمی درج‌شده تهیه کن و از اینجا انتخابش کن. فایل اصلی در محل خودش باقی می‌ماند و یک نسخهٔ خصوصی داخل برنامه ذخیره می‌شود."
            textSize = 14f
            setTextColor(Color.rgb(210, 226, 217))
        }, params())

        content.addView(TextView(this).apply {
            text = "توجه: این مرحله فقط مدل را وارد و قالب آن را بررسی می‌کند. اجرای واقعی Whisper و LLM پس از اتصال موتورهای بومی فعال می‌شود."
            textSize = 13f
            setTextColor(Color.rgb(255, 205, 120))
        }, params())

        OfflineModelCatalog.all.forEach { spec ->
            content.addView(TextView(this).apply {
                text = spec.displayName
                textSize = 17f
                setTextColor(Color.rgb(236, 250, 242))
            }, params())

            content.addView(TextView(this).apply {
                text = "نام فایل: ${spec.fileName}\nحجم مجاز: ${formatBytes(spec.minimumBytes)} تا ${formatBytes(spec.maximumBytes)}\nمنبع: ${spec.sourcePage}"
                textSize = 12f
                setTextColor(Color.rgb(168, 196, 183))
                setTextIsSelectable(true)
            }, params())

            content.addView(Button(this).apply {
                text = "انتخاب و واردکردن این مدل"
                isAllCaps = false
                setOnClickListener { chooseModelFile(spec.id) }
            }, params())

            if (spec.engine == OfflineModelEngine.LLAMA_CPP) {
                content.addView(Button(this).apply {
                    text = "اجرای آزمایشی مدل فارسی (آفلاین)"
                    isAllCaps = false
                    setOnClickListener { showLlmPrompt() }
                }, params())
            }
        }

        status = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(88, 218, 165))
            setTextIsSelectable(true)
        }
        content.addView(status, params())

        content.addView(Button(this).apply {
            text = "بازبینی وضعیت مدل‌ها"
            isAllCaps = false
            setOnClickListener { refreshStatus() }
        }, params())

        refreshStatus()
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
                    status.text = "مدل ${installed.spec.displayName} با موفقیت در فضای خصوصی برنامه ذخیره شد.\nحجم: ${formatBytes(installed.sizeBytes)}\nاین مدل هنوز تا زمان اتصال موتور بومی قابل اجرا نیست."
                }.onFailure { error ->
                    status.text = "واردکردن مدل ناموفق بود: ${error.message ?: "خطای نامشخص"}"
                }
                refreshStatus()
            }
        }
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
                else "${spec.displayName}: ذخیره شده · ${formatBytes(item.sizeBytes)} · اجرای موتور هنوز فعال نیست"
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

    override fun onDestroy() {
        ioExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_MODEL_FILE = 4201
        private const val MIN_AVAILABLE_MEMORY_BYTES = 1200L * 1024L * 1024L
    }
}
