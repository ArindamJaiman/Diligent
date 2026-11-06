package dev.diligent.app.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChallengeTemplatesTest {

    @Test
    fun templatesAreUsable() {
        val all = ChallengeTemplates.ALL
        assertTrue(all.isNotEmpty())
        assertEquals("titles are unique", all.size, all.map { it.title }.toSet().size)
        all.forEach {
            assertTrue(it.keyword.isNotBlank())
            assertTrue(it.target > 0)
            assertTrue(it.days in 1..365)
            assertTrue(it.unit in setOf("hrs", "mins", "qs"))
        }
    }

    /** The web app (web/js/cloud.js CHALLENGE_TEMPLATES) lists the same templates; keep them in step. */
    @Test
    fun matchesTheWebList() {
        assertEquals(
            listOf("LeetCode 30", "Quantum 100 h", "Deep work week", "Gym 12", "Reading 20 h", "Papers sprint"),
            ChallengeTemplates.ALL.map { it.title }
        )
    }
}
