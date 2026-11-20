package ir.mrsiavash.elagent

import android.speech.SpeechRecognizer
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechErrorMessagesTest {
    @Test
    fun explainsDisconnectedServiceAndUnsupportedLanguageSeparately() {
        val disconnected = SpeechErrorMessages.describe(SpeechRecognizer.ERROR_SERVER_DISCONNECTED)
        val unsupported = SpeechErrorMessages.describe(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)

        assertTrue(disconnected.contains("سرویس تشخیص گفتار قطع شد"))
        assertTrue(unsupported.contains("زبان فارسی"))
        assertTrue(disconnected != unsupported)
    }
}
