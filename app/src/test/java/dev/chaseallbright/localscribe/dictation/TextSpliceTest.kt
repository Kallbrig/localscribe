package dev.chaseallbright.localscribe.dictation

import org.junit.Assert.assertEquals
import org.junit.Test

class TextSpliceTest {

    @Test
    fun `inserts at the cursor without disturbing surrounding text`() {
        val result = spliceAtCursor("Hello world", selectionStart = 5, selectionEnd = 5, insertion = " there")

        assertEquals("Hello there world", result.text)
        assertEquals(11, result.cursor)
    }

    @Test
    fun `empty field just receives the dictation`() {
        val result = spliceAtCursor("", selectionStart = 0, selectionEnd = 0, insertion = "Good morning.")

        assertEquals("Good morning.", result.text)
        assertEquals(13, result.cursor)
    }

    @Test
    fun `a selection is replaced, matching normal typing behavior`() {
        val result = spliceAtCursor("keep DROP keep", selectionStart = 5, selectionEnd = 9, insertion = "new")

        assertEquals("keep new keep", result.text)
        assertEquals(8, result.cursor)
    }

    @Test
    fun `unknown selection appends rather than overwriting the field`() {
        // AccessibilityNodeInfo reports -1 when it has no selection information.
        val result = spliceAtCursor("existing", selectionStart = -1, selectionEnd = -1, insertion = " added")

        assertEquals("existing added", result.text)
        assertEquals(14, result.cursor)
    }

    @Test
    fun `a backwards selection is normalized`() {
        val result = spliceAtCursor("keep DROP keep", selectionStart = 9, selectionEnd = 5, insertion = "new")

        assertEquals("keep new keep", result.text)
        assertEquals(8, result.cursor)
    }

    @Test
    fun `stale out-of-range indices are clamped instead of crashing`() {
        val result = spliceAtCursor("short", selectionStart = 99, selectionEnd = 120, insertion = "!")

        assertEquals("short!", result.text)
        assertEquals(6, result.cursor)
    }

    @Test
    fun `cursor at the very start prepends`() {
        val result = spliceAtCursor("world", selectionStart = 0, selectionEnd = 0, insertion = "hello ")

        assertEquals("hello world", result.text)
        assertEquals(6, result.cursor)
    }
}
