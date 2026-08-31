package dev.chaseallbright.localscribe.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackReportTest {

    private val facts = DeviceFacts(
        appVersion = "0.2.0-beta.4",
        versionCode = 12,
        androidRelease = "16",
        sdkInt = 36,
        manufacturer = "samsung",
        model = "SM-S938U",
        abi = "arm64-v8a",
        cpuVerdict = "supported",
        totalRamGb = 11.4,
        whisperTier = "base.en",
        whisperDownloaded = true,
        cleanupTier = "qwen-0.5b",
        cleanupDownloaded = false,
        cleanupMode = "Standard",
        recordingLimit = "2 minutes",
        recentFailures = listOf("E-DICT/IllegalStateException")
    )

    @Test
    fun `the body carries every fact`() {
        val body = FeedbackReport.body(facts, "It stopped working.")
        listOf(
            "0.2.0-beta.4", "12", "16", "36", "samsung", "SM-S938U", "arm64-v8a",
            "supported", "11.4", "base.en", "qwen-0.5b", "Standard", "2 minutes",
            "E-DICT/IllegalStateException", "It stopped working."
        ).forEach { assertTrue("body is missing '$it':\n$body", body.contains(it)) }
    }

    @Test
    fun `no recent failures still renders`() {
        val body = FeedbackReport.body(facts.copy(recentFailures = emptyList()), "hi")
        assertTrue(body.contains("none"))
    }

    @Test
    fun `over-long user text is truncated with a marker`() {
        val long = "x".repeat(FeedbackReport.MAX_USER_TEXT + 500)
        val body = FeedbackReport.body(facts, long)
        assertTrue(body.contains("truncated"))
        // Asserts the text is actually shortened. A length bound alone passed even with
        // truncation removed entirely, because the diagnostics block is small.
        assertFalse(
            "user text was not truncated",
            body.contains("x".repeat(FeedbackReport.MAX_USER_TEXT + 1))
        )
    }

    @Test
    fun `multi-line user text does not indent the diagnostics into a code block`() {
        // Kotlin interpolates before trimIndent() runs, so a second line at indent zero used to
        // drag the common indent to zero and leave every template line with twelve leading
        // spaces -- an indented code block in GitHub markdown. The table stopped being a table
        // the moment anyone pressed Enter, which is the modal case for a "What happened?" box.
        val body = FeedbackReport.body(facts, "first line\nsecond line")
        assertTrue("diagnostics heading was indented:\n$body", body.contains("\n### Diagnostics"))
        val indented = body.lines().filter { it.startsWith("    ") }
        assertEquals("these lines would render as code:\n$indented", emptyList<String>(), indented)
    }

    @Test
    fun `a report stays inside the url budget even in a non-latin script`() {
        // MAX_USER_TEXT is a character cap, but the budget that binds is the ENCODED length:
        // Cyrillic costs three bytes per character, CJK and emoji up to twelve. Capping
        // characters alone pushed a Russian user to the clipboard fallback while an English
        // user wrote three times as much and got a browser.
        listOf("я" to "Cyrillic", "字" to "CJK", "🙂" to "emoji").forEach { (glyph, name) ->
            val body = FeedbackReport.body(facts, glyph.repeat(FeedbackReport.MAX_USER_TEXT))
            assertNotNull(
                "$name report did not fit the url budget",
                FeedbackReport.issueUrl("Feedback from 0.2.0-beta.4", body, "feedback")
            )
        }
    }

    @Test
    fun `the url length cap is inclusive at the boundary`() {
        val base = FeedbackReport.issueUrl("t", "", "feedback")!!.length
        val padding = "y".repeat(FeedbackReport.MAX_URL_LENGTH - base)
        assertNotNull(FeedbackReport.issueUrl("t", padding, "feedback"))
        assertNull(FeedbackReport.issueUrl("t", padding + "y", "feedback"))
    }

    @Test
    fun `an unsupported cpu verdict is not mistaken for a supported one`() {
        val body = FeedbackReport.body(facts.copy(cpuVerdict = "unsupported, missing asimdhp"), "x")
        assertTrue(body.contains("unsupported, missing asimdhp"))
    }

    @Test
    fun `blank user text is replaced with a prompt rather than left empty`() {
        val body = FeedbackReport.body(facts, "   ")
        assertTrue(body.contains("No description given"))
    }

    @Test
    fun `the url percent-encodes the characters that would break a query string`() {
        val url = FeedbackReport.issueUrl("a b", "one&two#three\nfour", "feedback")
        assertNotNull(url)
        val encoded = url!!
        assertTrue(encoded.startsWith(FeedbackReport.ISSUE_BASE_URL + "?"))
        // A raw # starts a fragment, so everything after it never reaches the server; a raw &
        // would start a new query parameter. Neither may survive anywhere in the URL.
        assertFalse("a raw # would truncate the body", encoded.contains("#"))
        assertTrue("& was not encoded", encoded.contains("%26"))
        assertTrue("# was not encoded", encoded.contains("%23"))
        // Exactly two separators: ?title=...&body=...&labels=... Any more means a raw & leaked
        // out of the body and split it into a bogus extra parameter.
        assertEquals(2, encoded.count { it == '&' })
        assertTrue(encoded.contains("labels=feedback"))
    }

    @Test
    fun `a url past the length cap is refused rather than silently truncated`() {
        val huge = "y".repeat(FeedbackReport.MAX_URL_LENGTH)
        assertNull(FeedbackReport.issueUrl("title", huge, "feedback"))
    }

    @Test
    fun `an ordinary report fits within the cap`() {
        val body = FeedbackReport.body(facts, "Something went wrong when I tapped the bubble.")
        assertNotNull(FeedbackReport.issueUrl("Feedback from 0.2.0-beta.4", body, "feedback"))
    }
}
