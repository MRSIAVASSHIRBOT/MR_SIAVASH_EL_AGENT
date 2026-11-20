package ir.mrsiavash.elagent

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

/** One-shot, on-device-only speech input. There is no network-recognition fallback. */
class LocalVoiceController(
    private val activity: Activity,
    private val onStatus: (String) -> Unit,
    private val onTranscript: (String) -> Unit,
    private val onFinished: () -> Unit
) : RecognitionListener, TextToSpeech.OnInitListener {
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = TextToSpeech(activity, this)
    private var ttsReady = false
    private var closed = false
    private var cancelled = false

    fun start(): Boolean {
        if (closed) {
            onStatus("Voice controller is closed.")
            return false
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            onStatus("Local voice requires Android 12 or newer; no cloud fallback is used.")
            return false
        }
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(activity)) {
            onStatus("On-device speech recognition is unavailable on this device.")
            return false
        }
        cancelled = false
        return try {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(activity).also {
                it.setRecognitionListener(this)
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }
            recognizer?.startListening(intent)
            true
        } catch (_: Exception) {
            onStatus("Could not start the on-device speech recognizer.")
            false
        }
    }

    fun stop() { runCatching { recognizer?.stopListening() } }

    fun cancel() {
        cancelled = true
        runCatching { recognizer?.cancel() }
    }

    fun speak(message: String) {
        if (ttsReady && !closed) tts?.speak(message.take(240), TextToSpeech.QUEUE_FLUSH, null, "el-agent-local-reply")
        else onStatus(message)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("fa", "IR"))
            ttsReady = result != null && result >= TextToSpeech.LANG_AVAILABLE
        }
    }

    override fun onReadyForSpeech(params: android.os.Bundle?) = onStatus("میکروفون فعال است؛ فرمان را بگو.")
    override fun onBeginningOfSpeech() = onStatus("در حال شنیدن فرمان…")
    override fun onEndOfSpeech() = onStatus("در حال پردازش محلی گفتار…")
    override fun onError(error: Int) {
        if (!cancelled) {
            onFinished()
            onStatus("تشخیص گفتار محلی ناموفق بود (کد $error). دوباره تلاش کن.")
        }
    }
    override fun onResults(results: android.os.Bundle?) {
        if (cancelled || closed) return
        onFinished()
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
        if (text.isBlank()) onStatus("گفتاری تشخیص داده نشد؛ هیچ عملیاتی اجرا نشد.")
        else onTranscript(text)
    }
    override fun onPartialResults(partialResults: android.os.Bundle?) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit

    fun close() {
        closed = true
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        ttsReady = false
    }
}
