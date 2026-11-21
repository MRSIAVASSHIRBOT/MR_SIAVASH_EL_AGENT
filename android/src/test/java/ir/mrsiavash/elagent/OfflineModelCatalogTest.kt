package ir.mrsiavash.elagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineModelCatalogTest {
    @Test fun modelIdsAndFileNamesAreUnique() {
        assertEquals(OfflineModelCatalog.all.size, OfflineModelCatalog.all.map { it.id }.toSet().size)
        assertEquals(OfflineModelCatalog.all.size, OfflineModelCatalog.all.map { it.fileName }.toSet().size)
    }

    @Test fun speechModelsUseWhisperContainerAndMultilingualFiles() {
        val speechModels = OfflineModelCatalog.forEngine(OfflineModelEngine.WHISPER_CPP)
        assertEquals(2, speechModels.size)
        assertTrue(speechModels.all { it.expectedMagic == "ggml" })
        assertTrue(speechModels.all { it.fileName.endsWith(".bin") })
    }

    @Test fun languageModelUsesGgufContainer() {
        val model = OfflineModelCatalog.find("qwen3-06b-farsi-q4km")
        assertNotNull(model)
        assertEquals(OfflineModelEngine.LLAMA_CPP, model!!.engine)
        assertEquals("GGUF", model.expectedMagic)
        assertTrue(model.fileName.endsWith(".gguf"))
    }

    @Test fun everyModelHasBoundedSizeAndHttpsSource() {
        OfflineModelCatalog.all.forEach { model ->
            assertTrue(model.minimumBytes > 0)
            assertTrue(model.maximumBytes >= model.minimumBytes)
            assertTrue(model.sourcePage.startsWith("https://"))
        }
    }
}
