package dev.diligent.app.quotapulse.sources

import dev.diligent.app.quotapulse.data.Provider
import dev.diligent.app.quotapulse.data.SourceKind
import dev.diligent.app.quotapulse.data.SourceState
import dev.diligent.app.quotapulse.data.UsageSnapshot
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * A place usage numbers can be read from. Each source is fetched independently so one
 * failure never blocks the others.
 */
interface UsageSource {
    val kind: SourceKind
    val providers: Set<Provider>

    /** Shortest gap between automatic (live) requests, to respect provider rate limits. */
    val minLiveIntervalMs: Long

    suspend fun fetch(now: Long): SourceOutcome
}

/** What one source reported for one provider. */
data class ProviderReport(
    val snapshots: List<UsageSnapshot>,
    val state: SourceState = SourceState.OK,
    val message: String? = null
)

sealed class SourceOutcome {
    data class Success(val reports: Map<Provider, ProviderReport>, val note: String? = null) : SourceOutcome()

    /** Data unchanged since the last fetch (HTTP 304); previous snapshots stay valid. */
    data object NotModified : SourceOutcome()

    data class Failure(
        val state: SourceState,
        val message: String,
        val retryAfterMs: Long = 0L
    ) : SourceOutcome()
}

internal class HttpResult(val code: Int, val body: String, private val headers: Map<String?, List<String>>) {
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
}

/**
 * Minimal HTTPS GET with timeouts. Request headers (which carry credentials) are never logged.
 */
internal object Http {
    private const val MAX_BODY_CHARS = 2_000_000
    const val USER_AGENT = "Diligent-QuotaPulse/1.0 (Android)"

    fun get(url: String, headers: Map<String, String>, timeoutMs: Int = 15_000): HttpResult {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { reader ->
                val buffer = CharArray(8192)
                val out = StringBuilder()
                while (out.length < MAX_BODY_CHARS) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    out.append(buffer, 0, read)
                }
                out.toString()
            } ?: ""
            return HttpResult(code, body, connection.headerFields)
        } finally {
            connection.disconnect()
        }
    }
}

/** Maps a non-success HTTP response to a truthful failure state. */
internal fun httpFailure(result: HttpResult, what: String, now: Long): SourceOutcome.Failure = when (result.code) {
    401, 403 -> SourceOutcome.Failure(SourceState.AUTH_REQUIRED, "$what rejected the key (HTTP ${result.code})")
    429 -> SourceOutcome.Failure(
        SourceState.RATE_LIMITED,
        "$what rate limit reached",
        retryAfterMs = parseRetryAfter(result.header("Retry-After"), now) ?: 60_000L
    )
    in 500..599 -> SourceOutcome.Failure(SourceState.ERROR, "$what server error (HTTP ${result.code})")
    else -> SourceOutcome.Failure(SourceState.ERROR, "$what returned HTTP ${result.code}")
}

internal fun networkFailure(e: IOException, what: String): SourceOutcome.Failure = when (e) {
    is UnknownHostException -> SourceOutcome.Failure(SourceState.OFFLINE, "No connection to $what")
    is SocketTimeoutException -> SourceOutcome.Failure(SourceState.ERROR, "$what timed out")
    else -> SourceOutcome.Failure(SourceState.OFFLINE, "Network error reaching $what")
}

/** Retry-After is either delay-seconds or an HTTP date. */
internal fun parseRetryAfter(value: String?, now: Long): Long? {
    if (value.isNullOrBlank()) return null
    value.trim().toLongOrNull()?.let { return (it * 1000).coerceIn(1_000L, 3_600_000L) }
    return try {
        val at = ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        (at - now).coerceIn(1_000L, 3_600_000L)
    } catch (e: Exception) {
        null
    }
}
