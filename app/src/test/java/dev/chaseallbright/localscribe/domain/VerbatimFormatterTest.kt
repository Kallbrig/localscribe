package dev.chaseallbright.localscribe.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Informal's contract, driven by the reported cases. Whisper emits prose-formatted text --
 * sentence case, commas, terminal punctuation -- and informal is the mode that undoes that
 * rather than reinforcing it.
 */
class VerbatimFormatterTest {

    @Test
    fun `the reported case keeps the speaker's words and drops the correction`() {
        // Was being turned into "Hey man, what's up?" -- capitalised, comma inserted, and
        // "going on" swapped for "up".
        assertEquals(
            "hey man what's going on",
            VerbatimFormatter.format("Hey man, what's going on?")
        )
    }

    @Test
    fun `the second reported case keeps a major sentence break and nothing else`() {
        assertEquals(
            "hey my man what are you doing this weekend. I'm trying to go to the lake",
            VerbatimFormatter.format("Hey my man, what are you doing this weekend? I'm trying to go to the lake.")
        )
    }

    @Test
    fun `apostrophes already present are kept`() {
        assertEquals(
            "i don't think that's what he's saying",
            VerbatimFormatter.format("I don't think that's what he's saying.")
                .replace("I ", "i ") // "I" preservation is asserted separately
        )
    }

    @Test
    fun `the pronoun I stays capitalised`() {
        assertEquals("I'll be there in a minute", VerbatimFormatter.format("I'll be there in a minute."))
        assertEquals("yeah I know", VerbatimFormatter.format("Yeah, I know."))
    }

    @Test
    fun `names keep their capitals because whisper capitalised them mid-sentence`() {
        assertEquals(
            "tell Dana the meeting moved",
            VerbatimFormatter.format("Tell Dana the meeting moved.")
        )
    }

    @Test
    fun `a name at the start of a sentence still loses its sentence-case capital`() {
        // Nothing marks it as a name, so it reads as Whisper's sentence casing.
        assertEquals("dana is running late", VerbatimFormatter.format("Dana is running late."))
    }

    @Test
    fun `a name seen mid-sentence keeps its capital everywhere it appears`() {
        // Both, including the sentence-initial one: once a word is established as a name it
        // is a name everywhere, and lowercasing the first would look like a bug to the user.
        assertEquals(
            "Dana said Dana is late",
            VerbatimFormatter.format("Dana said Dana is late.")
        )
    }

    @Test
    fun `acronyms are left alone`() {
        assertEquals("send the PDF to HR", VerbatimFormatter.format("Send the PDF to HR."))
    }

    @Test
    fun `custom vocabulary casing wins`() {
        assertEquals(
            "ping Kallbrig about it",
            VerbatimFormatter.format("Ping Kallbrig about it.", listOf("Kallbrig"))
        )
    }

    @Test
    fun `disfluencies go but slang stays`() {
        assertEquals(
            "so like i was gonna say you know it's fine",
            VerbatimFormatter.format("Um, so like, uh, I was gonna say, you know, it's fine.")
                .replace("I ", "i ")
        )
    }

    @Test
    fun `a disfluency does not swallow the sentence break it carried`() {
        assertEquals("that's done. next thing", VerbatimFormatter.format("That's done. Um. Next thing."))
    }

    @Test
    fun `a leading disfluency does not produce a leading full stop`() {
        assertEquals("okay lets go", VerbatimFormatter.format("Um. Okay, lets go."))
    }

    @Test
    fun `repeated words collapse`() {
        // The whole repeated run collapses to a single occurrence, not to two.
        assertEquals("i want to go", VerbatimFormatter.format("I want want want to go.").replace("I ", "i "))
    }

    @Test
    fun `multiple sentences are separated but the text does not end in a full stop`() {
        assertEquals(
            "first thing. second thing. third thing",
            VerbatimFormatter.format("First thing. Second thing. Third thing.")
        )
    }

    @Test
    fun `empty input stays empty`() {
        assertEquals("", VerbatimFormatter.format(""))
        assertEquals("", VerbatimFormatter.format("   "))
    }

    @Test
    fun `nothing but disfluency produces nothing`() {
        assertEquals("", VerbatimFormatter.format("Um. Uh."))
    }

    @Test
    fun `no word is ever substituted`() {
        val source = "hey man what's going on"
        val formatted = VerbatimFormatter.format(source)
        val sourceWords = source.lowercase().split(" ").toSet()
        val formattedWords = formatted.lowercase().split(" ").map { it.trimEnd('.') }.toSet()

        assertEquals(
            "informal must be incapable of introducing a word",
            emptySet<String>(),
            formattedWords - sourceWords
        )
    }
}
