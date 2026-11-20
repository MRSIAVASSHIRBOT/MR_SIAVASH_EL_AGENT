package ir.mrsiavash.elagent

import android.speech.SpeechRecognizer

object SpeechErrorMessages {
    fun describe(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
            "سرویس تشخیص گفتار قطع شد؛ سرویس گفتار گوشی را بررسی و دوباره تلاش کن."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
            "موتور تشخیص آفلاین فعلی از زبان انتخاب‌شده (فارسی) پشتیبانی نمی‌کند."
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            "بسته زبان فارسی برای تشخیص آفلاین در دسترس نیست؛ پشتیبانی یا نصب آن را در تنظیمات گفتار گوشی بررسی کن."
        SpeechRecognizer.ERROR_NO_MATCH ->
            "گفتار قابل تشخیصی پیدا نشد؛ میکروفون و محیط را بررسی کن."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
            "در زمان مجاز گفتاری دریافت نشد."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            "مجوز میکروفون در دسترس نیست."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
            "سرویس تشخیص گفتار مشغول است؛ چند لحظه بعد دوباره تلاش کن."
        else -> "موتور تشخیص گفتار محلی درخواست را کامل نکرد."
    }
}
