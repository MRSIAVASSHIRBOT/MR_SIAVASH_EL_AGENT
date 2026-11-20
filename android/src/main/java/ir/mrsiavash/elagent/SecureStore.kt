package ir.mrsiavash.elagent

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores device identity and Hub credentials encrypted with an Android Keystore AES-GCM key. */
class SecureStore(private val context: android.content.Context) {
    private val prefs = context.getSharedPreferences("el_agent_secure", android.content.Context.MODE_PRIVATE)
    private val alias = "mr_siavash_el_agent_v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    @Synchronized
    fun read(): JSONObject {
        val encoded = prefs.getString("payload", null) ?: return JSONObject()
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size > 12) { "Encrypted local data is invalid." }
        val iv = bytes.copyOfRange(0, 12)
        val ciphertext = bytes.copyOfRange(12, bytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return JSONObject(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
    }

    @Synchronized
    fun write(value: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toString().toByteArray(Charsets.UTF_8))
        val combined = cipher.iv + encrypted
        check(prefs.edit().putString("payload", Base64.encodeToString(combined, Base64.NO_WRAP)).commit()) {
            "Could not persist encrypted local data."
        }
    }

    @Synchronized
    fun saveCloudApiKey(apiKey: String) {
        require(apiKey.length in 20..512 && apiKey.none { it.isWhitespace() }) {
            "Cloud API key is invalid."
        }
        val value = read()
        value.put("cloud_api_key", apiKey)
        write(value)
    }

    @Synchronized
    fun cloudApiKey(): String = read().optString("cloud_api_key", "")

    @Synchronized
    fun clearCloudApiKey() {
        val value = read()
        value.remove("cloud_api_key")
        write(value)
    }

    fun clearCredentialsPreservingIdentity() {
        val value = read()
        value.remove("hub_url")
        value.remove("access_token")
        value.remove("refresh_token")
        value.remove("access_expires_at")
        value.remove("refresh_expires_at")
        value.remove("scopes")
        write(value)
    }

    fun deviceId(): String {
        val value = read()
        val current = value.optString("device_id", "")
        if (current.isNotBlank()) return java.util.UUID.fromString(current).toString()
        val generated = java.util.UUID.randomUUID().toString()
        value.put("device_id", generated)
        write(value)
        return generated
    }
}
