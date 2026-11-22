package ir.mrsiavash.elagent

import java.io.File

/** Explicit, foreground-only local speech recognition bridge. */
object NativeWhisperRuntime {
    private val libraryLoaded: Boolean = runCatching {
        System.loadLibrary("elagent_whisper_jni")
    }.isSuccess

    val isAvailable: Boolean
        get() = libraryLoaded

    @Synchronized
    fun loadModel(file: File): Boolean {
        if (!libraryLoaded || !file.isFile || !file.canRead()) return false
        return nativeLoad(file.absolutePath)
    }

    @Synchronized
    fun transcribe(samples16kMono: FloatArray, threads: Int = 2): String {
        if (!libraryLoaded || samples16kMono.isEmpty()) return ""
        return nativeTranscribe(samples16kMono, threads.coerceIn(1, 4))
    }

    @Synchronized
    fun unload() {
        if (libraryLoaded) nativeUnload()
    }

    private external fun nativeLoad(path: String): Boolean
    private external fun nativeTranscribe(samples16kMono: FloatArray, threads: Int): String
    private external fun nativeUnload()
}
