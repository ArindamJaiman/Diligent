package dev.diligent.app.insights

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import dev.diligent.app.data.repository.DiligentRepository
import dev.diligent.app.focus.domain.FocusStats
import dev.diligent.app.quotapulse.data.SecretStore
import dev.diligent.app.tracking.DayClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * The weekly coach letter (Rize's summaries, Rosebud's reflections): a short, specific read of
 * the last two weeks, written by Claude with *your* Anthropic API key. Only a numeric summary
 * is sent (daily completion, hours, sleep/steps/mood, focus hours by time of day, per-activity
 * totals and the patterns Insights found); activity names go too, notes never do.
 */
@Singleton
class AiCoach @Inject constructor(
    private val insights: InsightsService,
    private val repository: DiligentRepository,
    private val secrets: SecretStore
) {
    fun hasKey(): Boolean = secrets.has(KEY)

    fun setKey(key: String?) = secrets.put(KEY, key?.trim())

    /** The summary sent to Claude, also shown to the user before sending. */
    suspend fun summary(): JsonObject {
        val today = DayClock.today()
        val days = insights.facts(today.minusDays(13), today)
        val readiness = insights.readiness()
        val (correlations, companions) = insights.correlations()
        val compare = insights.compare(today.minusDays(6), today)
        val records = repository.getFocusRecords(DayClock.startOf(today.minusDays(13)), System.currentTimeMillis())
        val byHour = FocusStats.minutesByHour(records, ZoneId.systemDefault())
        val focus = FocusStats.summarize(records)
        return buildJsonObject {
            put("today", today.toString())
            putJsonArray("days") {
                days.forEach { d ->
                    add(buildJsonObject {
                        put("date", d.date.toString())
                        d.completion?.let { put("goals_hit_pct", (it * 100).roundToInt()) }
                        put("hours", (d.hours * 10).roundToInt() / 10.0)
                        d.sleepHours?.let { put("sleep_h", (it * 10).roundToInt() / 10.0) }
                        d.steps?.let { put("steps", it) }
                        d.mood?.let { put("mood_1_5", it) }
                        d.energy?.let { put("energy_1_5", it) }
                        d.distractingMinutes?.let { put("scroll_min", it) }
                        put("focus_sessions", d.focusSessions)
                    })
                }
            }
            putJsonArray("this_week_vs_last") {
                compare.forEach { c ->
                    add(buildJsonObject {
                        put("activity", c.name)
                        put("unit", c.unit)
                        put("this_week", (c.current * 10).roundToInt() / 10.0)
                        put("last_week", (c.previous * 10).roundToInt() / 10.0)
                    })
                }
            }
            putJsonObject("focus_14d") {
                put("sessions", focus.sessions)
                put("minutes", focus.minutes)
                put("avg_minutes", focus.averageMinutes)
                put("stake_held_pct", (focus.intactShare * 100).roundToInt())
                put("avg_rating_1_5", (focus.averageRating * 10).roundToInt() / 10.0)
                put("minutes_by_hour", buildJsonArray { byHour.forEach { add(JsonPrimitive(it)) } })
            }
            putJsonObject("readiness") {
                put("score", readiness.score)
                put("load_vs_usual_pct", (readiness.load * 100).roundToInt())
                put("burnout_risk", readiness.burnoutRisk)
            }
            putJsonArray("patterns") { (correlations.map { it.sentence() } + companions).forEach { add(JsonPrimitive(it)) } }
        }
    }

    /** Writes the letter; the error message on failure. */
    suspend fun weeklyLetter(summary: JsonObject): Result<String> = withContext(Dispatchers.IO) {
        val key = secrets.get(KEY) ?: return@withContext Result.failure(IllegalStateException("Add your Anthropic API key first"))
        runCatching {
            val client = AnthropicOkHttpClient.builder().apiKey(key).build()
            try {
                val params = MessageCreateParams.builder()
                    .model(MODEL)
                    .maxTokens(16000L)
                    // A declined request is retried server-side on the model Anthropic recommends for it.
                    .addBeta("server-side-fallback-2026-07-01")
                    .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                    .system(SYSTEM_PROMPT)
                    .addUserMessage("Here is my last two weeks as JSON:\n$summary")
                    .build()
                val response = client.beta().messages().create(params)
                if (response.stopReason().orElse(null) == BetaStopReason.REFUSAL) {
                    error("Claude declined to write this one; try again later")
                }
                response.content().mapNotNull { block -> block.text().orElse(null)?.text() }
                    .joinToString("\n")
                    .trim()
                    .ifEmpty { error("Empty reply") }
            } finally {
                client.close()
            }
        }
    }

    companion object {
        private const val KEY = "anthropic_api_key"
        const val MODEL = "claude-opus-5-5"

        private val SYSTEM_PROMPT = """
            You are a warm, direct productivity coach writing a short weekly letter to someone who
            tracks their deep work, habits, sleep and focus in an app called Diligent.
            From the JSON summary, write about 150-220 words in plain text (no markdown headings):
            open with one specific win, name the one or two patterns that matter most (cite the
            numbers), and end with exactly three small, concrete experiments for next week, each on
            its own line starting with "→". If the readiness data shows burnout risk, put rest first.
            Don't invent data that isn't in the summary, and don't lecture.
        """.trimIndent()
    }
}
