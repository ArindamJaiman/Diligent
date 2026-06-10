package dev.diligent.app.widget

/**
 * Curated collection of Wall Street, trading, and hustle quotes.
 * Rotates based on the current minute for a dynamic ticker-tape feel.
 *
 * Also provides a hero ticker string — a continuous scrolling marquee
 * of short punchy phrases for the widget's animated header.
 */
object WallStreetQuotes {

    private val quotes = listOf(
        // ─── Scarface ───────────────────────────────────
        "THE WORLD IS YOURS." to "Tony Montana",
        "All I have in this world is my balls and my word." to "Tony Montana",
        "Every day above ground is a good day." to "Scarface",

        // ─── Wall Street (Film) ─────────────────────────
        "Money never sleeps." to "Gordon Gekko",
        "Greed, for lack of a better word, is good." to "Gordon Gekko",
        "If you need a friend, get a dog." to "Gordon Gekko",
        "The most valuable commodity I know of is information." to "Gordon Gekko",

        // ─── Warren Buffett ─────────────────────────────
        "Be fearful when others are greedy, and greedy when others are fearful." to "Buffett",
        "Risk comes from not knowing what you're doing." to "Buffett",
        "Price is what you pay. Value is what you get." to "Buffett",
        "The stock market is a device for transferring money from the impatient to the patient." to "Buffett",
        "Someone's sitting in the shade today because someone planted a tree a long time ago." to "Buffett",

        // ─── Trading Wisdom ─────────────────────────────
        "The trend is your friend until the end." to "Wall Street",
        "Buy the rumor, sell the news." to "Wall Street",
        "Bulls make money. Bears make money. Pigs get slaughtered." to "Wall Street",
        "Cut your losses short, let your winners run." to "Wall Street",
        "Buy when there's blood in the streets." to "Rothschild",
        "The market can stay irrational longer than you can stay solvent." to "Keynes",
        "Compound interest is the eighth wonder of the world." to "Einstein",

        // ─── Hustle & Grind ─────────────────────────────
        "Fortune favors the bold." to "Virgil",
        "Stay hungry. Stay foolish." to "Steve Jobs",
        "Every morning I check the Forbes list. If I'm not on it, I go to work." to "Vinnie",
        "It's not about how much you make, it's how much you keep." to "Kiyosaki",
        "The four most dangerous words: 'This time it's different.'" to "Templeton",
        "In investing, what is comfortable is rarely profitable." to "Dalio",
        "I'd rather hustle 24/7 than slave 9 to 5." to "Unknown",
        "Don't watch the clock. Do what it does. Keep going." to "Sam Levenson",
        "Winners are not afraid of losing. Losers are." to "Kiyosaki",
        "The harder you work, the luckier you get." to "Gary Player"
    )

    /**
     * Short punchy phrases for the hero ticker-tape marquee.
     * These scroll continuously across the top of the widget.
     */
    private val heroSnippets = listOf(
        "◆ THE WORLD IS YOURS ◆",
        "MONEY NEVER SLEEPS",
        "STAY HUNGRY",
        "GREED IS GOOD",
        "FORTUNE FAVORS THE BOLD",
        "BULLS MAKE MONEY",
        "BUY THE DIP",
        "COMPOUND & CONQUER",
        "RISK IT ALL",
        "DISCIPLINE = FREEDOM",
        "EXECUTE DAILY",
        "NO DAYS OFF",
        "STACK & BUILD",
        "PATIENCE PAYS",
        "TRUST THE PROCESS"
    )

    /**
     * Returns a quote based on the current minute.
     * Changes automatically every 60 seconds.
     */
    fun getQuoteForMinute(): Pair<String, String> {
        val minuteIndex = ((System.currentTimeMillis() / 60_000) % quotes.size).toInt()
        return quotes[minuteIndex]
    }

    /**
     * Returns a rotating hero phrase — changes every 30 seconds.
     * Short punchy Wall Street catchphrases for the widget header.
     */
    fun getHeroPhrase(): String {
        val index = ((System.currentTimeMillis() / 30_000) % heroSnippets.size).toInt()
        return heroSnippets[index]
    }

    /**
     * Returns a random quote.
     */
    fun getRandomQuote(): Pair<String, String> = quotes.random()

    /**
     * Returns the total number of quotes available.
     */
    fun count(): Int = quotes.size
}
