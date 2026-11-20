package ir.mrsiavash.elagent

import org.junit.Assert.*
import org.junit.Test

class FreeProviderRouterTest {
    private class FakeProvider(
        override val id: FreeProviderId,
        private val result: String? = null,
        private val failure: ProviderRequestException? = null
    ) : StructuredPlanProvider {
        override val model = "test-model"
        var calls = 0
        override fun generate(systemPrompt: String, userPrompt: String): String {
            calls++
            failure?.let { throw it }
            return result ?: "{}"
        }
    }

    @Test fun routerFallsBackAfterRateLimit() {
        val limited = FakeProvider(
            FreeProviderId.GEMINI,
            failure = ProviderRequestException(FreeProviderId.GEMINI, ProviderFailureKind.RATE_LIMIT, "limited", 12)
        )
        val next = FakeProvider(FreeProviderId.GROQ, result = """{"steps":[]}""")
        val router = FreeProviderPlanRouterForTest(listOf(limited, next))
        val result = router.generate()
        assertEquals(FreeProviderId.GROQ, result.provider)
        assertEquals(1, limited.calls)
        assertEquals(1, next.calls)
    }

    @Test fun routerDoesNotRetrySameProvider() {
        val failed = FakeProvider(
            FreeProviderId.GEMINI,
            failure = ProviderRequestException(FreeProviderId.GEMINI, ProviderFailureKind.RATE_LIMIT, "limited")
        )
        val fallback = FakeProvider(FreeProviderId.OPENROUTER, result = "{}")
        FreeProviderPlanRouterForTest(listOf(failed, fallback)).generate()
        assertEquals(1, failed.calls)
    }

    @Test fun routerReportsAllProvidersUnavailable() {
        val first = FakeProvider(
            FreeProviderId.GEMINI,
            failure = ProviderRequestException(FreeProviderId.GEMINI, ProviderFailureKind.QUOTA_EXHAUSTED, "quota")
        )
        val second = FakeProvider(
            FreeProviderId.GROQ,
            failure = ProviderRequestException(FreeProviderId.GROQ, ProviderFailureKind.RATE_LIMIT, "limited", 25)
        )
        val error = org.junit.Assert.assertThrows(NoFreeProviderAvailableException::class.java) {
            FreeProviderPlanRouterForTest(listOf(first, second)).generate()
        }
        assertEquals(25L, error.retryAfterSeconds)
        assertEquals(2, error.providerErrors.size)
    }

    @Test fun classifiesQuotaSeparatelyFromTransientRateLimit() {
        assertEquals(ProviderFailureKind.RATE_LIMIT, ProviderFailurePolicy.classify(429, "Too many requests"))
        assertEquals(ProviderFailureKind.QUOTA_EXHAUSTED, ProviderFailurePolicy.classify(429, "Daily quota exceeded"))
        assertEquals(ProviderFailureKind.QUOTA_EXHAUSTED, ProviderFailurePolicy.classify(402, "Payment required"))
    }

    @Test fun parsesRetryAfterSecondsAndHttpDate() {
        assertEquals(7L, ProviderFailurePolicy.retryAfterSeconds("7", 1_000L))
        assertEquals(0L, ProviderFailurePolicy.retryAfterSeconds("0", 1_000L))
        assertNotNull(ProviderFailurePolicy.retryAfterSeconds("Wed, 21 Oct 2015 07:28:00 GMT", 0L))
    }

    @Test fun openRouterRejectsPotentiallyBillableModelIds() {
        assertThrows(IllegalArgumentException::class.java) {
            OpenRouterFreePlanProvider("test-api-key-that-is-long-enough", "openai/gpt-oss-20b")
        }
        assertThrows(IllegalArgumentException::class.java) {
            OpenRouterFreePlanProvider("test-api-key-that-is-long-enough", "provider/model")
        }
        assertEquals(
            "openrouter/free",
            OpenRouterFreePlanProvider("test-api-key-that-is-long-enough").model
        )
    }

    @Test fun freeProviderOrderIsExplicit() {
        assertEquals(
            listOf(FreeProviderId.GEMINI, FreeProviderId.GROQ, FreeProviderId.OPENROUTER),
            listOf(FreeProviderId.GEMINI, FreeProviderId.GROQ, FreeProviderId.OPENROUTER)
        )
    }
}

private class FreeProviderPlanRouterForTest(private val providers: List<StructuredPlanProvider>) {
    data class Result(val provider: FreeProviderId, val json: String)
    fun generate(): Result {
        val errors = mutableListOf<ProviderRequestException>()
        for (provider in providers) {
            try {
                return Result(provider.id, provider.generate("system", "user"))
            } catch (error: ProviderRequestException) {
                errors += error
            }
        }
        throw NoFreeProviderAvailableException(errors, errors.mapNotNull { it.retryAfterSeconds }.minOrNull())
    }
}
