package dev.diligent.app.desk

/**
 * A 5×7 dot-matrix font for digits and a few symbols, shared by the desk clock and the Glyph
 * Matrix frames. Each glyph is 7 rows of 5 characters, '#' = lit.
 */
object DotFont {

    const val WIDTH = 5
    const val HEIGHT = 7

    private val glyphs: Map<Char, List<String>> = mapOf(
        '0' to listOf(".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###."),
        '1' to listOf("..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."),
        '2' to listOf(".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"),
        '3' to listOf("#####", "...#.", "..#..", "...#.", "....#", "#...#", ".###."),
        '4' to listOf("...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."),
        '5' to listOf("#####", "#....", "####.", "....#", "....#", "#...#", ".###."),
        '6' to listOf("..##.", ".#...", "#....", "####.", "#...#", "#...#", ".###."),
        '7' to listOf("#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."),
        '8' to listOf(".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."),
        '9' to listOf(".###.", "#...#", "#...#", ".####", "....#", "...#.", ".##.."),
        ':' to listOf(".....", "..#..", "..#..", ".....", "..#..", "..#..", "....."),
        '%' to listOf("##..#", "##..#", "...#.", "..#..", ".#...", "#..##", "#..##"),
        'h' to listOf("#....", "#....", "#.##.", "##..#", "#...#", "#...#", "#...#"),
        'm' to listOf(".....", ".....", "##.#.", "#.#.#", "#.#.#", "#...#", "#...#"),
        ' ' to List(HEIGHT) { "....." },
        '-' to listOf(".....", ".....", ".....", ".###.", ".....", ".....", ".....")
    )

    /** Lit cells of [c] as (column, row) pairs; unknown characters are blank. */
    fun cells(c: Char): List<Pair<Int, Int>> {
        val rows = glyphs[c] ?: glyphs.getValue(' ')
        return rows.flatMapIndexed { y, row -> row.mapIndexedNotNull { x, ch -> if (ch == '#') x to y else null } }
    }

    /** Width in dots of [text] with one blank column between glyphs. */
    fun width(text: String): Int = if (text.isEmpty()) 0 else text.length * (WIDTH + 1) - 1
}
