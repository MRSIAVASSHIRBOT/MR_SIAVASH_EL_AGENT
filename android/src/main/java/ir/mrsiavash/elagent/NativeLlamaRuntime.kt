package ir.mrsiavash.elagent

import java.io.File

/**
 * Optional local LLM bridge. The native library is loaded only when the owner
 * explicitly starts a local inference session.
 */
object NativeLlamaRuntime {
    private val libraryLoaded: Boolean = runCatching {
        System.loadLibrary("elagent_llama_jni")
    }.isSuccess

    val isAvailable: Boolean
        get() = libraryLoaded

    @Synchronized
    fun loadModel(file: File, contextTokens: Int = 512, threads: Int = 2): Boolean {
        if (!libraryLoaded || !file.isFile || !file.canRead()) return false
        return nativeLoad(file.absolutePath, contextTokens, threads)
    }

    @Synchronized
    fun generate(prompt: String, maxTokens: Int = 80): String {
        if (!libraryLoaded || prompt.isBlank()) return ""
        return nativeGenerate(prompt, maxTokens.coerceIn(1, 128))
    }

    @Synchronized
    fun unload() {
        if (libraryLoaded) nativeUnload()
    }

    private external fun nativeLoad(path: String, contextTokens: Int, threads: Int): Boolean
    private external fun nativeGenerate(prompt: String, maxTokens: Int): String
    private external fun nativeUnload()
}
