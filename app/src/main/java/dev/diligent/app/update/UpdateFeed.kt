package dev.diligent.app.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest

/**
 * What CI publishes next to each release APK as update.json (see .github/workflows/release.yml).
 *
 * @param versionCode the number of commits on main, so every new commit is a higher version
 * @param sha256 hex digest of the APK, checked before it is handed to the installer
 */
@Serializable
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val commit: String = "",
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long = 0,
    val notes: String = "",
    val publishedAt: String = ""
) {
    val shortCommit: String get() = commit.take(7)
}

/** Pure rules for the update check, so they are unit tested. */
object UpdateFeed {

    /** Opening the app checks at most this often; nothing checks in the background. */
    const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L

    /** "Later" hides the prompt for this version for a day; Settings can still install it. */
    const val SNOOZE_MS = 24 * 60 * 60 * 1000L

    /** The release APK and its redirect come from GitHub; anything else is refused. */
    private val TRUSTED_HOSTS = setOf("github.com")

    private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

    private val json = Json { ignoreUnknownKeys = true }

    /** Parses update.json; null when it is malformed or points somewhere it shouldn't. */
    fun parse(body: String): UpdateInfo? {
        val info = try {
            json.decodeFromString(UpdateInfo.serializer(), body)
        } catch (e: Exception) {
            return null
        }
        return info.copy(sha256 = info.sha256.lowercase()).takeIf { isValid(it) }
    }

    fun isValid(info: UpdateInfo): Boolean =
        info.versionCode > 0 && info.versionName.isNotBlank() && isTrustedUrl(info.apkUrl) && SHA256_HEX.matches(info.sha256)

    fun isTrustedUrl(url: String): Boolean {
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            return false
        }
        return uri.scheme == "https" && uri.host in TRUSTED_HOSTS && uri.path.orEmpty().endsWith(".apk")
    }

    fun isNewer(info: UpdateInfo, installedVersionCode: Int): Boolean = info.versionCode > installedVersionCode

    /** True when opening the app should check again (also after the clock jumps backwards). */
    fun autoCheckDue(lastCheckAt: Long, now: Long): Boolean =
        lastCheckAt <= 0L || now < lastCheckAt || now - lastCheckAt >= CHECK_EVERY_MS

    /** Whether to ask "Update found" for [info] after an automatic check. */
    fun shouldPrompt(info: UpdateInfo, installedVersionCode: Int, snoozedVersion: Int, snoozedUntil: Long, now: Long): Boolean =
        isNewer(info, installedVersionCode) && !(snoozedVersion == info.versionCode && now < snoozedUntil)

    fun sha256Hex(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return toHex(digest.digest())
    }

    fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
