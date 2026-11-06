package dev.diligent.app.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipWatchTest {

    private fun clip(id: String, device: String, at: Long, kind: String = "text", text: String = "t$id", locked: Boolean = false) =
        Clip(id = id, text = text, device = device, createdAt = at, kind = kind, locked = locked)

    @Test
    fun `only newer clips from other devices, oldest first`() {
        val clips = listOf(
            clip("a", "laptop", 300),
            clip("b", "Pixel 9", 400),   // this phone
            clip("c", "laptop", 100),    // before the last check
            clip("d", "web", 350)
        )
        assertEquals(listOf("a", "d"), ClipWatch.fromOthers(clips, since = 200, thisDevice = "pixel 9").map { it.id })
    }

    @Test
    fun `previews never show ciphertext or raw file details`() {
        assertEquals("hello", ClipWatch.preview(clip("x", "web", 1, text = "hello")))
        assertEquals("🖼 Image · open the web app to see it", ClipWatch.preview(clip("x", "web", 1, kind = "image", text = "data:image/png;base64,AAAA")))
        assertEquals("📎 notes.pdf", ClipWatch.preview(clip("x", "web", 1, kind = "file", text = "{\"name\":\"notes.pdf\",\"path\":\"p\"}")))
        assertEquals(true, ClipWatch.preview(clip("x", "web", 1, text = "enc1:abc", locked = true)).startsWith("🔒"))
    }
}
