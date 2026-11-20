package ir.mrsiavash.elagent

import android.speech.SpeechRecognizer

/** Human-readable, local-only diagnostics for Android on-device speech errors. */
object SpeechErrorMessages {
    fun describe(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
            "سرویس تشخیص گفتار قطع شد؛ سرویس گفتار گوشی را بررسی و دوباره تلاش کن."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
            "زبان فارسی برای تشخیص آفلاین در این دستگاه پشتیبانی نمی‌شود."
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            "بسته زبان گفتار آفلاین نصب یا در دسترس نیست."
        SpeechRecognizer.ERROR_NO_MATCH ->
            "گفتاری تشخیص داده نشد؛ محیط آرام‌تری را امتحان کن."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
            "در زمان تعیین‌شده گفتاری دریافت نشد."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            "مجوز میکروفون در دسترس نیست."
        else ->
            "تشخیص گفتار محلی ناموفق بود؛ کد خطا: $code."
    }
}
