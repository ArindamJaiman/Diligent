package dev.diligent.app.data

import dev.diligent.app.data.local.entity.GithubUser
import dev.diligent.app.data.local.entity.GithubUsers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GithubUsersTest {

    private fun done(edit: GithubUsers.Edit): List<GithubUser> = (edit as GithubUsers.Edit.Done).users

    private fun rejected(edit: GithubUsers.Edit): String = (edit as GithubUsers.Edit.Rejected).message

    private fun names(users: List<GithubUser>) = users.map { it.username }

    private fun devs(vararg names: String) = names.mapIndexed { i, n -> GithubUser(n, GithubUsers.COLORS[i % 5], i) }

    @Test
    fun `more than ten developers can be added`() {
        var users = emptyList<GithubUser>()
        repeat(15) { users = done(GithubUsers.add(users, "dev-$it")) }
        assertEquals(15, users.size)
        assertEquals((0 until 15).toList(), users.map { it.position })
        assertEquals("dev-14", users.last().username)
    }

    @Test
    fun `the list stops at the cap`() {
        val full = (0 until GithubUsers.MAX_USERS).map { GithubUser("dev$it", "emerald", it) }
        assertEquals("Up to ${GithubUsers.MAX_USERS} developers", rejected(GithubUsers.add(full, "one-more")))
    }

    @Test
    fun `usernames, at-names and profile links are accepted`() {
        assertEquals("octocat", GithubUsers.normalize("  octocat "))
        assertEquals("octocat", GithubUsers.normalize("@octocat"))
        assertEquals("octo-cat", GithubUsers.normalize("https://github.com/octo-cat"))
        assertEquals("octocat", GithubUsers.normalize("github.com/octocat/?tab=repositories"))
        assertEquals("LennyDany-03", GithubUsers.normalize("LennyDany-03"))
    }

    @Test
    fun `things that can't be usernames are rejected`() {
        listOf("", "  ", "-leading", "has space", "under_score", "a".repeat(40), "emoji😀").forEach {
            assertNull(it, GithubUsers.normalize(it))
        }
        assertEquals("That doesn't look like a GitHub username", rejected(GithubUsers.add(emptyList(), "not valid")))
    }

    @Test
    fun `duplicates are caught ignoring case`() {
        val users = devs("Octocat")
        assertEquals("Octocat is already on the list", rejected(GithubUsers.add(users, "octocat")))
    }

    @Test
    fun `new developers get the least-used colour`() {
        val users = done(GithubUsers.add(GithubUsers.DEFAULTS, "fourth")) // emerald, crimson, blue taken
        assertEquals("gold", users.last().color)
        val more = done(GithubUsers.add(users, "fifth"))
        assertEquals("purple", more.last().color)
        assertEquals("emerald", done(GithubUsers.add(more, "sixth")).last().color)
    }

    @Test
    fun `rename keeps place and colour, and allows a change of case`() {
        val users = devs("alpha", "beta", "gamma")
        val renamed = done(GithubUsers.rename(users, "beta", "Beta"))
        assertEquals(listOf("alpha", "Beta", "gamma"), names(renamed))
        assertEquals(users[1].color, renamed[1].color)
        assertEquals("alpha is already on the list", rejected(GithubUsers.rename(users, "beta", "ALPHA")))
    }

    @Test
    fun `remove renumbers the rest`() {
        val users = done(GithubUsers.remove(devs("a", "b", "c"), "a"))
        assertEquals(listOf("b", "c"), names(users))
        assertEquals(listOf(0, 1), users.map { it.position })
    }

    @Test
    fun `move up and down, clamped at the ends`() {
        val users = devs("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), names(done(GithubUsers.move(users, "b", -1))))
        assertEquals(listOf("a", "c", "b"), names(done(GithubUsers.move(users, "b", 1))))
        assertEquals(listOf("a", "b", "c"), names(done(GithubUsers.move(users, "a", -1))))
        assertEquals(listOf(0, 1, 2), done(GithubUsers.move(users, "c", -2)).map { it.position })
        assertEquals(listOf("c", "a", "b"), names(done(GithubUsers.move(users, "c", -2))))
    }

    @Test
    fun `recolour accepts only known colours`() {
        val users = devs("a", "b")
        assertEquals("purple", done(GithubUsers.recolor(users, "b", "purple"))[1].color)
        assertTrue(GithubUsers.recolor(users, "b", "neon") is GithubUsers.Edit.Rejected)
    }

    @Test
    fun `old backups bring their three slots along`() {
        val settings = Json.parseToJsonElement(
            """{"githubUser1":" alice ","githubUser1Color":"gold","githubUser2":"","githubUser2Color":"crimson",""" +
                """"githubUser3":"ALICE","githubUser3Color":"blue","quotePreset":"zen"}"""
        ).jsonObject
        val users = GithubUsers.fromLegacySettings(settings)!!
        assertEquals(listOf(GithubUser("alice", "gold", 0)), users)

        val unknownColour = Json.parseToJsonElement("""{"githubUser1":"bob","githubUser1Color":"neon"}""").jsonObject
        assertEquals("emerald", GithubUsers.fromLegacySettings(unknownColour)!!.single().color)

        assertNull(GithubUsers.fromLegacySettings(Json.parseToJsonElement("""{"quotePreset":"zen"}""").jsonObject))
        assertNull(GithubUsers.fromLegacySettings(null))
    }
}
