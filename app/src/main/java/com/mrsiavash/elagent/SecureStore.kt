package com.mrsiavash.elagent

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("el_agent_state", Context.MODE_PRIVATE)

    fun deviceId(): String {
        val current = prefs.getString("device_id", null)
        if (!current.isNullOrBlank()) return current
        val created = java.util.UUID.randomUUID().toString()
        prefs.edit().putString("device_id", created).apply()
        return created
    }

    fun saveCredentials(value: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toString().toByteArray(Charsets.UTF_8))
        val packed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        prefs.edit().putString("hub_credentials", packed).apply()
    }

    fun credentials(): JSONObject? {
        val packed = prefs.getString("hub_credentials", null) ?: return null
        return try {
            val parts = packed.split(":", limit = 2)
            require(parts.size == 2)
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            JSONObject(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
        } catch (error: Exception) {
            throw IllegalStateException("Encrypted Hub credentials could not be opened. Re-pair only after checking Hub-side device status.", error)
        }
    }

    fun clearCredentials() {
        prefs.edit().remove("hub_credentials").remove("pending_task").remove("pending_alias")
            .remove("pending_launched").apply()
    }

    fun savePendingTask(taskId: String, alias: String) {
        prefs.edit().putString("pending_task", taskId).putString("pending_alias", alias)
            .putBoolean("pending_launched", false).apply()
    }

    fun pendingTaskId(): String? = prefs.getString("pending_task", null)
    fun pendingAlias(): String? = prefs.getString("pending_alias", null)
    fun pendingWasLaunched(): Boolean = prefs.getBoolean("pending_launched", false)
    fun markPendingLaunched() { prefs.edit().putBoolean("pending_launched", true).apply() }
    fun clearPendingTask() {
        prefs.edit().remove("pending_task").remove("pending_alias").remove("pending_launched").apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val KEY_ALIAS = "mr_siavash_el_agent_hub_v1"
    }
}
