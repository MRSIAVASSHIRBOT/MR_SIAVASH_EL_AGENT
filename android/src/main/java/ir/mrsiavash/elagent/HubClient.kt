package ir.mrsiavash.elagent

import android.os.Build
import android.os.SystemClock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

class HubClient(private val store: SecureStore) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    companion object { private val credentialLock = Any() }

    private fun canonicalHubUrl(input: String): String {
        val uri = URI(input.trim())
        require(uri.scheme == "https" && !uri.host.isNullOrBlank()) {
            "Hub address must use HTTPS on Android."
        }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null && uri.path in listOf("", "/")) {
            "Enter only the Hub origin, without credentials or API paths."
        }
        return "https://${uri.rawAuthority}"
    }

    private fun request(base: String, path: String, payload: JSONObject, token: String? = null, deviceId: String? = null): JSONObject {
        val builder = Request.Builder().url(base + path)
            .post(payload.toString().toRequestBody(jsonType))
            .header("Accept", "application/json")
        if (token != null) builder.header("Authorization", "Bearer $token")
        if (deviceId != null) builder.header("X-Device-ID", deviceId)
        http.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("Hub request failed (HTTP ${response.code}).")
            return JSONObject(text)
        }
    }

    fun pair(hubInput: String, pairingCode: String): String {
        require(pairingCode.length in 40..128) { "Pairing code format is invalid." }
        val hub = canonicalHubUrl(hubInput)
        val id = store.deviceId()
        val payload = JSONObject()
            .put("pairing_code", pairingCode)
            .put("device_id", id)
            .put("agent_version", "0.3.0")
            .put("os_version", "Android ${Build.VERSION.RELEASE}".take(64))
        val result = request(hub, "/devices/pair", payload)
        require(result.optString("device_id") == id) { "Hub returned a different device identity." }
        val access = result.optString("access_token")
        val refresh = result.optString("refresh_token")
        val accessTtl = result.optLong("expires_in", -1)
        val refreshTtl = result.optLong("refresh_expires_in", -1)
        require(access.length >= 40 && refresh.length >= 40) { "Hub returned malformed device credentials." }
        require(accessTtl in 30L..3600L && refreshTtl in 3600L..(90L * 24 * 3600)) {
            "Hub returned invalid credential lifetimes."
        }
        val scopes = result.optJSONArray("scopes") ?: throw IllegalStateException("Hub omitted granted scopes.")
        require((0 until scopes.length()).all { scopes.optString(it).matches(Regex("[a-z_.]+")) }) {
            "Hub returned malformed device scopes."
        }
        val now = System.currentTimeMillis()
        val saved = store.read()
            .put("hub_url", hub)
            .put("device_id", id)
            .put("access_token", access)
            .put("refresh_token", refresh)
            .put("access_expires_at", now + accessTtl * 1000L)
            .put("refresh_expires_at", now + refreshTtl * 1000L)
            .put("scopes", scopes)
        store.write(saved)
        return "Paired successfully. Granted scopes: " + (0 until scopes.length()).joinToString { scopes.getString(it) }
    }

    fun refreshIfNeeded(): JSONObject = synchronized(credentialLock) {
        val saved = store.read()
        val hub = saved.getString("hub_url")
        val id = saved.getString("device_id")
        if (saved.optLong("access_expires_at") > System.currentTimeMillis() + 60_000L) {
            saved
        } else {
            require(saved.optLong("refresh_expires_at") > System.currentTimeMillis()) {
                "Refresh credential expired. Revoke this device in Hub and pair again."
            }
            val refreshed = request(
                hub, "/devices/refresh",
                JSONObject().put("refresh_token", saved.getString("refresh_token")).put("device_id", id)
            )
            require(refreshed.optString("device_id") == id) { "Hub refresh returned a different device identity." }
            val access = refreshed.optString("access_token")
            val refresh = refreshed.optString("refresh_token")
            val accessTtl = refreshed.optLong("expires_in", -1)
            val refreshTtl = refreshed.optLong("refresh_expires_in", -1)
            require(access.length >= 40 && refresh.length >= 40 && accessTtl in 30L..3600L && refreshTtl in 3600L..(90L * 24 * 3600)) {
                "Hub returned invalid refreshed credentials."
            }
            saved.put("access_token", access)
                .put("refresh_token", refresh)
                .put("access_expires_at", System.currentTimeMillis() + accessTtl * 1000L)
                .put("refresh_expires_at", System.currentTimeMillis() + refreshTtl * 1000L)
            store.write(saved)
            saved
        }
    }

    fun heartbeat(): String {
        val saved = refreshIfNeeded()
        val payload = JSONObject()
            .put("agent_version", "0.3.0")
            .put("runtime_state", "ready")
            .put("app_uptime_seconds", (SystemClock.elapsedRealtime() / 1000L).coerceAtMost(31_536_000L))
        val response = request(
            saved.getString("hub_url"), "/devices/heartbeat", payload,
            saved.getString("access_token"), saved.getString("device_id")
        )
        return "Hub heartbeat: ${response.optString("status", "unknown")}"
    }

    fun isPaired(): Boolean = store.read().has("refresh_token")
}
