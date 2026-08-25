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

    @Test
    fun `separates dictation from preceding text with a space`() {
        val result = spliceAtCursor("Hello", selectionStart = 5, selectionEnd = 5, insertion = "there.")

        assertEquals("Hello there.", result.text)
        assertEquals(12, result.cursor)
    }

    @Test
    fun `does not double up an existing trailing space`() {
        val result = spliceAtCursor("Hello ", selectionStart = 6, selectionEnd = 6, insertion = "there")

        assertEquals("Hello there", result.text)
        assertEquals(11, result.cursor)
    }

    @Test
    fun `separates dictation from following text with a space`() {
        val result = spliceAtCursor("world", selectionStart = 0, selectionEnd = 0, insertion = "hello")

        assertEquals("hello world", result.text)
        assertEquals(5, result.cursor)
    }

    @Test
    fun `no space is added after an opening bracket or quote`() {
        val result = spliceAtCursor("(", selectionStart = 1, selectionEnd = 1, insertion = "an aside")

        assertEquals("(an aside", result.text)
        assertEquals(9, result.cursor)
    }

    @Test
    fun `no space is added before punctuation that follows the cursor`() {
        val result = spliceAtCursor("Hello.", selectionStart = 5, selectionEnd = 5, insertion = "there")

        assertEquals("Hello there.", result.text)
        assertEquals(11, result.cursor)
    }

    @Test
    fun `dictated punctuation attaches to the preceding word`() {
        val result = spliceAtCursor("Hello", selectionStart = 5, selectionEnd = 5, insertion = ",")

        assertEquals("Hello,", result.text)
        assertEquals(6, result.cursor)
    }

    @Test
    fun `a newline counts as separation`() {
        val result = spliceAtCursor("Line one\n", selectionStart = 9, selectionEnd = 9, insertion = "Line two")

        assertEquals("Line one\nLine two", result.text)
        assertEquals(17, result.cursor)
    }
}
