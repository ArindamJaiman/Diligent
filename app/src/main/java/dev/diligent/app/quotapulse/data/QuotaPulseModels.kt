package dev.diligent.app.quotapulse.data

import androidx.room.Entity

/**
 * The four AI tools QuotaPulse tracks.
 */
enum class Provider(val label: String) {
    CLAUDE("CLAUDE"),
    GEMINI("GEMINI"),
    ANTIGRAVITY("ANTIGRAVITY"),
    CODEX("CODEX");

    companion object {
        fun from(value: String?): Provider? = entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

/**
 * What a number actually measures. These are different measurements and are
 * never presented as interchangeable (e.g. API billing is not a plan allowance).
 */
enum class MetricKind(val label: String) {
    /** % left in a subscription rate-limit window reported by the provider's own client. */
    SUBSCRIPTION_QUOTA("Subscription quota"),

    /** Whether a subscription limit is currently reached (no percentage available). */
    LIMIT_STATUS("Plan limit status"),

    /** Token totals from local CLI session logs. */
    SESSION_TOKENS("Session tokens"),

    /** Organization API token usage from an Admin API. */
    API_TOKENS("API usage"),

    /** Organization API spend from an Admin API. */
    API_COST("API billing"),

    /** Remaining allowance typed in by the user from a provider's usage panel. */
    MANUAL_ALLOWANCE("Remaining allowance (manual)");

    companion object {
        fun from(value: String?): MetricKind? = entries.firstOrNull { it.name == value }
    }
}

/**
 * Where a snapshot came from.
 */
enum class SourceKind(val label: String) {
    DESKTOP_CONNECTOR("Desktop connector"),
    ANTHROPIC_ADMIN_API("Anthropic Admin API"),
    OPENAI_ADMIN_API("OpenAI Admin API"),
    MANUAL("Manual entry");

    companion object {
        fun from(value: String?): SourceKind? = entries.firstOrNull { it.name == value }
    }
}

/**
 * Outcome of the last attempt to read one source for one provider.
 */
enum class SourceState {
    OK,
    AUTH_REQUIRED,
    RATE_LIMITED,
    UNAVAILABLE,
    OFFLINE,
    ERROR;

    companion object {
        fun from(value: String?): SourceState = entries.firstOrNull { it.name == value } ?: ERROR
    }
}

/**
 * One normalized usage measurement. Every snapshot carries its provider, what it
 * measures, its source, and when it was measured.
 *
 * @param metricKey stable id within the provider, e.g. "codex.primary"
 * @param percentLeft only for genuine quota metrics; never used to encode "unknown"
 * @param amount numeric value for token/cost metrics
 * @param unit "tokens" or "usd"
 * @param title short window label, e.g. "5-HOUR", "TODAY", "MONTH TO DATE"
 * @param detail extra context, e.g. plan type or limit status
 * @param measuredAt when the source measured it (epoch millis)
 * @param fetchedAt when the phone stored it (epoch millis)
 */
@Entity(tableName = "usage_snapshots", primaryKeys = ["provider", "metricKey"])
data class UsageSnapshot(
    val provider: String,
    val metricKey: String,
    val kind: String,
    val source: String,
    val title: String,
    val percentLeft: Double?,
    val amount: Double?,
    val unit: String?,
    val detail: String?,
    val resetsAt: Long?,
    val measuredAt: Long,
    val fetchedAt: Long
) {
    val providerEnum: Provider? get() = Provider.from(provider)
    val kindEnum: MetricKind? get() = MetricKind.from(kind)
    val sourceEnum: SourceKind? get() = SourceKind.from(source)
}

/**
 * Last read attempt for a (provider, source) pair, kept so failures can be shown
 * truthfully while previous valid snapshots stay visible.
 */
@Entity(tableName = "usage_source_status", primaryKeys = ["provider", "source"])
data class UsageSourceStatus(
    val provider: String,
    val source: String,
    val state: String,
    val message: String?,
    val lastAttemptAt: Long,
    val lastSuccessAt: Long,
    val retryAfter: Long
) {
    val stateEnum: SourceState get() = SourceState.from(state)
}
