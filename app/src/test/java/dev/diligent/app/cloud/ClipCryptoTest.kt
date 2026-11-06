package dev.diligent.app.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipCryptoTest {

    @Test
    fun roundTrip() {
        val stored = ClipCrypto.encrypt("ssh-ed25519 AAAA… ✓", "pass phrase")
        assertTrue(stored.startsWith(ClipCrypto.PREFIX))
        assertEquals("ssh-ed25519 AAAA… ✓", ClipCrypto.decrypt(stored, "pass phrase"))
    }

    @Test
    fun freshIvEachTime() {
        assertNotEquals(ClipCrypto.encrypt("same", "k"), ClipCrypto.encrypt("same", "k"))
    }

    @Test
    fun plainClipsPassThrough() {
        assertEquals("hello", ClipCrypto.decrypt("hello", null))
        assertEquals("hello", ClipCrypto.decrypt("hello", "any"))
    }

    @Test
    fun wrongOrMissingPassphraseGivesNull() {
        val stored = ClipCrypto.encrypt("secret", "right")
        assertNull(ClipCrypto.decrypt(stored, "wrong"))
        assertNull(ClipCrypto.decrypt(stored, null))
        assertNull(ClipCrypto.decrypt(ClipCrypto.PREFIX + "not base64!", "right"))
    }

    /** Written by the web app's WebCrypto code (web/js/cloud.js) with a fixed IV. */
    @Test
    fun readsTheWebAppsClips() {
        val fromWeb = "enc1:AQIDBAUGBwgJCgsMh6AhUG1/4/EEzhS7Rc+i8V9xyzEWmh1gAKgAbr+18/tKHBbUUJDqIQ=="
        assertEquals("héllo from the web 🚀", ClipCrypto.decrypt(fromWeb, "correct horse ✓"))
    }
}
