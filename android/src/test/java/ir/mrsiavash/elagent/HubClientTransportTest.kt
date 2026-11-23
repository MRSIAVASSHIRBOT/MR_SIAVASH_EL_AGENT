package ir.mrsiavash.elagent

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.time.Instant
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class HubClientTransportTest {
    private val validClaim = AndroidDeviceTaskClaim(
        taskId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        action = "app.launch",
        app = "browser",
        ownerConfirmed = false,
        timeoutSeconds = 15,
        leaseToken = "l".repeat(48),
        leaseExpiresAt = Instant.parse("2099-01-01T00:00:00Z")
    )

    private fun completion(
        taskId: String = validClaim.taskId,
        status: String = "reported_succeeded",
        outcomeVerified: Boolean? = false
    ): JSONObject {
        val task = JSONObject()
            .put("task_id", taskId)
            .put("status", status)
        if (outcomeVerified != null) task.put("outcome_verified", outcomeVerified)
        return JSONObject().put("task", task)
    }

    @Test
    fun acceptsOnlyTheMatchingExplicitlyUnverifiedCompletionRecord() {
        validateReportedTaskOutcome(completion(), validClaim, succeeded = true)
        validateReportedTaskOutcome(
            completion(status = "reported_failed"),
            validClaim,
            succeeded = false
        )
    }

    @Test
    fun rejectsCompletionForAnotherTaskIdentity() {
        val result = runCatching {
            validateReportedTaskOutcome(
                completion(taskId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
                validClaim,
                succeeded = true
            )
        }
        assertTrue(result.isFailure)
        assertEquals("Hub returned an unexpected task outcome record.", result.exceptionOrNull()?.message)
    }

    @Test
    fun rejectsAStatusThatDoesNotMatchTheReportedOutcome() {
        val result = runCatching {
            validateReportedTaskOutcome(
                completion(status = "reported_failed"),
                validClaim,
                succeeded = true
            )
        }
        assertTrue(result.isFailure)
    }

    @Test
    fun rejectsVerifiedOrMissingOutcomeVerificationMarkers() {
        val verified = runCatching {
            validateReportedTaskOutcome(
                completion(outcomeVerified = true),
                validClaim,
                succeeded = true
            )
        }
        val missing = runCatching {
            validateReportedTaskOutcome(
                completion(outcomeVerified = null),
                validClaim,
                succeeded = true
            )
        }
        assertTrue(verified.isFailure)
        assertTrue(missing.isFailure)
    }

    @Test
    fun rejectsACompletionWithoutTheTaskEnvelope() {
        val result = runCatching {
            validateReportedTaskOutcome(JSONObject(), validClaim, succeeded = true)
        }
        assertTrue(result.isFailure)
        assertEquals(
            "Hub did not confirm that the task outcome was recorded.",
            result.exceptionOrNull()?.message
        )
    }
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
