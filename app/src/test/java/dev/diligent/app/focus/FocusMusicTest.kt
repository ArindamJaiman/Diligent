package dev.diligent.app.focus

import dev.diligent.app.focus.FocusMusic.Action
import dev.diligent.app.focus.FocusMusic.Config
import dev.diligent.app.focus.FocusMusic.Event
import dev.diligent.app.focus.FocusMusic.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusMusicTest {

    private val resume = Config(Mode.RESUME)
    private val link = Config(Mode.LINK, "https://open.spotify.com/playlist/abc")

    @Test
    fun `off does nothing`() {
        Event.entries.forEach { assertEquals(emptyList<Action>(), FocusMusic.actions(Config(), it, true, false, true)) }
    }

    @Test
    fun `start plays unless something already is`() {
        assertEquals(listOf(Action.Play), FocusMusic.actions(resume, Event.START, false, playing = false, foreground = false))
        assertEquals(emptyList<Action>(), FocusMusic.actions(resume, Event.START, false, playing = true, foreground = true))
    }

    @Test
    fun `a link opens from the app and falls back to play from the widget`() {
        assertEquals(listOf(Action.Open("https://open.spotify.com/playlist/abc")), FocusMusic.actions(link, Event.START, false, false, foreground = true))
        assertEquals(listOf(Action.Play), FocusMusic.actions(link, Event.START, false, false, foreground = false))
        assertEquals(listOf(Action.Play), FocusMusic.actions(Config(Mode.LINK, "not a link"), Event.START, false, false, foreground = true))
    }

    @Test
    fun `stop pauses only music this started`() {
        assertEquals(listOf(Action.Pause), FocusMusic.actions(resume, Event.STOP, startedByUs = true, playing = true, foreground = false))
        assertEquals(emptyList<Action>(), FocusMusic.actions(resume, Event.STOP, startedByUs = false, playing = true, foreground = false))
        assertEquals(emptyList<Action>(), FocusMusic.actions(resume, Event.STOP, startedByUs = true, playing = false, foreground = false))
    }

    @Test
    fun `pomodoro breaks pause and the next block plays again`() {
        assertEquals(listOf(Action.Pause), FocusMusic.actions(resume, Event.BREAK, true, playing = true, foreground = false))
        assertEquals(listOf(Action.Play), FocusMusic.actions(resume, Event.FOCUS, true, playing = false, foreground = false))
        val keepPlaying = resume.copy(pauseOnBreaks = false)
        assertEquals(emptyList<Action>(), FocusMusic.actions(keepPlaying, Event.BREAK, true, playing = true, foreground = false))
    }

    @Test
    fun `links`() {
        assertTrue(FocusMusic.isLink(" spotify:playlist:37i9dQZF1DX8Uebhn9wzrS "))
        assertTrue(FocusMusic.isLink("https://music.youtube.com/playlist?list=x"))
        assertFalse(FocusMusic.isLink("lofi beats"))
    }
}
