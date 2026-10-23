package dev.diligent.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateFeedTest {

    private val sha = "a".repeat(64)
    private val url = "https://github.com/ArindamJaiman/Diligent/releases/download/build-42/Diligent-1.0.42.apk"

    /** What the release workflow writes (jq output). */
    private val feed = """
        {
          "versionCode": 42,
          "versionName": "1.0.42",
          "commit": "c100e26f0123456789abcdef0123456789abcdef",
          "apkUrl": "$url",
          "sha256": "$sha",
          "sizeBytes": 24454222,
          "notes": "feat: instant widget taps",
          "publishedAt": "2026-10-23T09:00:00Z",
          "somethingNew": true
        }
    """.trimIndent()

    private fun info(code: Int = 42) = UpdateFeed.parse(feed)!!.copy(versionCode = code)

    @Test
    fun `parses the feed CI publishes and ignores unknown fields`() {
        val info = UpdateFeed.parse(feed)
        assertNotNull(info)
        assertEquals(42, info!!.versionCode)
        assertEquals("1.0.42", info.versionName)
        assertEquals("c100e26", info.shortCommit)
        assertEquals(24_454_222L, info.sizeBytes)
    }

    @Test
    fun `upper-case checksums are accepted`() {
        assertEquals(sha, UpdateFeed.parse(feed.replace(sha, sha.uppercase()))!!.sha256)
    }

    @Test
    fun `malformed or unsafe feeds are refused`() {
        assertNull(UpdateFeed.parse("not json"))
        assertNull(UpdateFeed.parse(feed.replace(sha, "1234")))
        assertNull(UpdateFeed.parse(feed.replace("https://github.com", "http://github.com")))
        assertNull(UpdateFeed.parse(feed.replace("https://github.com", "https://evil.example")))
        assertNull(UpdateFeed.parse(feed.replace("Diligent-1.0.42.apk", "notes.txt")))
        assertNull(UpdateFeed.parse(feed.replace("\"versionCode\": 42", "\"versionCode\": 0")))
    }

    @Test
    fun `only a higher version code is an update`() {
        assertTrue(UpdateFeed.isNewer(info(42), 41))
        assertFalse(UpdateFeed.isNewer(info(42), 42))
        assertFalse(UpdateFeed.isNewer(info(40), 42))
    }

    @Test
    fun `opening the app checks at most every six hours`() {
        val hour = 3_600_000L
        assertTrue(UpdateFeed.autoCheckDue(0L, 10 * hour))
        assertFalse(UpdateFeed.autoCheckDue(10 * hour, 15 * hour))
        assertTrue(UpdateFeed.autoCheckDue(10 * hour, 16 * hour))
        assertTrue("clock moved back", UpdateFeed.autoCheckDue(10 * hour, 9 * hour))
    }

    @Test
    fun `later hides that version for a day, but not a newer one`() {
        val now = 100_000_000L
        val until = now + UpdateFeed.SNOOZE_MS
        assertFalse(UpdateFeed.shouldPrompt(info(42), 41, snoozedVersion = 42, snoozedUntil = until, now = now))
        assertTrue(UpdateFeed.shouldPrompt(info(43), 41, snoozedVersion = 42, snoozedUntil = until, now = now))
        assertTrue(UpdateFeed.shouldPrompt(info(42), 41, snoozedVersion = 42, snoozedUntil = until, now = until))
        assertFalse(UpdateFeed.shouldPrompt(info(42), 42, snoozedVersion = 0, snoozedUntil = 0, now = now))
    }

    @Test
    fun `sha256 matches the known digest`() {
        // sha256("abc")
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            UpdateFeed.sha256Hex("abc".byteInputStream())
        )
    }
}
