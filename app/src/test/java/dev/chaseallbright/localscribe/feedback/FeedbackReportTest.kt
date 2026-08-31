package dev.chaseallbright.localscribe.feedback

import org.junit.Assert.assertEquals
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
        assertTrue(
            "user text was not truncated",
            body.length < long.length + 2000
        )
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
        // A raw & or # would truncate the body at GitHub's end.
        assertTrue(!encoded.substringAfter("body=").substringBefore("&labels").contains("#"))
        assertTrue(encoded.contains("%26") || encoded.contains("%2526"))
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
