package dev.chaseallbright.localscribe.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleColorTest {

    @Test
    fun `default is the original purple`() {
        assertEquals(BubbleColor.PURPLE, BubbleColor.DEFAULT)
        assertEquals(0xFF6750A4L, BubbleColor.PURPLE.argb)
    }

    @Test
    fun `white is offered`() {
        assertEquals(0xFFFFFFFFL, BubbleColor.WHITE.argb)
    }

    @Test
    fun `light swatches get a dark glyph`() {
        assertEquals(BubbleColor.DARK_CONTENT, BubbleColor.WHITE.contentArgb)
        assertEquals(BubbleColor.DARK_CONTENT, BubbleColor.AMBER.contentArgb)
    }

    @Test
    fun `dark swatches get a light glyph`() {
        assertEquals(BubbleColor.LIGHT_CONTENT, BubbleColor.BLACK.contentArgb)
        assertEquals(BubbleColor.LIGHT_CONTENT, BubbleColor.PURPLE.contentArgb)
        assertEquals(BubbleColor.LIGHT_CONTENT, BubbleColor.GRAY.contentArgb)
    }

    @Test
    fun `every swatch keeps its glyph at least large-text legible`() {
        // WCAG 3:1 is the bar for graphical objects and large text; a mic glyph is both.
        BubbleColor.entries.forEach { color ->
            val ratio = BubbleColor.contrastRatio(color.argb, color.contentArgb)
            assertTrue("${color.name} contrast $ratio", ratio >= 3.0)
        }
    }

    @Test
    fun `ids are unique so persistence cannot collide`() {
        val ids = BubbleColor.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `buttons differ from the pill they sit on`() {
        BubbleColor.entries.forEach { color ->
            assertTrue(color.name, color.buttonArgb != color.argb)
        }
    }

    @Test
    fun `button glyphs stay legible on every swatch`() {
        BubbleColor.entries.forEach { color ->
            val ratio = BubbleColor.contrastRatio(color.buttonArgb, color.contentArgb)
            assertTrue("${color.name} button contrast $ratio", ratio >= 3.0)
        }
    }

    @Test
    fun `dark pills get lighter buttons and light pills darker ones`() {
        val black = 0xFF000000L
        assertTrue(
            BubbleColor.contrastRatio(BubbleColor.BLACK.buttonArgb, black) >
                BubbleColor.contrastRatio(BubbleColor.BLACK.argb, black)
        )
        assertTrue(
            BubbleColor.contrastRatio(BubbleColor.WHITE.buttonArgb, black) <
                BubbleColor.contrastRatio(BubbleColor.WHITE.argb, black)
        )
    }

    @Test
    fun `contrast of black on white is twenty one`() {
        assertEquals(21.0, BubbleColor.contrastRatio(0xFF000000L, 0xFFFFFFFFL), 0.01)
    }
}
