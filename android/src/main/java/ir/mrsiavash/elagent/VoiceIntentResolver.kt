package ir.mrsiavash.elagent

import java.util.Locale

/** Maps a transcript to a tiny, explicit action allowlist. Ambiguous or unknown speech is rejected. */
object VoiceIntentResolver {
    fun resolve(transcript: String): String? {
        val normalized = transcript.lowercase(Locale.ROOT)
            .replace("ي", "ی")
            .replace("ك", "ک")
            .replace("‌", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.isEmpty()) return null

        val chatGpt = normalized.contains("چت جی پی تی") ||
            Regex("""(?<![a-z0-9])chatgpt(?![a-z0-9])""").containsMatchIn(normalized) ||
            Regex("""(?<![a-z0-9])chat\s*gpt(?![a-z0-9])""").containsMatchIn(normalized)
        val browser = normalized.contains("مرورگر") ||
            Regex("""(?<![a-z0-9])browser(?![a-z0-9])""").containsMatchIn(normalized)

        return when {
            chatGpt && !browser -> "chatgpt"
            browser && !chatGpt -> "browser"
            else -> null
        }
    }
}
