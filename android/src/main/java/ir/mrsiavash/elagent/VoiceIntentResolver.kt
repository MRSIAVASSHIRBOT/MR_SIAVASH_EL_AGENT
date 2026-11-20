package ir.mrsiavash.elagent

import java.util.Locale

/** Maps an explicit transcript to a tiny action allowlist; incidental mentions are never commands. */
object VoiceIntentResolver {
    private val launchVerbs = listOf("open", "launch", "start")
    private val chatAliases = listOf("chatgpt", "chat gpt", "چت جی پی تی")
    private val browserAliases = listOf("browser", "مرورگر", "مرورگر اینترنت")

    fun resolve(transcript: String): String? {
        val command = transcript.lowercase(Locale.ROOT)
            .replace("ي", "ی")
            .replace("ك", "ک")
            .replace("‌", " ")
            .replace(Regex("[!?؟،,.؛:]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .removePrefix("لطفاً ")
            .removePrefix("لطفا ")
            .trim()
        if (command.isEmpty() || command.length > 160) return null

        fun isExplicitLaunch(aliases: List<String>): Boolean = aliases.any { alias ->
            command in launchVerbs.map { "$it $alias" } ||
                command in listOf(
                    "باز کن $alias",
                    "اجرا کن $alias",
                    "برو به $alias",
                    "$alias رو باز کن",
                    "$alias را باز کن",
                    "$alias باز کن",
                )
        }

        val chatGpt = isExplicitLaunch(chatAliases)
        val browser = isExplicitLaunch(browserAliases)
        return when {
            chatGpt && !browser -> "chatgpt"
            browser && !chatGpt -> "browser"
            else -> null
        }
    }
}
