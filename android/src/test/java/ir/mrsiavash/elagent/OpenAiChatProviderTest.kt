package ir.mrsiavash.elagent

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class OpenAiChatProviderTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        val certificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()

        server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.start()
        client = OkHttpClient.Builder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
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
            client
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
            client
        )
        assertThrows(IllegalStateException::class.java) { provider.chat("hello") }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun rejectsOversizedPromptBeforeNetworkCall() {
        val provider = OpenAiChatProvider(
            "test-api-key-that-is-long-enough",
            "test-model",
            server.url("/chat").toString(),
            client
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
