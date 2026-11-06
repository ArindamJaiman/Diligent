package dev.diligent.app.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSendClipTest {

    @Test
    fun `new text from another app is sent`() {
        assertTrue(AutoSendClip.shouldSend(true, "https://arxiv.org/abs/2401.00001", "Chrome", false, AutoSendClip.hash("old")))
        assertTrue(AutoSendClip.shouldSend(true, "first copy", null, false, null))
    }

    @Test
    fun `nothing is sent when off, empty, already seen, our own or sensitive`() {
        assertFalse(AutoSendClip.shouldSend(false, "text", null, false, null))
        assertFalse(AutoSendClip.shouldSend(true, "  ", null, false, null))
        assertFalse(AutoSendClip.shouldSend(true, "same", null, false, AutoSendClip.hash("same")))
        assertFalse(AutoSendClip.shouldSend(true, "a clip we copied", AutoSendClip.OWN_LABEL, false, null))
        assertFalse(AutoSendClip.shouldSend(true, "hunter2", "Bitwarden", true, null))
    }

    @Test
    fun `hash is short, stable and different per text`() {
        assertEquals(32, AutoSendClip.hash("x").length)
        assertEquals(AutoSendClip.hash("x"), AutoSendClip.hash("x"))
        assertNotEquals(AutoSendClip.hash("x"), AutoSendClip.hash("y"))
    }
}
