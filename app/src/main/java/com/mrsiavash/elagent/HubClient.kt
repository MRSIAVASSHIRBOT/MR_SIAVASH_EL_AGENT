package com.mrsiavash.elagent

import android.content.Context
import android.os.Build
import android.os.SystemClock
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

class HubHttpException(val statusCode: Int, message: String) : Exception(message)

class HubClient(context: Context) {
    private val store = SecureStore(context)

    fun pairingPayload(): JSONObject = JSONObject()
        .put("device_id", store.deviceId())
        .put("platform", "android")
        .put("label", "Android personal assistant")
        .put("advertised_capabilities", org.json.JSONArray(listOf("device.heartbeat", "app.launch")))
        .put("scopes", org.json.JSONArray(listOf("device.heartbeat", "app.launch")))

    fun pair(hubOrigin: String, pairingCode: String): JSONObject {
        val origin = validateOrigin(hubOrigin)
        require(pairingCode.length in 40..128) { "کد Pairing معتبر نیست." }
        val result = request(
            origin,
            "/devices/pair",
            "POST",
            JSONObject()
                .put("pairing_code", pairingCode)
                .put("device_id", store.deviceId())
                .put("agent_version", VERSION)
                .put("os_version", "Android ${Build.VERSION.RELEASE ?: "unknown"}")
        )
        validateAndStore(origin, result)
        return result
    }

    fun status(): String {
        val credentials = store.credentials() ?: return "هنوز به Hub متصل نشده است."
        val scopes = credentials.optJSONArray("scopes")
        return "متصل به ${credentials.optString("hub_url")}\nScopes: ${scopes?.toString() ?: "[]"}"
    }

    fun heartbeat() {
        val credentials = readyCredentials()
        request(
            credentials.getString("hub_url"),
            "/devices/heartbeat",
            "POST",
            JSONObject()
                .put("agent_version", VERSION)
                .put("runtime_state", "ready")
                .put("app_uptime_seconds", (SystemClock.elapsedRealtime() / 1000L).coerceAtMost(31_536_000L)),
            credentials.getString("access_token")
        )
    }

    fun claimTask(): JSONObject? {
        val credentials = readyCredentials()
        val scopes = credentials.optJSONArray("scopes")
        val hasLaunchScope = (0 until (scopes?.length() ?: 0)).any { scopes!!.optString(it) == "app.launch" }
        if (!hasLaunchScope) return null
        val response = request(
            credentials.getString("hub_url"),
            "/devices/tasks/claim",
            "POST",
            JSONObject(),
            credentials.getString("access_token")
        )
        return response.optJSONObject("task")
    }

    fun completeTask(taskId: String, succeeded: Boolean, resultCode: String) {
        val credentials = readyCredentials()
        request(
            credentials.getString("hub_url"),
            "/devices/tasks/$taskId/complete",
            "POST",
            JSONObject()
                .put("outcome", if (succeeded) "succeeded" else "failed")
                .put("result_code", resultCode),
            credentials.getString("access_token")
        )
    }

    private fun readyCredentials(): JSONObject {
        var credentials = store.credentials() ?: error("ابتدا دستگاه را با Hub جفت کنید.")
        val refreshExpiry = Instant.parse(credentials.getString("refresh_expires_at"))
        require(refreshExpiry.isAfter(Instant.now())) { "اعتبار Refresh منقضی شده؛ دستگاه را دوباره Pair کنید." }
        val accessExpiry = Instant.parse(credentials.getString("access_expires_at"))
        if (accessExpiry.isBefore(Instant.now().plusSeconds(90))) {
            val refreshed = request(
                credentials.getString("hub_url"),
                "/devices/refresh",
                "POST",
                JSONObject()
                    .put("device_id", store.deviceId())
                    .put("refresh_token", credentials.getString("refresh_token"))
            )
            val scopes = refreshed.optJSONArray("scopes") ?: error("Hub scope response is missing.")
            val oldScopes = credentials.optJSONArray("scopes") ?: error("Stored scope data is missing.")
            require(scopes.toString() == oldScopes.toString()) { "Hub scope changed; explicit re-pairing is required." }
            credentials.put("access_token", refreshed.getString("access_token"))
            credentials.put("refresh_token", refreshed.getString("refresh_token"))
            credentials.put("access_expires_at", Instant.now().plusSeconds(refreshed.getLong("expires_in")).toString())
            credentials.put("refresh_expires_at", Instant.now().plusSeconds(refreshed.getLong("refresh_expires_in")).toString())
            store.saveCredentials(credentials)
        }
        return credentials
    }

    private fun validateAndStore(origin: String, result: JSONObject) {
        require(result.getString("device_id") == store.deviceId()) { "Hub device identity mismatch." }
        val scopes = result.optJSONArray("scopes") ?: error("Hub did not return granted scopes.")
        val granted = (0 until scopes.length()).map { scopes.getString(it) }.toSet()
        require("device.heartbeat" in granted && granted.all { it in setOf("device.heartbeat", "app.launch") }) {
            "Hub granted an unsupported scope set."
        }
        val credentials = JSONObject()
            .put("hub_url", origin)
            .put("device_id", store.deviceId())
            .put("access_token", result.getString("access_token"))
            .put("refresh_token", result.getString("refresh_token"))
            .put("access_expires_at", Instant.now().plusSeconds(result.getLong("expires_in")).toString())
            .put("refresh_expires_at", Instant.now().plusSeconds(result.getLong("refresh_expires_in")).toString())
            .put("scopes", scopes)
        store.saveCredentials(credentials)
    }

    private fun request(
        origin: String,
        path: String,
        method: String,
        body: JSONObject,
        bearer: String? = null
    ): JSONObject {
        val connection = URL(origin + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json")
            if (bearer != null) connection.setRequestProperty("Authorization", "Bearer $bearer")
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
            if (response.length > 1_000_000) throw IllegalStateException("Hub response exceeded the size limit.")
            if (code !in 200..299) {
                val detail = runCatching { JSONObject(response).optString("detail") }.getOrDefault("request rejected")
                throw HubHttpException(code, "Hub HTTP $code: $detail")
            }
            return JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }

    private fun validateOrigin(value: String): String {
        val trimmed = value.trim().trimEnd('/')
        val uri = java.net.URI(trimmed)
        require(uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null &&
            uri.scheme == "https") {
            "آدرس Hub باید HTTPS باشد؛ HTTP فقط برای localhost توسعه مجاز است."
        }
        require(uri.path.isNullOrEmpty() || uri.path == "/") { "فقط آدرس اصلی Hub را وارد کنید، نه مسیر API." }
        return trimmed
    }

    companion object { const val VERSION = "0.3.0" }
}
