package ir.mrsiavash.elagent

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

enum class FreeProviderId(val storageId: String, val label: String) {
    GEMINI("gemini", "Google Gemini"),
    OPENROUTER("openrouter", "OpenRouter")
}

enum class ProviderFailureKind {
    RATE_LIMIT, QUOTA_EXHAUSTED, AUTHENTICATION, INVALID_REQUEST, TEMPORARY, INVALID_RESPONSE, UNKNOWN
}

class ProviderRequestException(
    val provider: FreeProviderId,
    val kind: ProviderFailureKind,
    message: String,
    val retryAfterSeconds: Long? = null
) : IllegalStateException(message)

data class RoutedPlanResponse(
    val json: String,
    val provider: FreeProviderId,
    val model: String
)

interface StructuredPlanProvider {
    val id: FreeProviderId
    val model: String
    fun generate(systemPrompt: String, userPrompt: String): String
}

object ProviderFailurePolicy {
    private val quotaHints = listOf("quota", "billing", "insufficient", "payment", "credit balance", "daily limit", "resource_exhausted")

    fun classify(status: Int, body: String): ProviderFailureKind {
        val normalized = body.lowercase()
        if (status == 402 || quotaHints.any { normalized.contains(it) }) {
            return ProviderFailureKind.QUOTA_EXHAUSTED
        }
        return when (status) {
            401, 403 -> ProviderFailureKind.AUTHENTICATION
            429 -> ProviderFailureKind.RATE_LIMIT
            in 500..599 -> ProviderFailureKind.TEMPORARY
            in 400..499 -> ProviderFailureKind.INVALID_REQUEST
            else -> ProviderFailureKind.UNKNOWN
        }
    }

    fun retryAfterSeconds(header: String?, nowMillis: Long = System.currentTimeMillis()): Long? {
        val value = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        value.toLongOrNull()?.let { return it.coerceAtLeast(0) }
        return runCatching {
            val instant = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            ((instant.toEpochMilli() - nowMillis).coerceAtLeast(0) / 1_000L)
        }.getOrNull()
    }

    fun userFacing(kind: ProviderFailureKind): String = when (kind) {
        ProviderFailureKind.RATE_LIMIT -> "محدودیت موقت تعداد درخواست"
        ProviderFailureKind.QUOTA_EXHAUSTED -> "سهمیهٔ رایگان تمام شده یا محدودیت حساب وجود دارد"
        ProviderFailureKind.AUTHENTICATION -> "کلید API نامعتبر است یا دسترسی سرویس تأیید نشد"
        ProviderFailureKind.INVALID_REQUEST -> "مدل یا قالب درخواست توسط سرویس پذیرفته نشد"
        ProviderFailureKind.TEMPORARY -> "اختلال موقت سرویس"
        ProviderFailureKind.INVALID_RESPONSE -> "پاسخ سرویس قابل‌استفاده نبود"
        ProviderFailureKind.UNKNOWN -> "خطای نامشخص سرویس"
    }
}

internal object ProviderHttp {
    val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(45, TimeUnit.SECONDS)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    const val MAX_RESPONSE_BYTES = 64 * 1024

    fun readBounded(response: okhttp3.Response): String {
        val body = response.body ?: throw IllegalStateException("سرویس پاسخ خالی برگرداند.")
        if (body.contentLength() > MAX_RESPONSE_BYTES) {
            throw IllegalStateException("پاسخ سرویس از حد مجاز بزرگ‌تر است.")
        }
        val output = ByteArrayOutputStream()
        body.byteStream().use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                require(output.size() + count <= MAX_RESPONSE_BYTES) {
                    "پاسخ سرویس از حد مجاز بزرگ‌تر است."
                }
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray().toString(Charsets.UTF_8)
    }

    fun errorBody(response: okhttp3.Response): String =
        runCatching { readBounded(response) }.getOrDefault("")

    fun fail(provider: FreeProviderId, response: okhttp3.Response, rawBody: String): Nothing {
        val kind = ProviderFailurePolicy.classify(response.code, rawBody)
        val retryAfter = ProviderFailurePolicy.retryAfterSeconds(response.header("Retry-After"))
        val providerMessage = runCatching {
            val error = JSONObject(rawBody).optJSONObject("error")
            (error?.optString("message") ?: JSONObject(rawBody).optString("message"))
                .takeIf { it.isNotBlank() && it != "null" }
                ?.replace(Regex("\\s+"), " ")
                ?.take(140)
        }.getOrNull()
        val diagnostic = ProviderFailurePolicy.userFacing(kind) +
            " · HTTP ${response.code}" +
            (providerMessage?.let { " · $it" } ?: "")
        throw ProviderRequestException(provider, kind, diagnostic, retryAfter)
    }

    fun parseChatCompletion(provider: FreeProviderId, raw: String): String {
        val parsed = runCatching { JSONObject(raw) }.getOrElse {
            throw ProviderRequestException(provider, ProviderFailureKind.INVALID_RESPONSE, "پاسخ سرویس JSON معتبر نیست.")
        }
        val choice = parsed.optJSONArray("choices")?.optJSONObject(0)
            ?: throw ProviderRequestException(provider, ProviderFailureKind.INVALID_RESPONSE, "پاسخ سرویس فاقد نتیجه است.")
        if (choice.optString("finish_reason") == "length") {
            throw ProviderRequestException(provider, ProviderFailureKind.INVALID_RESPONSE, "پاسخ مدل ناقص بود.")
        }
        val message = choice.optJSONObject("message")
            ?: throw ProviderRequestException(provider, ProviderFailureKind.INVALID_RESPONSE, "ساختار پاسخ مدل نامعتبر است.")
        val content = message.optString("content", "").trim()
        if (content.isEmpty()) {
            throw ProviderRequestException(provider, ProviderFailureKind.INVALID_RESPONSE, "مدل خروجی متنی تولید نکرد.")
        }
        // Free models do not consistently support response_format/json_object. Ask for JSON
        // in the prompt, then tolerate common Markdown fences or a short prose preamble.
        val unfenced = content
            .replace(Regex("^\\s*```(?:json)?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*```\\s*$"), "")
            .trim()
        val firstBrace = unfenced.indexOf('{')
        val lastBrace = unfenced.lastIndexOf('}')
        if (firstBrace < 0 || lastBrace <= firstBrace) {
            throw ProviderRequestException(provider, ProviderFailureKind.INVALID_RESPONSE, "مدل JSON قابل‌استخراج تولید نکرد.")
        }
        return unfenced.substring(firstBrace, lastBrace + 1)
    }
}

class OpenRouterFreePlanProvider(
    private val apiKey: String,
    override val model: String = DEFAULT_MODEL,
    private val client: OkHttpClient = ProviderHttp.client
) : StructuredPlanProvider {
    override val id = FreeProviderId.OPENROUTER

    init {
        require(apiKey.length in 20..512 && apiKey.none { it.isWhitespace() }) { "کلید OpenRouter نامعتبر است." }
        require(model == "openrouter/free" || model.endsWith(":free")) {
            "برای جلوگیری از هزینه، OpenRouter فقط با مدل free مجاز است."
        }
    }

    override fun generate(systemPrompt: String, userPrompt: String): String {
        val payload = JSONObject()
            .put("model", model)
            .put("messages", org.json.JSONArray()
                .put(JSONObject().put("role", "system").put("content", systemPrompt))
                .put(JSONObject().put("role", "user").put("content", userPrompt)))
            .put("max_tokens", 1800)
            .put("temperature", 0.2)
        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .header("Authorization", "Bearer " + apiKey)
            .header("HTTP-Referer", "https://github.com/MRSIAVASSHIRBOT/MR_SIAVASH_EL_AGENT")
            .header("X-Title", "MR SIAVASH EL AGENT")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(ProviderHttp.jsonMediaType))
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code in 300..399) {
                throw ProviderRequestException(id, ProviderFailureKind.AUTHENTICATION, "تغییر مسیر سرویس برای حفاظت از کلید API رد شد.")
            }
            val raw = if (response.isSuccessful) ProviderHttp.readBounded(response) else ProviderHttp.errorBody(response)
            if (!response.isSuccessful) ProviderHttp.fail(id, response, raw)
            return ProviderHttp.parseChatCompletion(id, raw)
        }
    }

    companion object {
        const val DEFAULT_MODEL = "openrouter/free"
    }
}

class GeminiFreePlanProvider(
    private val apiKey: String,
    override val model: String = DEFAULT_MODEL,
    private val client: OkHttpClient = ProviderHttp.client
) : StructuredPlanProvider {
    override val id = FreeProviderId.GEMINI

    init {
        require(apiKey.length in 20..512 && apiKey.none { it.isWhitespace() }) { "کلید Gemini نامعتبر است." }
        require(model.matches(Regex("[A-Za-z0-9._-]{1,100}"))) { "شناسهٔ مدل Gemini نامعتبر است." }
    }

    override fun generate(systemPrompt: String, userPrompt: String): String {
        val payload = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text", systemPrompt))))
            .put("contents", org.json.JSONArray().put(
                JSONObject().put("role", "user")
                    .put("parts", org.json.JSONArray().put(JSONObject().put("text", userPrompt)))
            ))
            .put("generationConfig", JSONObject()
                .put("responseMimeType", "application/json")
                .put("temperature", 0.2)
                .put("maxOutputTokens", 1400))
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent")
            .header("x-goog-api-key", apiKey)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(ProviderHttp.jsonMediaType))
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code in 300..399) {
                throw ProviderRequestException(id, ProviderFailureKind.AUTHENTICATION, "تغییر مسیر سرویس برای حفاظت از کلید API رد شد.")
            }
            val raw = if (response.isSuccessful) ProviderHttp.readBounded(response) else ProviderHttp.errorBody(response)
            if (!response.isSuccessful) ProviderHttp.fail(id, response, raw)
            val parsed = runCatching { JSONObject(raw) }.getOrElse {
                throw ProviderRequestException(id, ProviderFailureKind.INVALID_RESPONSE, "پاسخ Gemini معتبر نیست.")
            }
            val candidate = parsed.optJSONArray("candidates")?.optJSONObject(0)
                ?: throw ProviderRequestException(id, ProviderFailureKind.INVALID_RESPONSE, "Gemini نتیجه‌ای برنگرداند.")
            if (candidate.optString("finishReason") == "MAX_TOKENS") {
                throw ProviderRequestException(id, ProviderFailureKind.INVALID_RESPONSE, "پاسخ Gemini ناقص بود.")
            }
            val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
                ?: throw ProviderRequestException(id, ProviderFailureKind.INVALID_RESPONSE, "ساختار پاسخ Gemini نامعتبر است.")
            val text = buildString {
                for (index in 0 until parts.length()) {
                    append(parts.optJSONObject(index)?.optString("text", "") ?: "")
                }
            }.trim()
            if (text.isEmpty()) {
                throw ProviderRequestException(id, ProviderFailureKind.INVALID_RESPONSE, "Gemini خروجی متنی تولید نکرد.")
            }
            return text
        }
    }

    companion object {
        const val DEFAULT_MODEL = "gemini-4-hydrogen"
    }
}

class NoFreeProviderAvailableException(
    val providerErrors: List<ProviderRequestException>,
    val retryAfterSeconds: Long?
) : IllegalStateException(
    if (providerErrors.isEmpty()) {
        "هیچ کلید API رایگان ذخیره نشده است. ابتدا کلید Gemini یا OpenRouter را ذخیره کنید."
    } else {
        "هیچ‌یک از سرویس‌های رایگان در دسترس نیستند: " +
            providerErrors.joinToString("؛ ") {
                it.provider.label + ": " + ProviderFailurePolicy.userFacing(it.kind)
            } +
            (retryAfterSeconds?.let { "؛ زمان پیشنهادی تلاش مجدد: " + it + " ثانیه" } ?: "")
    }
)

class FreeProviderPlanRouter(
    private val store: SecureStore? = null,
    private val providersOverride: List<StructuredPlanProvider>? = null
) {
    fun generate(systemPrompt: String, userPrompt: String): RoutedPlanResponse {
        require(systemPrompt.isNotBlank() && systemPrompt.length <= 6_000)
        require(userPrompt.isNotBlank() && userPrompt.length <= 5_000)
        val providers = providersOverride ?: configuredProviders()
        val failures = mutableListOf<ProviderRequestException>()
        for (provider in providers) {
            try {
                val json = provider.generate(systemPrompt, userPrompt)
                try {
                    AgentPlanParser.parse(json)
                } catch (_: IllegalArgumentException) {
                    throw ProviderRequestException(
                        provider.id,
                        ProviderFailureKind.INVALID_RESPONSE,
                        "خروجی مدل با ساختار طرح موردنیاز سازگار نبود."
                    )
                }
                return RoutedPlanResponse(json, provider.id, provider.model)
            } catch (error: ProviderRequestException) {
                failures += error
            } catch (error: IOException) {
                failures += ProviderRequestException(
                    provider.id,
                    ProviderFailureKind.TEMPORARY,
                    "ارتباط شبکه با این سرویس برقرار نشد."
                )
            } catch (error: IllegalStateException) {
                failures += ProviderRequestException(
                    provider.id,
                    ProviderFailureKind.INVALID_RESPONSE,
                    error.message?.take(160) ?: "پاسخ سرویس قابل‌استفاده نبود."
                )
            } catch (_: Exception) {
                failures += ProviderRequestException(
                    provider.id,
                    ProviderFailureKind.INVALID_RESPONSE,
                    "پاسخ سرویس قابل‌استفاده نبود."
                )
            }
        }
        val retry = failures.mapNotNull { it.retryAfterSeconds }.minOrNull()
        throw NoFreeProviderAvailableException(failures, retry)
    }

    companion object {
        val DEFAULT_PROVIDER_ORDER = listOf(
            FreeProviderId.GEMINI,
            FreeProviderId.OPENROUTER
        )
    }

    private fun configuredProviders(): List<StructuredPlanProvider> {
        val secureStore = store ?: throw IllegalStateException("SecureStore is required when providers are not injected.")
        val gemini = secureStore.providerApiKey(FreeProviderId.GEMINI.storageId)
        val openRouter = secureStore.providerApiKey(FreeProviderId.OPENROUTER.storageId)
        val configured = mapOf(
            FreeProviderId.GEMINI to gemini,
            FreeProviderId.OPENROUTER to openRouter
        )
        return DEFAULT_PROVIDER_ORDER.mapNotNull { provider ->
            val key = configured[provider].orEmpty()
            if (key.isBlank()) null else when (provider) {
                FreeProviderId.GEMINI -> GeminiFreePlanProvider(key)
                FreeProviderId.OPENROUTER -> OpenRouterFreePlanProvider(key)
            }
        }
    }
}
