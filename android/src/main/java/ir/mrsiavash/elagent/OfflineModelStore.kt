package ir.mrsiavash.elagent

import android.content.Context
import android.net.Uri
import android.os.StatFs
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class InstalledOfflineModel(
    val spec: OfflineModelSpec,
    val file: File,
    val sizeBytes: Long
)

/**
 * Imports a model selected by the owner into app-private storage.
 * Container validation is structural only, not a cryptographic authenticity check.
 */
class OfflineModelStore(context: Context) {
    private val appContext = context.applicationContext
    private val modelDirectory = File(appContext.filesDir, "offline-models")

    fun installedModels(): List<InstalledOfflineModel> =
        OfflineModelCatalog.all.mapNotNull { spec ->
            val file = modelFile(spec)
            if (!file.isFile) return@mapNotNull null
            runCatching {
                validateFile(file, spec)
                InstalledOfflineModel(spec, file, file.length())
            }.getOrNull()
        }

    fun isInstalled(modelId: String): Boolean {
        val spec = OfflineModelCatalog.find(modelId) ?: return false
        val file = modelFile(spec)
        return file.isFile && runCatching { validateFile(file, spec) }.isSuccess
    }

    fun modelFile(modelId: String): File? =
        OfflineModelCatalog.find(modelId)?.let(::modelFile)

    @Throws(IOException::class, IllegalArgumentException::class)
    fun importFromUri(modelId: String, source: Uri): InstalledOfflineModel {
        val spec = OfflineModelCatalog.find(modelId)
            ?: throw IllegalArgumentException("مدل انتخاب‌شده در فهرست پشتیبانی‌شده نیست.")

        if (!modelDirectory.exists() && !modelDirectory.mkdirs()) {
            throw IOException("ساخت پوشهٔ خصوصی مدل‌ها ناموفق بود.")
        }

        val staging = File(modelDirectory, "${spec.id}.importing")
        val destination = modelFile(spec)
        val requiredSpace = spec.minimumBytes + 32L * 1024L * 1024L
        if (StatFs(modelDirectory.absolutePath).availableBytes < requiredSpace) {
            throw IOException("فضای آزاد کافی برای واردکردن این مدل وجود ندارد.")
        }

        try {
            val input = appContext.contentResolver.openInputStream(source)
                ?: throw IOException("فایل انتخاب‌شده قابل خواندن نیست.")
            input.use { sourceStream ->
                staging.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = sourceStream.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > spec.maximumBytes) {
                            throw IOException("حجم فایل با مدل انتخاب‌شده مطابقت ندارد.")
                        }
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }

            validateFile(staging, spec)
            Files.move(
                staging.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
            return InstalledOfflineModel(spec, destination, destination.length())
        } finally {
            if (staging.exists()) staging.delete()
        }
    }

    private fun modelFile(spec: OfflineModelSpec): File =
        File(modelDirectory, spec.fileName)

    private fun validateFile(file: File, spec: OfflineModelSpec) {
        val size = file.length()
        if (!file.isFile || size !in spec.minimumBytes..spec.maximumBytes) {
            throw IOException("اندازهٔ فایل مدل معتبر نیست.")
        }
        val expected = spec.expectedMagic.toByteArray(Charsets.US_ASCII)
        val actual = ByteArray(expected.size)
        FileInputStream(file).use { input ->
            var offset = 0
            while (offset < actual.size) {
                val count = input.read(actual, offset, actual.size - offset)
                if (count < 0) throw IOException("سرآیند فایل مدل ناقص است.")
                offset += count
            }
        }
        if (!actual.contentEquals(expected)) {
            throw IOException("قالب فایل با موتور انتخاب‌شده سازگار نیست.")
        }
    }
}
