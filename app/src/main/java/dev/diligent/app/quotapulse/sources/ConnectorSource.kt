package dev.diligent.app.quotapulse.sources

import dev.diligent.app.quotapulse.data.MetricKind
import dev.diligent.app.quotapulse.data.Provider
import dev.diligent.app.quotapulse.data.QuotaPulsePrefs
import dev.diligent.app.quotapulse.data.SourceKind
import dev.diligent.app.quotapulse.data.SourceState
import dev.diligent.app.quotapulse.data.UsageSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pairing data for the desktop connector: a secret GitHub gist that relays an
 * AES-256-GCM encrypted usage snapshot. The gist id alone reveals nothing; only this
 * phone (holding [key]) can decrypt the payload.
 *
 * Pairing code format: `QP1.<gistId>.<base64url 32-byte key>`
 */
data class ConnectorPairing(val gistId: String, val key: ByteArray) {
    companion object {
        private val GIST_ID = Regex("^[0-9a-fA-F]{20,64}$")

        fun parse(code: String): ConnectorPairing? {
            val parts = code.trim().split('.')
            if (parts.size != 3 || parts[0] != "QP1" || !GIST_ID.matches(parts[1])) return null
            val key = try {
                Base64.getUrlDecoder().decode(parts[2])
            } catch (e: IllegalArgumentException) {
                return null
            }
            return if (key.size == 32) ConnectorPairing(parts[1], key) else null
        }
    }
}

/** Decrypts `QP1:<base64url(iv | ciphertext | tag)>` produced by the desktop connector. */
object ConnectorCrypto {
    private const val PREFIX = "QP1:"
    private val AAD = "quotapulse-v1".toByteArray(Charsets.UTF_8)

    fun decrypt(envelope: String, key: ByteArray): String {
        require(envelope.startsWith(PREFIX)) { "Unknown payload format" }
        val bytes = Base64.getUrlDecoder().decode(envelope.removePrefix(PREFIX).trim())
        require(bytes.size > 12 + 16) { "Payload too short" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, bytes, 0, 12))
        cipher.updateAAD(AAD)
        return String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
    }
}

@Serializable
internal data class ConnectorPayload(
    val v: Int,
    val host: String? = null,
    val collectedAt: Long,
    val providers: List<ConnectorProvider> = emptyList()
)

@Serializable
internal data class ConnectorProvider(
    val provider: String,
    val status: String = "OK",
    val message: String? = null,
    val metrics: List<ConnectorMetric> = emptyList()
)

@Serializable
internal data class ConnectorMetric(
    val key: String,
    val kind: String,
    val title: String,
    val percentLeft: Double? = null,
    val amount: Double? = null,
    val unit: String? = null,
    val detail: String? = null,
    val resetsAt: Long? = null,
    val measuredAt: Long
)

/**
 * Reads the encrypted snapshot the desktop connector publishes. Polled only on
 * manual sync or in live mode; uses ETags so unchanged data costs a tiny 304.
 */
class ConnectorSource(
    private val pairing: ConnectorPairing,
    private val prefs: QuotaPulsePrefs
) : UsageSource {

    override val kind = SourceKind.DESKTOP_CONNECTOR
    override val providers = SUPPORTED_PROVIDERS
    // Unauthenticated GitHub API reads are limited to 60/hour per IP.
    override val minLiveIntervalMs = 120_000L

    override suspend fun fetch(now: Long): SourceOutcome = withContext(Dispatchers.IO) {
        val headers = buildMap {
            put("Accept", "application/vnd.github+json")
            put("X-GitHub-Api-Version", "2022-11-28")
            prefs.connectorEtag?.let { put("If-None-Match", it) }
        }
        try {
            val result = Http.get("https://api.github.com/gists/${pairing.gistId}", headers)
            when {
                result.code == 304 -> SourceOutcome.NotModified
                result.code == 404 -> SourceOutcome.Failure(
                    SourceState.ERROR, "Connector gist not found — pair the connector again"
                )
                result.code == 403 || result.code == 429 -> {
                    val remaining = result.header("X-RateLimit-Remaining")
                    val reset = result.header("X-RateLimit-Reset")?.toLongOrNull()
                    if (remaining == "0" || result.code == 429) {
                        val wait = reset?.let { it * 1000 - now } ?: parseRetryAfter(result.header("Retry-After"), now)
                        SourceOutcome.Failure(
                            SourceState.RATE_LIMITED,
                            "GitHub rate limit reached",
                            retryAfterMs = (wait ?: 600_000L).coerceIn(60_000L, 3_600_000L)
                        )
                    } else {
                        httpFailure(result, "GitHub", now)
                    }
                }
                result.code != 200 -> httpFailure(result, "GitHub", now)
                else -> {
                    val outcome = decode(result.body, pairing.key, now)
                    if (outcome is SourceOutcome.Success) prefs.connectorEtag = result.header("ETag")
                    outcome
                }
            }
        } catch (e: IOException) {
            networkFailure(e, "GitHub")
        }
    }

    companion object {
        const val FILE_NAME = "quotapulse.enc"
        val SUPPORTED_PROVIDERS = setOf(Provider.CLAUDE, Provider.CODEX)

        /** Metric kinds a connector may report; API billing never comes from it. */
        private val ALLOWED_KINDS = setOf(MetricKind.SUBSCRIPTION_QUOTA, MetricKind.LIMIT_STATUS, MetricKind.SESSION_TOKENS)
        private const val MAX_CLOCK_SKEW_MS = 5 * 60_000L

        private val json = Json { ignoreUnknownKeys = true }

        /** Parses the gist response, decrypts it, and validates every field. */
        internal fun decode(gistBody: String, key: ByteArray, now: Long): SourceOutcome {
            val envelope = json.parseToJsonElement(gistBody).jsonObject["files"]?.jsonObject
                ?.get(FILE_NAME)?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
                ?: return SourceOutcome.Failure(SourceState.ERROR, "Connector gist has no $FILE_NAME file")

            val plaintext = try {
                ConnectorCrypto.decrypt(envelope, key)
            } catch (e: Exception) {
                return SourceOutcome.Failure(SourceState.AUTH_REQUIRED, "Can't decrypt connector data — pair again")
            }
            return parsePayload(plaintext, now)
        }

        internal fun parsePayload(plaintext: String, now: Long): SourceOutcome {
            val payload = try {
                json.decodeFromString<ConnectorPayload>(plaintext)
            } catch (e: Exception) {
                return SourceOutcome.Failure(SourceState.ERROR, "Connector sent unreadable data")
            }
            if (payload.v != 1) {
                return SourceOutcome.Failure(SourceState.ERROR, "Connector version ${payload.v} not supported — update the app")
            }

            val reports = mutableMapOf<Provider, ProviderReport>()
            payload.providers.forEach { entry ->
                val provider = Provider.from(entry.provider)?.takeIf { it in SUPPORTED_PROVIDERS } ?: return@forEach
                val prefix = provider.name.lowercase() + "."
                val snapshots = entry.metrics.mapNotNull { m ->
                    val kind = MetricKind.from(m.kind)?.takeIf { it in ALLOWED_KINDS } ?: return@mapNotNull null
                    if (!m.key.startsWith(prefix) || m.key.length > 64) return@mapNotNull null
                    UsageSnapshot(
                        provider = provider.name,
                        metricKey = m.key,
                        kind = kind.name,
                        source = SourceKind.DESKTOP_CONNECTOR.name,
                        title = m.title.take(24).uppercase(),
                        percentLeft = m.percentLeft?.takeIf { kind == MetricKind.SUBSCRIPTION_QUOTA && !it.isNaN() }
                            ?.coerceIn(0.0, 100.0),
                        amount = m.amount?.takeIf { it.isFinite() && it >= 0 },
                        unit = m.unit?.take(12),
                        detail = m.detail?.take(80),
                        resetsAt = m.resetsAt,
                        measuredAt = m.measuredAt.coerceAtMost(now + MAX_CLOCK_SKEW_MS),
                        fetchedAt = now
                    )
                }
                val state = if (entry.status.equals("OK", ignoreCase = true)) SourceState.OK else SourceState.UNAVAILABLE
                reports[provider] = ProviderReport(snapshots, state, entry.message?.take(120))
            }
            val host = payload.host?.take(40)
            return SourceOutcome.Success(reports, note = host)
        }
    }
}
