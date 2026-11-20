package ir.mrsiavash.elagent

import android.os.Build
import android.os.SystemClock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.time.Instant
import java.util.concurrent.TimeUnit

data class AndroidDeviceTaskClaim(
    val taskId: String,
    val action: String,
    val app: String?,
    val ownerConfirmed: Boolean,
    val timeoutSeconds: Long,
    val leaseToken: String,
    val leaseExpiresAt: Instant
)

class HubClient(private val store: SecureStore) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    companion object {
        private val credentialLock = Any()
        private val processStartedAt = SystemClock.elapsedRealtime()
    }

    private fun canonicalHubUrl(input: String): String {
        val uri = URI(input.trim())
        require(uri.scheme == "https" && !uri.host.isNullOrBlank()) {
            "Hub address must use HTTPS on Android."
        }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null && uri.path in listOf("", "/")) {
            "Enter only the Hub origin, without credentials or API paths."
        }
        return "https://" + uri.rawAuthority
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

    fun pair(hubInput: String, pairingCodeInput: String): String {
        val pairingCode = pairingCodeInput.trim()
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
        val grantedScopes = (0 until scopes.length()).map { scopes.optString(it) }.toSet()
        require(
            "device.heartbeat" in grantedScopes &&
            grantedScopes.all { it == "device.heartbeat" || it == "app.launch" }
        ) {
            "Android supports only device.heartbeat and app.launch scopes in this preview."
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
        return "Paired successfully. Granted scopes: ${grantedScopes.sorted().joinToString()}"
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
            val scopes = refreshed.optJSONArray("scopes") ?: throw IllegalStateException("Hub omitted refreshed scopes.")
            val grantedScopes = (0 until scopes.length()).map { scopes.optString(it) }.toSet()
            require(
                access.length >= 40 && refresh.length >= 40 &&
                accessTtl in 30L..3600L && refreshTtl in 3600L..(90L * 24 * 3600) &&
                "device.heartbeat" in grantedScopes &&
                grantedScopes.all { it == "device.heartbeat" || it == "app.launch" }
            ) {
                "Hub returned invalid refreshed credentials or unsupported scopes."
            }
            saved.put("access_token", access)
                .put("refresh_token", refresh)
                .put("access_expires_at", System.currentTimeMillis() + accessTtl * 1000L)
                .put("refresh_expires_at", System.currentTimeMillis() + refreshTtl * 1000L)
                .put("scopes", scopes)
            store.write(saved)
            saved
        }
    }

    fun claimTask(): AndroidDeviceTaskClaim? {
        val saved = refreshIfNeeded()
        val scopes = saved.optJSONArray("scopes") ?: return null
        val grantedScopes = (0 until scopes.length()).map { scopes.optString(it) }.toSet()
        if ("app.launch" !in grantedScopes) return null

        val response = request(
            saved.getString("hub_url"), "/devices/tasks/claim", JSONObject(),
            saved.getString("access_token"), saved.getString("device_id")
        )
        val task = response.optJSONObject("task") ?: return null
        require(AndroidTaskPolicy.belongsToDevice(task.optString("device_id", null), saved.getString("device_id"))) {
            "Hub returned a task for a different device identity."
        }
        val taskId = task.optString("task_id")
        val action = task.optString("action")
        val requiredScope = task.optString("required_scope")
        val app = if (task.isNull("app")) null else task.optString("app")
        val timeout = task.optLong("timeout_seconds", -1)
        val lease = response.optString("lease_token")

        require(runCatching { java.util.UUID.fromString(taskId).toString() == taskId }.getOrDefault(false)) {
            "Hub returned a non-canonical Android task identifier."
        }
        require(requiredScope.isNotBlank()) { "Hub omitted the task's required scope." }

        val leaseExpiresAt = runCatching { Instant.parse(response.optString("lease_expires_at")) }
            .getOrElse { throw IllegalStateException("Hub returned an invalid lease expiry.", it) }
        val remainingLeaseSeconds = leaseExpiresAt.epochSecond - Instant.now().epochSecond
        require(
            timeout in 5L..60L &&
            lease.length in 40..128 &&
            remainingLeaseSeconds in 1L..60L
        ) {
            "Hub returned an invalid task lease or timeout."
        }

        val claim = AndroidDeviceTaskClaim(
            taskId, action, app, task.optBoolean("owner_confirmed", false),
            timeout, lease, leaseExpiresAt
        )
        if (!AndroidTaskPolicy.canLaunch(action, requiredScope, app, grantedScopes)) {
            // A device can be offered a valid Hub task that this Android
            // platform preview cannot perform. Report a bounded failure so
            // the one-shot claim does not sit leased until expiry.
            completeTask(
                claim,
                succeeded = false,
                errorCode = AndroidTaskPolicy.denialCode(requiredScope, grantedScopes),
                durationMs = 0L
            )
            return null
        }
        return claim
    }

    fun completeTask(
        claim: AndroidDeviceTaskClaim,
        succeeded: Boolean,
        errorCode: String?,
        durationMs: Long
    ) {
        require(Instant.now().isBefore(claim.leaseExpiresAt)) {
            "Hub task lease expired before the outcome could be reported."
        }
        val saved = refreshIfNeeded()
        val payload = JSONObject()
            .put("lease_token", claim.leaseToken)
            .put("outcome", if (succeeded) "succeeded" else "failed")
            .put("duration_ms", durationMs.coerceIn(0L, 60_000L))
        if (!succeeded) payload.put("error_code", errorCode ?: "execution_failed")
        val response = request(
            saved.getString("hub_url"), "/devices/tasks/${claim.taskId}/complete", payload,
            saved.getString("access_token"), saved.getString("device_id")
        )
        val reported = response.optJSONObject("task")
            ?: throw IllegalStateException("Hub did not confirm that the task outcome was recorded.")
        val expectedStatus = if (succeeded) "reported_succeeded" else "reported_failed"
        require(
            reported.optString("task_id") == claim.taskId &&
            reported.optString("status") == expectedStatus &&
            reported.optBoolean("outcome_verified", true) == false
        ) {
            "Hub returned an unexpected task outcome record."
        }
    }

    fun heartbeat(): String {
        val saved = refreshIfNeeded()
        val payload = JSONObject()
            .put("agent_version", "0.3.0")
            .put("runtime_state", "ready")
            .put("app_uptime_seconds", ((SystemClock.elapsedRealtime() - processStartedAt) / 1000L).coerceAtMost(31_536_000L))
        val response = request(
            saved.getString("hub_url"), "/devices/heartbeat", payload,
            saved.getString("access_token"), saved.getString("device_id")
        )
        return "Hub heartbeat: ${response.optString("status", "unknown")}"
    }

    fun isPaired(): Boolean = store.read().has("refresh_token")
}
