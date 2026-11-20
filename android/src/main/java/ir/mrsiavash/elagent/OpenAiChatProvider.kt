package ir.mrsiavash.elagent

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.concurrent.TimeUnit

/** Explicit online provider. The API key is supplied by Android Keystore-backed SecureStore. */
class OpenAiChatProvider(
    private val apiKey: String,
    private val model: String,
    private val endpoint: String = API_URL,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
) {
    init {
        require(apiKey.length in 20..512 && apiKey.none { it.isWhitespace() }) {
            "Cloud API key is missing or invalid."
        }
        require(model.length in 1..100 && model.matches(Regex("[A-Za-z0-9._:-]+"))) {
            "Cloud model name is invalid."
        }
        val parsed = URI(endpoint)
        val officialEndpoint = parsed.scheme == "https" &&
            parsed.host == "api.openai.com" &&
            parsed.path == "/v1/chat/completions" &&
            (parsed.port == -1 || parsed.port == 443) &&
            parsed.userInfo == null && parsed.query == null && parsed.fragment == null
        val loopbackTestEndpoint = parsed.scheme in setOf("http", "https") &&
            parsed.host in setOf("127.0.0.1", "localhost")
        require(officialEndpoint || loopbackTestEndpoint) {
            "Cloud endpoint must be the official API HTTPS endpoint."
        }
        require(!client.followRedirects && !client.followSslRedirects) {
            "Cloud provider client must refuse redirects."
        }
    }

    fun chat(prompt: String): String {
        require(prompt.isNotBlank() && prompt.length <= MAX_PROMPT_CHARS) {
            "Message must contain 1-$MAX_PROMPT_CHARS characters."
        }
        return complete(
            JSONArray().put(JSONObject().put("role", "user").put("content", prompt.trim())),
            structuredMode = false,
            maxTokens = 256
        )
    }

    /** Requests strict JSON Schema output; callers still validate the untrusted completion locally. */
    fun chatJson(systemPrompt: String, userPrompt: String): String {
        require(systemPrompt.isNotBlank() && systemPrompt.length <= MAX_SYSTEM_PROMPT_CHARS) {
            "System prompt is missing or too long."
        }
        require(userPrompt.isNotBlank() && userPrompt.length <= MAX_PROMPT_CHARS) {
            "Planning request is missing or too long."
        }
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt))
            .put(JSONObject().put("role", "user").put("content", userPrompt))
        return complete(messages, structuredMode = true, maxTokens = 900)
    }

    private fun complete(messages: JSONArray, structuredMode: Boolean, maxTokens: Int): String {
        val payload = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("max_completion_tokens", maxTokens)
        if (structuredMode) {
            payload.put(
                "response_format",
                JSONObject()
                    .put("type", "json_schema")
                    .put(
                        "json_schema",
                        JSONObject()
                            .put("name", "android_agent_plan_v1")
                            .put("strict", true)
                            .put("schema", agentPlanJsonSchema())
                    )
            )
        }

        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
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
            val raw = body.byteStream().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    require(output.size() + count <= MAX_RESPONSE_BYTES) {
                        "Cloud response exceeded the size limit."
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray().toString(Charsets.UTF_8)
            }

            val parsed = try {
                JSONObject(raw)
            } catch (_: Exception) {
                throw IllegalStateException("Cloud provider returned invalid JSON.")
            }
            val choice = parsed.optJSONArray("choices")?.optJSONObject(0)
                ?: throw IllegalStateException("Cloud provider returned no completion choice.")
            if (choice.optString("finish_reason") == "length") {
                throw IllegalStateException("Cloud response was truncated; reduce the request and try again.")
            }
            val content = choice.optJSONObject("message")?.optString("content", null)
            if (content.isNullOrBlank()) throw IllegalStateException("Cloud model returned no text.")
            return content.trim()
        }
    }

    companion object {
        const val API_URL = "https://api.openai.com/v1/chat/completions"
        const val MAX_PROMPT_CHARS = 5_000
        const val MAX_SYSTEM_PROMPT_CHARS = 6_000
        const val MAX_RESPONSE_BYTES = 64 * 1024
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun agentPlanJsonSchema(): JSONObject {
            val idSchema = JSONObject()
                .put("type", "string")
                .put("pattern", "^[a-z][a-z0-9_-]{0,31}$")
            val stepSchema = JSONObject()
                .put("type", "object")
                .put("additionalProperties", false)
                .put(
                    "properties",
                    JSONObject()
                        .put("id", idSchema)
                        .put("operation", JSONObject().put("type", "string").put(
                            "enum", JSONArray()
                                .put("open_app")
                                .put("open_url")
                                .put("read_selected_document")
                                .put("show_text")
                        ))
                        .put("depends_on", JSONObject().put(
                            "type", "array"
                        ).put("items", idSchema).put("maxItems", 8))
                        .put("target", JSONObject().put("type", JSONArray().put("string").put("null")).put("maxLength", 512))
                        .put("text", JSONObject().put("type", JSONArray().put("string").put("null")).put("maxLength", 1200))
                )
                .put("required", JSONArray().put("id").put("operation").put("depends_on").put("target").put("text"))
            return JSONObject()
                .put("type", "object")
                .put("additionalProperties", false)
                .put(
                    "properties",
                    JSONObject()
                        .put("schema_version", JSONObject().put("type", "integer").put("enum", JSONArray().put(1)))
                        .put("summary", JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 300))
                        .put("steps", JSONObject().put("type", "array").put("items", stepSchema).put("maxItems", 8))
                        .put("clarification", JSONObject().put("type", JSONArray().put("string").put("null")).put("maxLength", 400))
                )
                .put("required", JSONArray().put("schema_version").put("summary").put("steps").put("clarification"))
        }

    }
}
