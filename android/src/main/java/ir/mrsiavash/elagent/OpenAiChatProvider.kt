package ir.mrsiavash.elagent

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Explicit, single-turn online text provider.
 * The API key is supplied by SecureStore and is never persisted by this class.
 */
class OpenAiChatProvider(
    private val apiKey: String,
    private val model: String,
    private val endpoint: String = API_URL,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
) {
    init {
        require(apiKey.length in 20..512 && apiKey.none { it.isWhitespace() }) {
            "Cloud API key is missing or invalid."
        }
        require(model.length in 1..100 && model.none { it.isWhitespace() }) {
            "Cloud model name is invalid."
        }
        val parsed = java.net.URI(endpoint)
        require(parsed.scheme == "https" || (
            parsed.scheme == "http" && parsed.host in setOf("127.0.0.1", "localhost")
        )) { "Cloud endpoint must use HTTPS." }
    }

    fun chat(prompt: String): String {
        require(prompt.isNotBlank() && prompt.length <= MAX_PROMPT_CHARS) {
            "Message must contain 1-$MAX_PROMPT_CHARS characters."
        }
        val messages = JSONArray().put(
            JSONObject().put("role", "user").put("content", prompt.trim())
        )
        val payload = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("max_completion_tokens", 256)
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code in 300..399) {
                throw IllegalStateException("Cloud provider redirect refused for credential safety.")
            }
            if (response.code == 401) throw IllegalStateException("Cloud provider rejected the API key.")
            if (response.code == 429) throw IllegalStateException("Cloud provider rate limit reached.")
            if (!response.isSuccessful) throw IllegalStateException("Cloud provider returned HTTP ${response.code}.")
            val body = response.body ?: throw IllegalStateException("Cloud provider returned an empty response.")
            if (body.contentLength() > MAX_RESPONSE_BYTES) {
                throw IllegalStateException("Cloud response exceeded the size limit.")
            }
            val raw = body.string()
            if (raw.toByteArray(Charsets.UTF_8).size > MAX_RESPONSE_BYTES) {
                throw IllegalStateException("Cloud response exceeded the size limit.")
            }
            val choices = JSONObject(raw).optJSONArray("choices")
            val message = choices?.optJSONObject(0)?.optJSONObject("message")
            val content = message?.optString("content", null)
            if (content.isNullOrBlank()) throw IllegalStateException("Cloud model returned no text.")
            return content.trim()
        }
    }

    companion object {
        const val API_URL = "https://api.openai.com/v1/chat/completions"
        const val MAX_PROMPT_CHARS = 8_000
        const val MAX_RESPONSE_BYTES = 1_000_000
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
