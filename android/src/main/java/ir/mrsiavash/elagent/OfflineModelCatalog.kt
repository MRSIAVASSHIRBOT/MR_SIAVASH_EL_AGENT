package ir.mrsiavash.elagent

/** Metadata for user-selected on-device model files. */
enum class OfflineModelEngine { WHISPER_CPP, LLAMA_CPP }

data class OfflineModelSpec(
    val id: String,
    val displayName: String,
    val engine: OfflineModelEngine,
    val fileName: String,
    val minimumBytes: Long,
    val maximumBytes: Long,
    val expectedMagic: String,
    val sourcePage: String
) {
    init {
        require(id.matches(Regex("[a-z0-9-]{3,80}")))
        require(fileName.matches(Regex("[A-Za-z0-9._-]{1,120}")))
        require(minimumBytes > 0 && maximumBytes >= minimumBytes)
        require(expectedMagic.length == 4)
        require(sourcePage.startsWith("https://"))
    }
}

object OfflineModelCatalog {
    private const val MIB = 1024L * 1024L

    val all: List<OfflineModelSpec> = listOf(
        OfflineModelSpec(
            id = "whisper-tiny-multilingual",
            displayName = "Whisper Tiny · چندزبانه",
            engine = OfflineModelEngine.WHISPER_CPP,
            fileName = "ggml-tiny.bin",
            minimumBytes = 60 * MIB,
            maximumBytes = 110 * MIB,
            expectedMagic = "ggml",
            sourcePage = "https://github.com/ggml-org/whisper.cpp"
        ),
        OfflineModelSpec(
            id = "whisper-base-multilingual",
            displayName = "Whisper Base · چندزبانه",
            engine = OfflineModelEngine.WHISPER_CPP,
            fileName = "ggml-base.bin",
            minimumBytes = 115 * MIB,
            maximumBytes = 190 * MIB,
            expectedMagic = "ggml",
            sourcePage = "https://github.com/ggml-org/whisper.cpp"
        ),
        OfflineModelSpec(
            id = "qwen3-06b-farsi-q4km",
            displayName = "Qwen3 0.6B Farsi · Q4_K_M",
            engine = OfflineModelEngine.LLAMA_CPP,
            fileName = "Qwen3-0.6B-Farsi-Q4_K_M.gguf",
            minimumBytes = 300 * MIB,
            maximumBytes = 520 * MIB,
            expectedMagic = "GGUF",
            sourcePage = "https://huggingface.co/gpugobrrr/Qwen3-0.6B-Farsi-GGUF"
        )
    )

    fun find(id: String): OfflineModelSpec? = all.firstOrNull { it.id == id }

    fun forEngine(engine: OfflineModelEngine): List<OfflineModelSpec> =
        all.filter { it.engine == engine }
}
