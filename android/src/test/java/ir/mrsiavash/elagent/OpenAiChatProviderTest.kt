package ir.mrsiavash.elagent

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class OpenAiChatProviderTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before fun setUp() {
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

    @After fun tearDown() { server.shutdown() }

    private fun provider() = OpenAiChatProvider(
        "test-api-key-that-is-long-enough",
        "test-model",
        server.url("/v1/chat/completions").toString(),
        client
    )

    @Test fun sendsSingleTurnChatRequest() {
        server.enqueue(MockResponse().setBody(
            """{"choices":[{"finish_reason":"stop","message":{"content":"Hello from cloud"}}]}"""
        ))
        assertEquals("Hello from cloud", provider().chat("hello"))
        val request = server.takeRequest()
        assertEquals("Bearer test-api-key-that-is-long-enough", request.getHeader("Authorization"))
        val payload = org.json.JSONObject(request.body.readUtf8())
        assertEquals("test-model", payload.getString("model"))
        assertFalse(payload.has("response_format"))
    }

    @Test fun sendsSystemPromptAndJsonResponseFormatForPlanner() {
        server.enqueue(MockResponse().setBody(
            """{"choices":[{"finish_reason":"stop","message":{"content":"{\"schema_version\":1}"}}]}"""
        ))
        val response = provider().chatJson("system constraints", "open the browser")
        assertEquals("""{"schema_version":1}""", response)
        val payload = org.json.JSONObject(server.takeRequest().body.readUtf8())
        val messages = payload.getJSONArray("messages")
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("system constraints", messages.getJSONObject(0).getString("content"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        val responseFormat = payload.getJSONObject("response_format")
        assertEquals("json_schema", responseFormat.getString("type"))
        val schemaConfig = responseFormat.getJSONObject("json_schema")
        assertEquals("android_agent_plan_v1", schemaConfig.getString("name"))
        assertTrue(schemaConfig.getBoolean("strict"))
        val schema = schemaConfig.getJSONObject("schema")
        assertEquals(false, schema.getBoolean("additionalProperties"))
        assertTrue(schema.getJSONArray("required").toString().contains("steps"))
        assertFalse(payload.toString().contains("maxLength"))
        assertFalse(payload.toString().contains("minLength"))
        assertTrue(schema.getJSONArray("required").toString().contains("clarification"))
    }

    @Test fun refusesRedirectsWithoutForwardingCredential() {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://attacker.example/"))
        assertThrows(IllegalStateException::class.java) { provider().chat("hello") }
        assertEquals(1, server.requestCount)
    }

    @Test fun rejectsOversizedPromptBeforeNetworkCall() {
        assertThrows(IllegalArgumentException::class.java) {
            provider().chat("x".repeat(OpenAiChatProvider.MAX_PROMPT_CHARS + 1))
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun rejectsNonOfficialHttpsEndpoint() {
        assertThrows(IllegalArgumentException::class.java) {
            OpenAiChatProvider("test-api-key-that-is-long-enough", "model", "https://attacker.example/v1/chat/completions")
        }
    }

    @Test fun reportsStructuredOutputRefusalsWithoutParsingThemAsPlans() {
        server.enqueue(MockResponse().setBody(
            """{"choices":[{"finish_reason":"stop","message":{"refusal":"I cannot help with that request."}}]}"""
        ))
        val error = assertThrows(IllegalStateException::class.java) {
            provider().chatJson("system", "unsafe request")
        }
        assertTrue(error.message!!.contains("declined"))
    }

    @Test fun rejectsTruncatedCompletions() {
        server.enqueue(MockResponse().setBody(
            """{"choices":[{"finish_reason":"length","message":{"content":"{\"partial\":"}}]}"""
        ))
        assertThrows(IllegalStateException::class.java) { provider().chatJson("system", "user") }
    }

    @Test fun rejectsMissingCompletionContent() {
        server.enqueue(MockResponse().setBody("""{"choices":[{"finish_reason":"stop","message":{"content":""}}]}"""))
        assertThrows(IllegalStateException::class.java) { provider().chat("hello") }
    }
}
