package ir.mrsiavash.elagent

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

data class DocumentTextPreview(
    val mimeType: String,
    val text: String,
    val bytesRead: Int
)

object DocumentAccessPolicy {
    const val MAX_DOCUMENT_BYTES = 64 * 1024
    const val MAX_PREVIEW_CHARS = 12_000

    private val supportedExactTypes = setOf(
        "application/json",
        "application/xml",
        "application/x-yaml",
        "application/yaml",
        "application/toml",
        "application/sql",
        "application/javascript"
    )

    fun isSupportedTextMimeType(mimeType: String?): Boolean {
        val normalized = mimeType?.substringBefore(';')?.trim()?.lowercase() ?: return false
        return normalized.startsWith("text/") || normalized in supportedExactTypes
    }

    fun hasReadPermission(resultFlags: Int): Boolean =
        resultFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0

    fun canPersistReadPermission(resultFlags: Int): Boolean {
        val read = hasReadPermission(resultFlags)
        val persistable = resultFlags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0
        return read && persistable
    }

    fun decodeUtf8(bytes: ByteArray): String {
        require(bytes.size <= MAX_DOCUMENT_BYTES) { "Selected document exceeds the 64 KiB preview limit." }
        require(bytes.none { it == 0.toByte() }) { "Binary documents are not supported by the text preview." }
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val decoded = decoder.decode(ByteBuffer.wrap(bytes)).toString()
        return decoded.take(MAX_PREVIEW_CHARS)
    }
}

/** Uses the Android Storage Access Framework; no broad storage permission is requested. */
object AndroidDocumentAccess {
    fun createOpenDocumentIntent(): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }

    /** Returns true when access was persisted; a temporary picker grant remains usable for this session. */
    fun persistReadPermission(resolver: ContentResolver, uri: Uri, resultFlags: Int): Boolean {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT) { "Only Android content-provider URIs are supported." }
        val readGranted = resultFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION
        require(readGranted != 0) { "Android did not grant read access to the selected document." }
        if (!DocumentAccessPolicy.canPersistReadPermission(resultFlags)) return false
        resolver.takePersistableUriPermission(uri, readGranted)
        return true
    }

    fun readTextPreview(resolver: ContentResolver, uri: Uri): DocumentTextPreview {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT) { "Only Android content-provider URIs are supported." }
        val mime = resolver.getType(uri)
        require(DocumentAccessPolicy.isSupportedTextMimeType(mime)) {
            "This file type is not supported for local text preview."
        }
        val stream = resolver.openInputStream(uri) ?: throw IllegalStateException("Android could not open the selected document.")
        val output = ByteArrayOutputStream()
        stream.use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                require(output.size() + count <= DocumentAccessPolicy.MAX_DOCUMENT_BYTES) {
                    "Selected document exceeds the 64 KiB preview limit."
                }
                output.write(buffer, 0, count)
            }
        }
        val bytes = output.toByteArray()
        return DocumentTextPreview(mime ?: "application/octet-stream", DocumentAccessPolicy.decodeUtf8(bytes), bytes.size)
    }
}
