package dev.diligent.app.quotapulse.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores provider credentials encrypted with an AES-256-GCM key that lives in the
 * Android Keystore (never exportable). Values are never logged and never placed in
 * widget state, intents, or deep links.
 *
 * If the Keystore key is lost (e.g. data restored onto another device), stored values
 * can't be decrypted; they are dropped and the provider shows AUTH REQUIRED.
 */
@Singleton
class SecretStore @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun has(name: String): Boolean = prefs.contains(name)

    fun get(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_SIZE))
            String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE), Charsets.UTF_8)
        } catch (e: Exception) {
            prefs.edit().remove(name).apply()
            null
        }
    }

    fun put(name: String, value: String?) {
        if (value.isNullOrBlank()) {
            prefs.edit().remove(name).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    fun remove(vararg names: String) {
        prefs.edit().apply { names.forEach { remove(it) } }.apply()
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        const val ANTHROPIC_ADMIN_KEY = "anthropic_admin_key"
        const val OPENAI_ADMIN_KEY = "openai_admin_key"
        const val CONNECTOR_GIST_ID = "connector_gist_id"
        const val CONNECTOR_KEY = "connector_key"

        private const val PREFS_NAME = "quotapulse_secrets"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "quotapulse_secrets_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
    }
}
