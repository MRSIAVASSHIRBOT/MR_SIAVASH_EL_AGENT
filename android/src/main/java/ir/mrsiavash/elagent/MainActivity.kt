package ir.mrsiavash.elagent

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
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
    private lateinit var taskButton: Button
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

        taskButton = Button(this).apply {
            text = "Check Hub tasks"
            backgroundTintList = ColorStateList.valueOf(Color.rgb(13, 33, 25))
            setTextColor(Color.rgb(53, 214, 160))
            setOnClickListener {
                isEnabled = false
                setBusy("Checking for an owner-authorized task…")
                executor.execute {
                    val result = runCatching { HubClient(store).claimTask() }
                    runOnUiThread {
                        taskButton.isEnabled = true
                        result.onSuccess { claim ->
                            if (claim == null) setStatus("No task is queued for this Android device or app.launch is not granted.")
                            else showTaskApproval(claim)
                        }.onFailure { error ->
                            setStatus(error.message ?: "Could not check Hub tasks.")
                        }
                    }
                }
            }
        }
        root.addView(taskButton, buttonParams())
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



    private fun showTaskApproval(claim: AndroidDeviceTaskClaim) {
        val appName = claim.app ?: "unknown"
        val dialog = AlertDialog.Builder(this)
            .setTitle("Hub task requires your approval")
            .setMessage(
                "The Hub has requested to open '$appName' on this device. " +
                "Nothing will happen unless you approve this request. " +
                "The task expires after ${claim.timeoutSeconds} seconds."
            )
            .setPositiveButton("Approve and continue") { _, _ ->
                val started = SystemClock.elapsedRealtime()
                val launchResult = runCatching { launchAllowlisted(appName) }
                val elapsed = SystemClock.elapsedRealtime() - started
                if (launchResult.isSuccess) {
                    setStatus("App launch requested. Reporting the outcome to Hub…")
                    reportTaskOutcome(claim, true, null, elapsed)
                } else {
                    setStatus("This app is unavailable on Android. Reporting failure…")
                    reportTaskOutcome(claim, false, "app_not_available", elapsed)
                }
            }
            .setNegativeButton("Reject") { _, _ ->
                setStatus("Task rejected. Reporting the decision to Hub…")
                reportTaskOutcome(claim, false, "permission_denied", 0L)
            }
            .setOnCancelListener {
                reportTaskOutcome(claim, false, "permission_denied", 0L)
            }
            .create()
        dialog.show()
    }

    private fun reportTaskOutcome(
        claim: AndroidDeviceTaskClaim,
        succeeded: Boolean,
        errorCode: String?,
        durationMs: Long
    ) {
        executor.execute {
            runCatching { HubClient(store).completeTask(claim, succeeded, errorCode, durationMs) }
                .onSuccess {
                    runOnUiThread {
                        setStatus(if (succeeded) "Hub recorded the app-launch result (device-reported, not independently verified)." else "Hub recorded the rejected or failed task.")
                    }
                }
                .onFailure { error ->
                    runOnUiThread {
                        setStatus("Could not report task outcome; Hub will expire the lease without replay: ${error.message}")
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
