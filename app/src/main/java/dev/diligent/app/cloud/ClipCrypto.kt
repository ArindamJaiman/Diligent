package dev.diligent.app.cloud

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end encryption for your own clips: AES-256-GCM with a key derived from the
 * clipboard passphrase (PBKDF2-SHA256, 100,000 rounds). Stored as "enc1:" + base64(iv ‖
 * ciphertext ‖ tag), byte for byte what the web app's WebCrypto code writes, so either side
 * reads the other's clips. Text without the prefix is a plain clip and passes through.
 *
 * java.util.Base64 (API 26+, the min SDK) keeps this testable on the JVM.
 */
object ClipCrypto {
    const val PREFIX = "enc1:"
    private const val SALT = "diligent-clips-v1"
    private const val ROUNDS = 100_000
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private val keys = ConcurrentHashMap<String, SecretKeySpec>()
    private val random = SecureRandom()

    fun isEncrypted(stored: String): Boolean = stored.startsWith(PREFIX)

    private fun key(passphrase: String): SecretKeySpec = keys.getOrPut(passphrase) {
        val spec = PBEKeySpec(passphrase.toCharArray(), SALT.toByteArray(Charsets.UTF_8), ROUNDS, 256)
        SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
    }

    fun encrypt(plain: String, passphrase: String): String {
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(passphrase), GCMParameterSpec(TAG_BITS, iv))
        return PREFIX + Base64.getEncoder().encodeToString(iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }

    /** The plain text; plain clips pass through; null when there's no passphrase or it's the wrong one. */
    fun decrypt(stored: String, passphrase: String?): String? {
        if (!isEncrypted(stored)) return stored
        if (passphrase.isNullOrEmpty()) return null
        return runCatching {
            val raw = Base64.getDecoder().decode(stored.substring(PREFIX.length))
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(passphrase), GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES))
            String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
        }.getOrNull()
    }
}
