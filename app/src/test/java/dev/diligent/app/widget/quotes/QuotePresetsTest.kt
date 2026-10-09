package dev.diligent.app.widget.quotes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class QuotePresetsTest {

    private val themedPacks = QuotePresets.all.filter { it.id != QuotePresets.DEFAULT_ID }

    @Test
    fun `ten themed packs ship alongside wall street`() {
        assertEquals(10, themedPacks.size)
        assertEquals(QuotePresets.DEFAULT_ID, QuotePresets.all.first().id)
    }

    @Test
    fun `every themed pack has exactly 100 unique quotes`() {
        themedPacks.forEach { preset ->
            assertEquals("${preset.name} size", 100, preset.quotes.size)
            assertEquals("${preset.name} duplicates", 100, preset.quotes.map { it.text }.toSet().size)
        }
    }

    @Test
    fun `quotes are non-blank and fit the four-line widget slot`() {
        QuotePresets.all.flatMap { it.quotes }.forEach { quote ->
            assertTrue("blank quote", quote.text.isNotBlank())
            assertTrue("too long: ${quote.text}", quote.text.length <= 110)
            assertTrue("missing author", quote.author.isNotBlank())
        }
    }

    @Test
    fun `hero phrases are short uppercase ticker lines`() {
        QuotePresets.all.forEach { preset ->
            assertTrue(preset.heroPhrases.size >= 4)
            preset.heroPhrases.forEach { phrase ->
                assertEquals(phrase.uppercase(), phrase)
                assertTrue("too long: $phrase", phrase.length <= 26)
            }
        }
    }

    @Test
    fun `preset ids are unique and resolve`() {
        val ids = QuotePresets.selectable.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        ids.forEach { assertEquals(it, QuotePresets.byId(it).id) }
        assertEquals(QuotePresets.DEFAULT_ID, QuotePresets.byId("does-not-exist").id)
    }

    @Test
    fun `mix contains every quote`() {
        val mix = QuotePresets.byId(QuotePresets.MIX_ID)
        assertEquals(QuotePresets.all.sumOf { it.quotes.size }, mix.quotes.size)
    }

    @Test
    fun `pick returns distinct random quotes from the selected pack`() {
        val (heroes, quotes) = QuotePresets.pick("street_wisdom", quoteCount = 5, heroCount = 4, random = Random(7))
        assertEquals(4, heroes.size)
        assertEquals(5, quotes.size)
        assertEquals(5, quotes.toSet().size)
        assertTrue(quotes.all { it.author == "Street Wisdom" })
    }

    @Test
    fun `same seed gives the same selection`() {
        val a = QuotePresets.pick("ball_knowledge", 5, 4, Random(42))
        val b = QuotePresets.pick("ball_knowledge", 5, 4, Random(42))
        assertEquals(a, b)
    }
}
