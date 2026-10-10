package dev.diligent.app.quotapulse.sources

import dev.diligent.app.quotapulse.data.MetricKind
import dev.diligent.app.quotapulse.data.Provider
import dev.diligent.app.quotapulse.data.SourceKind
import dev.diligent.app.quotapulse.data.UsageSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private val json = Json { ignoreUnknownKeys = true }

/** First day of the current month in UTC — both Admin APIs bucket by UTC day. */
internal fun monthStartUtc(now: Long): Instant =
    Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant()

private fun JsonElement?.long(): Long = (this as? kotlinx.serialization.json.JsonPrimitive)?.doubleOrNull?.toLong() ?: 0L
private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject
private fun JsonObject.str(name: String): String? = (this[name] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

/**
 * Organization API usage and spend from the Anthropic Usage & Cost Admin API
 * (https://platform.claude.com/docs/en/manage-claude/usage-cost-api).
 *
 * This is API billing for the organization — it is NOT the remaining allowance of a
 * Claude Pro/Max subscription and is labelled accordingly.
 */
class AnthropicAdminSource(private val adminKey: String) : UsageSource {

    override val kind = SourceKind.ANTHROPIC_ADMIN_API
    override val providers = setOf(Provider.CLAUDE)
    // Docs: polling once per minute is supported for sustained use.
    override val minLiveIntervalMs = 60_000L

    override suspend fun fetch(now: Long): SourceOutcome = withContext(Dispatchers.IO) {
        val start = monthStartUtc(now).toString()
        val headers = mapOf("x-api-key" to adminKey, "anthropic-version" to "2023-06-01")
        try {
            val usagePages = mutableListOf<String>()
            var page: String? = null
            for (attempt in 0 until MAX_PAGES) {
                val url = "$BASE/usage_report/messages?starting_at=$start&bucket_width=1d&limit=31" +
                    (page?.let { "&page=" + URLEncoder.encode(it, "UTF-8") } ?: "")
                val result = Http.get(url, headers)
                if (result.code != 200) return@withContext httpFailure(result, "Anthropic Admin API", now)
                usagePages += result.body
                page = nextPage(result.body) ?: break
            }

            val cost = Http.get("$BASE/cost_report?starting_at=$start&limit=31", headers)
            val costCents = if (cost.code == 200) parseCostCents(cost.body) else null

            val usage = parseUsage(usagePages, Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate())
            val snapshots = buildList {
                add(
                    UsageSnapshot(
                        provider = Provider.CLAUDE.name,
                        metricKey = "claude.api.tokens.month",
                        kind = MetricKind.API_TOKENS.name,
                        source = kind.name,
                        title = "API · MONTH (UTC)",
                        percentLeft = null,
                        amount = usage.monthTokens.toDouble(),
                        unit = "tokens",
                        detail = "today ${usage.todayTokens} tokens · org-wide API usage",
                        resetsAt = null,
                        measuredAt = now,
                        fetchedAt = now
                    )
                )
                if (costCents != null) {
                    add(
                        UsageSnapshot(
                            provider = Provider.CLAUDE.name,
                            metricKey = "claude.api.cost.month",
                            kind = MetricKind.API_COST.name,
                            source = kind.name,
                            title = "API · MONTH (UTC)",
                            percentLeft = null,
                            amount = costCents / 100.0,
                            unit = "usd",
                            detail = "org-wide API billing (excludes Priority Tier)",
                            resetsAt = null,
                            measuredAt = now,
                            fetchedAt = now
                        )
                    )
                }
            }
            val note = if (costCents == null) "Cost report unavailable (HTTP ${cost.code})" else null
            SourceOutcome.Success(mapOf(Provider.CLAUDE to ProviderReport(snapshots, message = note)), note)
        } catch (e: IOException) {
            networkFailure(e, "Anthropic Admin API")
        }
    }

    internal data class Usage(val monthTokens: Long, val todayTokens: Long)

    companion object {
        private const val BASE = "https://api.anthropic.com/v1/organizations"
        private const val MAX_PAGES = 3

        internal fun nextPage(body: String): String? {
            val root = json.parseToJsonElement(body).jsonObject
            val hasMore = root["has_more"]?.jsonPrimitive?.booleanOrNull == true
            return if (hasMore) root.str("next_page") else null
        }

        /** Sums every token field in the usage report buckets. */
        internal fun parseUsage(pages: List<String>, todayUtc: LocalDate): Usage {
            var month = 0L
            var today = 0L
            pages.forEach { body ->
                json.parseToJsonElement(body).jsonObject["data"]?.jsonArray?.forEach { bucketElement ->
                    val bucket = bucketElement.jsonObject
                    val bucketDay = bucket.str("starting_at")?.take(10)
                    val bucketTotal = bucket["results"]?.jsonArray?.sumOf { resultElement ->
                        val r = resultElement.jsonObject
                        val cacheCreation = r.obj("cache_creation")
                        r["uncached_input_tokens"].long() +
                            r["cache_read_input_tokens"].long() +
                            r["output_tokens"].long() +
                            (cacheCreation?.get("ephemeral_1h_input_tokens").long()) +
                            (cacheCreation?.get("ephemeral_5m_input_tokens").long())
                    } ?: 0L
                    month += bucketTotal
                    if (bucketDay == todayUtc.toString()) today += bucketTotal
                }
            }
            return Usage(month, today)
        }

        /** Cost report amounts are decimal strings in cents (e.g. "123.45" USD = $1.23). */
        internal fun parseCostCents(body: String): Double =
            json.parseToJsonElement(body).jsonObject["data"]?.jsonArray?.sumOf { bucket ->
                bucket.jsonObject["results"]?.jsonArray?.sumOf { result ->
                    result.jsonObject.str("amount")?.toDoubleOrNull() ?: 0.0
                } ?: 0.0
            } ?: 0.0
    }
}

/**
 * Organization API usage and spend from the OpenAI Usage and Costs APIs
 * (/v1/organization/usage/completions and /v1/organization/costs, admin key).
 *
 * This covers OpenAI API billing for the whole organization; it is NOT ChatGPT
 * subscription usage or Codex plan limits, which have no public API.
 */
class OpenAiAdminSource(private val adminKey: String) : UsageSource {

    override val kind = SourceKind.OPENAI_ADMIN_API
    override val providers = setOf(Provider.CODEX)
    override val minLiveIntervalMs = 60_000L

    override suspend fun fetch(now: Long): SourceOutcome = withContext(Dispatchers.IO) {
        val start = monthStartUtc(now).epochSecond
        val headers = mapOf("Authorization" to "Bearer $adminKey")
        try {
            val usagePages = mutableListOf<String>()
            var page: String? = null
            for (attempt in 0 until MAX_PAGES) {
                val url = "$BASE/usage/completions?start_time=$start&bucket_width=1d&limit=31" +
                    (page?.let { "&page=" + URLEncoder.encode(it, "UTF-8") } ?: "")
                val result = Http.get(url, headers)
                if (result.code != 200) return@withContext httpFailure(result, "OpenAI Admin API", now)
                usagePages += result.body
                page = nextPage(result.body) ?: break
            }

            val costs = Http.get("$BASE/costs?start_time=$start&bucket_width=1d&limit=31", headers)
            val costUsd = if (costs.code == 200) parseCostUsd(costs.body) else null
            val usage = parseUsage(usagePages)

            val snapshots = buildList {
                add(
                    UsageSnapshot(
                        provider = Provider.CODEX.name,
                        metricKey = "codex.api.tokens.month",
                        kind = MetricKind.API_TOKENS.name,
                        source = kind.name,
                        title = "OPENAI API · MONTH",
                        percentLeft = null,
                        amount = usage.tokens.toDouble(),
                        unit = "tokens",
                        detail = "${usage.requests} requests · org-wide API usage",
                        resetsAt = null,
                        measuredAt = now,
                        fetchedAt = now
                    )
                )
                if (costUsd != null) {
                    add(
                        UsageSnapshot(
                            provider = Provider.CODEX.name,
                            metricKey = "codex.api.cost.month",
                            kind = MetricKind.API_COST.name,
                            source = kind.name,
                            title = "OPENAI API · MONTH",
                            percentLeft = null,
                            amount = costUsd,
                            unit = "usd",
                            detail = "org-wide API billing",
                            resetsAt = null,
                            measuredAt = now,
                            fetchedAt = now
                        )
                    )
                }
            }
            val note = if (costUsd == null) "Costs unavailable (HTTP ${costs.code})" else null
            SourceOutcome.Success(mapOf(Provider.CODEX to ProviderReport(snapshots, message = note)), note)
        } catch (e: IOException) {
            networkFailure(e, "OpenAI Admin API")
        }
    }

    internal data class Usage(val tokens: Long, val requests: Long)

    companion object {
        private const val BASE = "https://api.openai.com/v1/organization"
        private const val MAX_PAGES = 3

        internal fun nextPage(body: String): String? {
            val root = json.parseToJsonElement(body).jsonObject
            val hasMore = root["has_more"]?.jsonPrimitive?.booleanOrNull
            val next = root.str("next_page")
            return if (hasMore == false) null else next
        }

        internal fun parseUsage(pages: List<String>): Usage {
            var tokens = 0L
            var requests = 0L
            pages.forEach { body ->
                json.parseToJsonElement(body).jsonObject["data"]?.jsonArray?.forEach { bucket ->
                    bucket.jsonObject["results"]?.jsonArray?.forEach { resultElement ->
                        val r = resultElement.jsonObject
                        tokens += r["input_tokens"].long() + r["output_tokens"].long()
                        requests += r["num_model_requests"].long()
                    }
                }
            }
            return Usage(tokens, requests)
        }

        /** Costs results carry amount.value in dollars. */
        internal fun parseCostUsd(body: String): Double =
            json.parseToJsonElement(body).jsonObject["data"]?.jsonArray?.sumOf { bucket ->
                bucket.jsonObject["results"]?.jsonArray?.sumOf { result ->
                    result.jsonObject.obj("amount")?.get("value")?.jsonPrimitive?.doubleOrNull ?: 0.0
                } ?: 0.0
            } ?: 0.0
    }
}
