package dev.diligent.app.widget.quotes

import kotlin.random.Random

/**
 * A single quote line shown on the widget ticker.
 */
data class Quote(val text: String, val author: String)

/**
 * A themed pack of quotes plus short hero phrases for the widget's ticker header.
 */
class QuotePreset(
    val id: String,
    val name: String,
    val description: String,
    val heroPhrases: List<String>,
    val quotes: List<Quote>
)

/**
 * Builds a preset whose quotes are all signed with the preset's own name.
 */
internal fun signedPreset(
    id: String,
    name: String,
    description: String,
    heroPhrases: List<String>,
    lines: List<String>
) = QuotePreset(
    id = id,
    name = name,
    description = description,
    heroPhrases = heroPhrases,
    quotes = lines.map { Quote(it, name) }
)

/**
 * Registry of every quote preset the widget can display.
 * The selected preset id is persisted in Settings.quotePreset.
 */
object QuotePresets {

    const val DEFAULT_ID = "wall_street"
    const val MIX_ID = "mix"

    /** Every real preset, in the order shown in Settings. */
    val all: List<QuotePreset> by lazy {
        listOf(
            WallStreetPreset,
            StreetWisdomPreset,
            BallKnowledgePreset,
            StoicMindPreset,
            IronDisciplinePreset,
            CodeMonkPreset,
            SamuraiCodePreset,
            ChessMindPreset,
            FounderModePreset,
            ZenFocusPreset,
            LateNightGrindPreset
        )
    }

    /** Virtual preset that shuffles every pack together. */
    private val mix: QuotePreset by lazy {
        QuotePreset(
            id = MIX_ID,
            name = "Mix",
            description = "Everything, shuffled together",
            heroPhrases = all.flatMap { it.heroPhrases },
            quotes = all.flatMap { it.quotes }
        )
    }

    /** Presets offered in the Settings picker (real packs followed by the mix). */
    val selectable: List<QuotePreset> by lazy { all + mix }

    fun byId(id: String): QuotePreset =
        if (id == MIX_ID) mix else all.firstOrNull { it.id == id } ?: WallStreetPreset

    /**
     * Picks random, non-repeating quotes and hero phrases from the preset.
     */
    fun pick(
        id: String,
        quoteCount: Int,
        heroCount: Int,
        random: Random = Random.Default
    ): Pair<List<String>, List<Quote>> {
        val preset = byId(id)
        return preset.heroPhrases.shuffled(random).take(heroCount) to
            preset.quotes.shuffled(random).take(quoteCount)
    }
}
