package ir.mrsiavash.elagent

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class OpenAiChatProviderTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun sendsSingleTurnRequestAndReturnsText() {
        server.enqueue(MockResponse().setBody(
            """{"choices":[{"message":{"content":"Hello from cloud"}}]}"""
        ))
        val provider = OpenAiChatProvider(
            "test-api-key-that-is-long-enough",
            "test-model",
            server.url("/v1/chat/completions").toString(),
            OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
        )
        assertEquals("Hello from cloud", provider.chat("hello"))
        val request = server.takeRequest()
        assertEquals("Bearer test-api-key-that-is-long-enough", request.getHeader("Authorization"))
        assertEquals("POST", request.method)
        assertEquals("test-model", org.json.JSONObject(request.body.readUtf8()).getString("model"))
    }

    @Test
    fun refusesRedirects() {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://attacker.example/"))
        val provider = OpenAiChatProvider(
            "test-api-key-that-is-long-enough",
            "test-model",
            server.url("/chat").toString(),
            OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
        )
        assertThrows(IllegalStateException::class.java) { provider.chat("hello") }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun rejectsOversizedPromptBeforeNetworkCall() {
        val provider = OpenAiChatProvider(
            "test-api-key-that-is-long-enough",
            "test-model",
            server.url("/chat").toString()
        )
        assertThrows(IllegalArgumentException::class.java) {
            provider.chat("x".repeat(OpenAiChatProvider.MAX_PROMPT_CHARS + 1))
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun requiresHttpsExceptLoopbackTestEndpoint() {
        assertThrows(IllegalArgumentException::class.java) {
            OpenAiChatProvider("test-api-key-that-is-long-enough", "test-model", "http://example.com/chat")
        }
    }
    @Test
    fun rejectsNonOfficialHttpsHost() {
        assertThrows(IllegalArgumentException::class.java) {
            OpenAiChatProvider(
                "test-api-key-that-is-long-enough",
                "test-model",
                "https://attacker.example/v1/chat/completions"
            )
        }
    }

}
