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
        val next = FakeProvider(FreeProviderId.OPENROUTER, result = """{"schema_version":1,"summary":"ok","steps":[{"id":"open","operation":"open_app","depends_on":[],"target":"browser","text":null}],"clarification":null}""")
        val result = FreeProviderPlanRouter(providersOverride = listOf(limited, next))
            .generate("system", "user")
        assertEquals(FreeProviderId.OPENROUTER, result.provider)
        assertTrue(result.json.contains("\"schema_version\":1"))
        assertEquals(1, limited.calls)
        assertEquals(1, next.calls)
    }

    @Test fun routerDoesNotRetrySameProvider() {
        val failed = FakeProvider(
            FreeProviderId.GEMINI,
            failure = ProviderRequestException(FreeProviderId.GEMINI, ProviderFailureKind.RATE_LIMIT, "limited")
        )
        val fallback = FakeProvider(FreeProviderId.OPENROUTER, result = """{"schema_version":1,"summary":"ok","steps":[{"id":"open","operation":"open_app","depends_on":[],"target":"browser","text":null}],"clarification":null}""")
        FreeProviderPlanRouter(providersOverride = listOf(failed, fallback)).generate("system", "user")
        assertEquals(1, failed.calls)
    }

    @Test fun routerReportsAllProvidersUnavailable() {
        val first = FakeProvider(
            FreeProviderId.GEMINI,
            failure = ProviderRequestException(FreeProviderId.GEMINI, ProviderFailureKind.QUOTA_EXHAUSTED, "quota")
        )
        val second = FakeProvider(
            FreeProviderId.OPENROUTER,
            failure = ProviderRequestException(FreeProviderId.OPENROUTER, ProviderFailureKind.RATE_LIMIT, "limited", 25)
        )
        val error = org.junit.Assert.assertThrows(NoFreeProviderAvailableException::class.java) {
            FreeProviderPlanRouter(providersOverride = listOf(first, second)).generate("system", "user")
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
            listOf(FreeProviderId.GEMINI, FreeProviderId.OPENROUTER),
            FreeProviderPlanRouter.DEFAULT_PROVIDER_ORDER
        )
    }

    @Test fun routerFallsBackWhenFirstProviderReturnsInvalidPlanJson() {
        val malformed = FakeProvider(FreeProviderId.GEMINI, result = """{"unexpected":true}""")
        val valid = FakeProvider(FreeProviderId.OPENROUTER, result = """{"schema_version":1,"summary":"ok","steps":[{"id":"open","operation":"open_app","depends_on":[],"target":"browser","text":null}],"clarification":null}""")
        val result = FreeProviderPlanRouter(providersOverride = listOf(malformed, valid))
            .generate(AgentPlanPrompt.SYSTEM_PROMPT, "open browser")
        assertEquals(FreeProviderId.OPENROUTER, result.provider)
        assertEquals(1, malformed.calls)
        assertEquals(1, valid.calls)
    }
}

