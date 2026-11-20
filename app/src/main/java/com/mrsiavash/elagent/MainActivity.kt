package com.mrsiavash.elagent

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var hubUrl: EditText
    private lateinit var pairingCode: EditText
    private lateinit var pairingPayload: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = SecureStore(this)
        pairingPayload = HubClient(this).pairingPayload().toString(2)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 28)
        }
        val title = TextView(this).apply {
            text = "MR_SIAVASH_EL_AGENT · Android"
            textSize = 22f
        }
        val subtitle = TextView(this).apply {
            text = "همراه اندرویدی با اتصال امن به Hub"
            textSize = 15f
        }
        hubUrl = EditText(this).apply {
            hint = "آدرس Hub با HTTPS"
            setSingleLine(true)
        }
        pairingCode = EditText(this).apply {
            hint = "کد Pairing پنج‌دقیقه‌ای"
            setSingleLine(true)
        }
        status = TextView(this).apply {
            textSize = 14f
            text = "Device ID: ${store.deviceId()}\n\nدرخواست Pairing:\n$pairingPayload"
        }

        content.addView(title)
        content.addView(subtitle)
        content.addView(hubUrl, matchWrap())
        content.addView(pairingCode, matchWrap())
        content.addView(button("کپی درخواست Pairing") {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("EL Agent pairing payload", pairingPayload))
            status.text = "درخواست Pairing کپی شد. آن را در پنل Hub برای ایجاد challenge استفاده کنید."
        })
        content.addView(button("اتصال به Hub") { pair() })
        content.addView(button("شروع همگام‌سازی امن") { startSync() })
        content.addView(button("توقف همگام‌سازی") {
            stopService(Intent(this, SyncService::class.java))
            status.text = "همگام‌سازی متوقف شد."
        })
        content.addView(button("وضعیت اتصال") {
            status.text = runCatching { HubClient(this).status() }.getOrElse { it.message ?: "خطا" }
        })
        content.addView(button("قطع اتصال محلی") {
            stopService(Intent(this, SyncService::class.java))
            store.clearCredentials()
            status.text = "اعتبارنامه محلی پاک شد. دستگاه را از پنل مالک Hub نیز Revoke کنید."
        })
        content.addView(TextView(this).apply { text = "اجرای محلی برنامه‌های مجاز"; textSize = 17f })
        content.addView(button("بازکردن ChatGPT") { launchLocal("chatgpt") })
        content.addView(button("بازکردن مرورگر") { launchLocal("browser") })
        content.addView(button("بازکردن ماشین‌حساب") { launchLocal("calculator") })
        content.addView(status, matchWrap())
        val scroll = ScrollView(this).apply { addView(content) }
        setContentView(scroll)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    private fun pair() {
        val url = hubUrl.text.toString()
        val code = pairingCode.text.toString().trim()
        status.text = "در حال اتصال و اعتبارسنجی کد Pairing…"
        executor.execute {
            try {
                HubClient(this).pair(url, code)
                runOnUiThread {
                    pairingCode.text.clear()
                    status.text = HubClient(this).status() + "\n\nبرای ارسال Heartbeat، همگام‌سازی را شروع کنید."
                }
            } catch (error: Exception) {
                runOnUiThread { status.text = "اتصال ناموفق: ${error.message}" }
            }
        }
    }

    private fun startSync() {
        if (SecureStore(this).credentials() == null) {
            status.text = "ابتدا دستگاه را با Hub جفت کنید."
            return
        }
        val serviceIntent = Intent(this, SyncService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        status.text = "سرویس همگام‌سازی شروع شد؛ اعلان دائمی Android نمایش داده می‌شود."
    }

    private fun launchLocal(alias: String) {
        try {
            AndroidActions.launch(this, alias)
        } catch (error: Exception) {
            status.text = error.message ?: "اجرای برنامه ناموفق بود."
        }
    }

    private fun button(label: String, action: () -> Unit) =
        Button(this).apply {
            text = label
            setOnClickListener { action() }
            layoutParams = matchWrap()
        }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = 8 }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    companion object { private const val REQUEST_NOTIFICATIONS = 101 }
}
