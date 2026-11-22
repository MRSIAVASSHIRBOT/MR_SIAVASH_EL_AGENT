package ir.mrsiavash.elagent

import java.util.Locale

/** Resolves explicit, short Persian/English commands to a deliberately small action allowlist. */
object VoiceIntentResolver {
    private val launchVerbs = listOf("open", "launch", "start")
    private val chatAliases = listOf("chatgpt", "chat gpt", "چت جی پی تی")
    private val browserAliases = listOf("browser", "مرورگر", "مرورگر اینترنت")
    private val fatherCallPhrases = listOf(
        "با پدر تماس بگیر",
        "به پدر زنگ بزن",
        "به بابا زنگ بزن",
        "با بابا تماس بگیر",
        "به پدرم زنگ بزن",
        "با پدرم تماس بگیر",
        "call father",
        "call dad"
    )

    fun resolve(transcript: String): String? {
        var command = transcript.lowercase(Locale.ROOT)
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

        // "سیا" is a command prefix only; this is not an always-listening wake-word service.
        listOf("سیا ", "سيا ", "سیاوش ").firstOrNull { command.startsWith(it) }?.let {
            command = command.removePrefix(it).trim()
        }
        if (command in fatherCallPhrases) return "call_father"

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
