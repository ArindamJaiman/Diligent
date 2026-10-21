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
}
