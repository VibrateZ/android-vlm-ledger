package com.vibratez.ledger.security

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class AppSettings(
    val cloudEnabled: Boolean = false,
    val baseUrl: String = "",
    val model: String = "",
    val timeoutSeconds: Int = 60,
    val apiKeyConfigured: Boolean = false,
)

/**
 * Stores non-sensitive settings separately from an API key encrypted with Android Keystore.
 */
class SecureSettings(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        cloudEnabled = preferences.getBoolean(KEY_CLOUD_ENABLED, false),
        baseUrl = preferences.getString(KEY_BASE_URL, "").orEmpty(),
        model = preferences.getString(KEY_MODEL, "").orEmpty(),
        timeoutSeconds = preferences.getInt(KEY_TIMEOUT_SECONDS, 60).coerceIn(10, 120),
        apiKeyConfigured = preferences.contains(KEY_API_KEY_CIPHERTEXT),
    )

    fun save(settings: AppSettings, apiKey: String?) {
        require(settings.timeoutSeconds in 10..120) { "timeout must be between 10 and 120 seconds" }
        val encrypted = apiKey?.takeUnless(String::isBlank)?.let(::encrypt)
        preferences.edit {
            putBoolean(KEY_CLOUD_ENABLED, settings.cloudEnabled)
            putString(KEY_BASE_URL, settings.baseUrl.trim().trimEnd('/'))
            putString(KEY_MODEL, settings.model.trim())
            putInt(KEY_TIMEOUT_SECONDS, settings.timeoutSeconds)
            if (encrypted != null) {
                putString(KEY_API_KEY_CIPHERTEXT, encrypted.ciphertext)
                putString(KEY_API_KEY_IV, encrypted.iv)
            }
        }
    }

    fun readApiKey(): String? {
        val ciphertext = preferences.getString(KEY_API_KEY_CIPHERTEXT, null) ?: return null
        val iv = preferences.getString(KEY_API_KEY_IV, null) ?: return null
        return runCatching { decrypt(ciphertext, iv) }.getOrNull()
    }

    fun clearApiKey() {
        preferences.edit {
            remove(KEY_API_KEY_CIPHERTEXT)
            remove(KEY_API_KEY_IV)
        }
    }

    /** Returns a stable random SQLCipher passphrase protected by a separate Keystore key. */
    @SuppressLint("ApplySharedPref")
    @Synchronized
    fun databasePassphrase(): ByteArray {
        val stored = preferences.getString(KEY_DATABASE_PASSPHRASE, null)
        if (stored != null) {
            return decryptBytes(stored, DB_KEY_ALIAS)
        }
        val passphrase = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val persisted = preferences.edit()
            .putString(KEY_DATABASE_PASSPHRASE, encryptBytes(passphrase, DB_KEY_ALIAS))
            .commit()
        check(persisted) { "Unable to persist the encrypted database passphrase" }
        return passphrase
    }

    private data class EncryptedValue(val ciphertext: String, val iv: String)

    private fun encrypt(value: String): EncryptedValue {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(API_KEY_ALIAS))
        val ciphertext = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return EncryptedValue(
            ciphertext = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
            iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
        )
    }

    private fun decrypt(ciphertext: String, iv: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(API_KEY_ALIAS),
            GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
        )
        return cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP))
            .toString(StandardCharsets.UTF_8)
    }

    private fun encryptBytes(value: ByteArray, alias: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(alias))
        val encrypted = cipher.doFinal(value)
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    private fun decryptBytes(value: String, alias: String): ByteArray {
        val parts = value.split(":", limit = 2)
        require(parts.size == 2)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(alias),
            GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)),
        )
        return cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP))
    }

    private fun getOrCreateKey(alias: String): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance("AES", ANDROID_KEYSTORE)
        generator.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                alias,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFERENCES = "ledger_settings"
        const val API_KEY_ALIAS = "ledger_api_key_aes"
        const val DB_KEY_ALIAS = "ledger_db_key_aes"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_CLOUD_ENABLED = "cloud_enabled"
        const val KEY_BASE_URL = "base_url"
        const val KEY_MODEL = "model"
        const val KEY_TIMEOUT_SECONDS = "timeout_seconds"
        const val KEY_API_KEY_CIPHERTEXT = "api_key_ciphertext"
        const val KEY_API_KEY_IV = "api_key_iv"
        const val KEY_DATABASE_PASSPHRASE = "database_passphrase"
    }
}
