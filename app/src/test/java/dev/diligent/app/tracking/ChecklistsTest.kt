package dev.diligent.app.tracking

import dev.diligent.app.data.local.entity.Activity
import org.junit.Assert.assertEquals
import org.junit.Test

class ChecklistsTest {

    private fun activity(goal: Float, goalType: String = Goals.AT_LEAST) =
        Activity(id = 3, name = "LeetCode", unit = "problems", dailyGoal = goal, goalType = goalType)

    @Test
    fun `items split on lines, commas and semicolons, trimmed and without repeats`() {
        assertEquals(listOf("1 easy", "1 medium", "1 hard"), Checklists.parse(" 1 easy\n1 medium, 1 hard;\n\n1 easy "))
        assertEquals(Checklists.MAX_ITEMS, Checklists.parse((1..20).joinToString("\n") { "item $it" }).size)
        assertEquals(emptyList<String>(), Checklists.parse("  \n , ;"))
    }

    @Test
    fun `each item is worth an equal share of the goal`() {
        assertEquals(1f, Checklists.step(activity(3f), 3), 0f)
        assertEquals(0.5f, Checklists.step(activity(2f), 4), 0f)
        assertEquals(0f, Checklists.step(activity(3f), 0), 0f)
    }

    @Test
    fun `limits have nothing to tick off`() {
        assertEquals(0f, Checklists.step(activity(60f, Goals.AT_MOST), 3), 0f)
        assertEquals(0f, Checklists.step(activity(0f, Goals.QUIT), 3), 0f)
    }

    @Test
    fun `the done count ignores ticks past the end of a shortened list`() {
        assertEquals(2, ChecklistDay(listOf("a", "b"), setOf(0, 1, 4)).count)
    }
}
