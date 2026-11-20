package ir.mrsiavash.elagent

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test

class DocumentAccessPolicyTest {
    @Test fun acceptsTextAndKnownStructuredTextTypes() {
        assertTrue(DocumentAccessPolicy.isSupportedTextMimeType("text/plain"))
        assertTrue(DocumentAccessPolicy.isSupportedTextMimeType("text/markdown; charset=utf-8"))
        assertTrue(DocumentAccessPolicy.isSupportedTextMimeType("application/json"))
        assertTrue(DocumentAccessPolicy.isSupportedTextMimeType("application/xml"))
    }

    @Test fun rejectsBinaryAndUnknownMimeTypes() {
        assertFalse(DocumentAccessPolicy.isSupportedTextMimeType("application/pdf"))
        assertFalse(DocumentAccessPolicy.isSupportedTextMimeType("image/png"))
        assertFalse(DocumentAccessPolicy.isSupportedTextMimeType(null))
    }

    @Test fun persistsOnlyWhenProviderReturnsBothReadAndPersistableFlags() {
        assertTrue(DocumentAccessPolicy.canPersistReadPermission(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        ))
        assertFalse(DocumentAccessPolicy.canPersistReadPermission(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        assertFalse(DocumentAccessPolicy.canPersistReadPermission(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))
    }

    @Test fun decodesUtf8Text() {
        assertEquals("سلام", DocumentAccessPolicy.decodeUtf8("سلام".toByteArray(Charsets.UTF_8)))
    }

    @Test fun rejectsOversizedInput() {
        val bytes = ByteArray(DocumentAccessPolicy.MAX_DOCUMENT_BYTES + 1) { 'a'.code.toByte() }
        assertThrows(IllegalArgumentException::class.java) { DocumentAccessPolicy.decodeUtf8(bytes) }
    }

    @Test fun rejectsBinaryNullBytes() {
        assertThrows(IllegalArgumentException::class.java) {
            DocumentAccessPolicy.decodeUtf8(byteArrayOf(0x41, 0x00, 0x42))
        }
    }

    @Test fun rejectsMalformedUtf8() {
        assertThrows(java.nio.charset.CharacterCodingException::class.java) {
            DocumentAccessPolicy.decodeUtf8(byteArrayOf(0xC3.toByte(), 0x28))
        }
    }

    @Test fun capsDisplayedPreviewCharacters() {
        val input = "x".repeat(DocumentAccessPolicy.MAX_PREVIEW_CHARS + 100)
        assertEquals(DocumentAccessPolicy.MAX_PREVIEW_CHARS, DocumentAccessPolicy.decodeUtf8(input.toByteArray()).length)
    }
}
