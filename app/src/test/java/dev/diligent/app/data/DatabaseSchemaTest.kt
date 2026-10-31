package dev.diligent.app.data

import dev.diligent.app.data.local.DiligentDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Keeps the hand-written migrations in step with the schemas Room exports at build time (app/schemas). */
class DatabaseSchemaTest {

    private fun database(version: Int): JsonObject {
        val path = "schemas/dev.diligent.app.data.local.DiligentDatabase/$version.json"
        val file = listOf(File(path), File("app/$path")).first { it.exists() }
        return Json.parseToJsonElement(file.readText()).jsonObject.getValue("database").jsonObject
    }

    private fun entity(version: Int, table: String): JsonObject =
        database(version).getValue("entities").jsonArray
            .map { it.jsonObject }
            .single { it.getValue("tableName").jsonPrimitive.content == table }

    /** tableName → createSql with the placeholder filled in. */
    private fun tables(version: Int): Map<String, String> =
        database(version).getValue("entities").jsonArray.associate { entity ->
            val name = entity.jsonObject.getValue("tableName").jsonPrimitive.content
            name to entity.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", name)
        }

    private fun indices(version: Int, table: String): List<String> =
        entity(version, table)["indices"]?.jsonArray
            ?.map { it.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table) }
            ?: emptyList()

    @Test
    fun `migration 4 to 5 creates leetcode_reviews exactly as Room exports it`() {
        assertEquals(5, database(5).getValue("version").jsonPrimitive.int)
        assertEquals(tables(5).getValue("leetcode_reviews"), DiligentDatabase.CREATE_LEETCODE_REVIEWS)
        val entity = entity(5, "leetcode_reviews")
        assertEquals(0, entity["indices"]?.jsonArray?.size ?: 0)
        assertEquals(0, entity["foreignKeys"]?.jsonArray?.size ?: 0)
    }

    @Test
    fun `version 5 only adds leetcode_reviews`() {
        assertEquals(tables(4), tables(5) - "leetcode_reviews")
    }

    @Test
    fun `migration 5 to 6 builds github_users and the slimmer settings exactly as Room exports them`() {
        assertEquals(6, database(6).getValue("version").jsonPrimitive.int)
        assertEquals(tables(6).getValue("github_users"), DiligentDatabase.CREATE_GITHUB_USERS)
        assertEquals(tables(6).getValue("settings"), DiligentDatabase.createSettingsV6("settings"))
        listOf("github_users", "settings").forEach { table ->
            assertEquals(0, entity(6, table)["indices"]?.jsonArray?.size ?: 0)
            assertEquals(0, entity(6, table)["foreignKeys"]?.jsonArray?.size ?: 0)
        }
    }

    @Test
    fun `version 6 changes only settings and github_users`() {
        assertEquals(tables(5) - "settings", tables(6) - "settings" - "github_users")
    }

    @Test
    fun `migration 6 to 7 appends columns exactly as Room exports them`() {
        assertEquals(7, database(7).getValue("version").jsonPrimitive.int)
        val activities = tables(6).getValue("activities")
        assertEquals(
            activities.removeSuffix(")") + ", " + DiligentDatabase.ACTIVITY_COLUMNS_V7.joinToString(", ") { it.first } + ")",
            tables(7).getValue("activities")
        )
        val progress = tables(6).getValue("daily_progress")
        val foreignKey = progress.indexOf(", FOREIGN KEY")
        assertEquals(
            progress.substring(0, foreignKey) + ", " + DiligentDatabase.PROGRESS_COLUMNS_V7.joinToString(", ") { it.first } +
                progress.substring(foreignKey),
            tables(7).getValue("daily_progress")
        )
        assertEquals(indices(6, "daily_progress"), indices(7, "daily_progress"))
    }

    @Test
    fun `migration 6 to 7 creates focus_sessions and day_logs exactly as Room exports them`() {
        assertEquals(tables(7).getValue("focus_sessions"), DiligentDatabase.CREATE_FOCUS_SESSIONS)
        assertEquals(indices(7, "focus_sessions"), DiligentDatabase.FOCUS_SESSIONS_INDICES)
        assertEquals(tables(7).getValue("day_logs"), DiligentDatabase.CREATE_DAY_LOGS)
        assertEquals(0, entity(7, "day_logs")["indices"]?.jsonArray?.size ?: 0)
    }

    @Test
    fun `version 7 changes only activities and daily_progress and adds two tables`() {
        val changed = setOf("activities", "daily_progress")
        assertEquals(tables(6) - changed, tables(7) - changed - "focus_sessions" - "day_logs")
    }
}
