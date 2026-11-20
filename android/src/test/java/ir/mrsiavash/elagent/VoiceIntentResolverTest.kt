package ir.mrsiavash.elagent

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceIntentResolverTest {
    @Test fun resolvesPersianChatGptPhrase() {
        assertEquals("chatgpt", VoiceIntentResolver.resolve("لطفاً چت‌جی‌پی‌تی را باز کن"))
    }

    @Test fun resolvesEnglishChatGptPhrase() {
        assertEquals("chatgpt", VoiceIntentResolver.resolve("open ChatGPT"))
    }

    @Test fun resolvesPersianBrowserPhrase() {
        assertEquals("browser", VoiceIntentResolver.resolve("مرورگر را باز کن"))
    }

    @Test fun resolvesEnglishBrowserPhrase() {
        assertEquals("browser", VoiceIntentResolver.resolve("open browser"))
    }

    @Test fun rejectsUnknownAndAmbiguousCommands() {
        assertEquals(null, VoiceIntentResolver.resolve("برنامه تنظیمات را باز کن"))
        assertEquals(null, VoiceIntentResolver.resolve("ChatGPT and browser"))
        assertEquals(null, VoiceIntentResolver.resolve("   "))
    }
}
