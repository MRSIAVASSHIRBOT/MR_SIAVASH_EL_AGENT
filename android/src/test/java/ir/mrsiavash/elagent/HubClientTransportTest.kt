package ir.mrsiavash.elagent

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class HubClientTransportTest {
    @Test
    fun authenticatedHubRequestsDoNotFollowRedirects() {
        val origin = MockWebServer()
        val destination = MockWebServer()
        origin.start()
        destination.start()
        try {
            origin.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .addHeader("Location", destination.url("/capture").toString())
            )
            val bearer = "Bearer " + "a".repeat(48)
            val request = Request.Builder()
                .url(origin.url("/devices/heartbeat"))
                .header("Authorization", bearer)
                .post("{}".toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            val response = buildHubHttpClient().newCall(request).execute()
            response.use { assertEquals(302, it.code) }

            val received = origin.takeRequest(2, TimeUnit.SECONDS)
            assertNotNull("The configured Hub origin must receive the request.", received)
            assertEquals(bearer, received!!.getHeader("Authorization"))
            assertEquals("The bearer credential must not be forwarded to a redirect target.", 0, destination.requestCount)
        } finally {
            origin.shutdown()
            destination.shutdown()
        }
    }

    @Test
    fun rejectsHubResponsesLargerThanTheClientLimit() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("x".repeat(MAX_HUB_RESPONSE_BYTES + 1)))
            val response = okhttp3.OkHttpClient().newCall(
                Request.Builder().url(server.url("/oversized")).build()
            ).execute()
            val result = response.use { runCatching { readBoundedHubResponse(it) } }

            assertTrue("Known-length oversized Hub bodies must be rejected.", result.isFailure)
            assertEquals("Hub response exceeded the size limit.", result.exceptionOrNull()?.message)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun rejectsChunkedHubResponsesLargerThanTheClientLimit() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse().setChunkedBody("x".repeat(MAX_HUB_RESPONSE_BYTES + 1), 1024)
            )
            val response = okhttp3.OkHttpClient().newCall(
                Request.Builder().url(server.url("/chunked-oversized")).build()
            ).execute()
            val result = response.use { runCatching { readBoundedHubResponse(it) } }

            assertTrue("Unknown-length chunked Hub bodies must be rejected.", result.isFailure)
            assertEquals("Hub response exceeded the size limit.", result.exceptionOrNull()?.message)
        } finally {
            server.shutdown()
        }
    }

}
