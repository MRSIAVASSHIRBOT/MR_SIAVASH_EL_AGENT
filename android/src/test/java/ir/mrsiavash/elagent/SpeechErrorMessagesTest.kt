package ir.mrsiavash.elagent

import android.speech.SpeechRecognizer
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechErrorMessagesTest {
    @Test
    fun explainsDisconnectedServiceAndUnsupportedLanguageSeparately() {
        val disconnected = SpeechErrorMessages.describe(SpeechRecognizer.ERROR_SERVER_DISCONNECTED)
        val unsupported = SpeechErrorMessages.describe(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)
        assertTrue(disconnected.contains("قطع"))
        assertTrue(unsupported.contains("پشتیبانی"))
        assertTrue(disconnected != unsupported)
    }
}
