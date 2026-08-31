package dev.chaseallbright.localscribe.domain

import dev.chaseallbright.localscribe.models.ModelDownloadException
import dev.chaseallbright.localscribe.transfer.TranscriptArchive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FailureCopyTest {

    @Test
    fun `a user-facing exception is shown verbatim`() {
        val error = UserFacingException("This file is not a LocalScribe export.")
        assertEquals(
            "This file is not a LocalScribe export.",
            FailureCopy.userMessageFor(FailureContext.IMPORT, error)
        )
    }

    @Test
    fun `an ordinary exception never leaks its message`() {
        // A real SQLite string. This is exactly what users were being shown.
        val error = IllegalStateException("database or disk is full (code 13 SQLITE_FULL[13])")
        val shown = FailureCopy.userMessageFor(FailureContext.DICTATION, error)
        assertTrue("leaked the raw message: $shown", !shown.contains("SQLITE_FULL"))
        assertTrue("leaked the raw message: $shown", !shown.contains("disk is full"))
        assertEquals("Dictation failed. Nothing was inserted. (E-DICT)", shown)
    }

    @Test
    fun `a blank user-facing message falls back to the generic copy`() {
        // Marked, but with nothing worth showing -- must not render an empty toast.
        assertEquals(
            "Export failed. (E-EXPORT)",
            FailureCopy.userMessageFor(FailureContext.EXPORT, UserFacingException("   "))
        )
        assertEquals(
            "Export failed. (E-EXPORT)",
            FailureCopy.userMessageFor(FailureContext.EXPORT, UserFacingException(""))
        )
    }

    @Test
    fun `each context carries its own code and copy`() {
        val error = RuntimeException("boom")
        assertEquals("Export failed. (E-EXPORT)", FailureCopy.userMessageFor(FailureContext.EXPORT, error))
        assertEquals("Import failed. (E-IMPORT)", FailureCopy.userMessageFor(FailureContext.IMPORT, error))
    }

    @Test
    fun `a connectivity failure during a download says so`() {
        val offline = java.net.UnknownHostException("Unable to resolve host \"huggingface.co\"")
        val shown = FailureCopy.userMessageFor(FailureContext.DOWNLOAD, offline)
        assertEquals("Download failed. Check your connection. (E-DOWNLOAD)", shown)
        assertTrue("leaked the host", !shown.contains("huggingface"))
    }

    @Test
    fun `a wrapped connectivity failure still says so`() {
        // The shape that ALWAYS occurs in production: ModelDownloader retries and then rethrows
        // every failure as ModelDownloadException(message, cause), so the connectivity type is
        // never the top-level throwable. Checking only the top level made the hint dead code.
        val wrapped = ModelDownloadException(
            "Download stalled or failed after 3 attempts for ggml-base.en.bin: " +
                "Unable to resolve host \"huggingface.co\"",
            java.net.UnknownHostException("Unable to resolve host \"huggingface.co\"")
        )
        val shown = FailureCopy.userMessageFor(FailureContext.DOWNLOAD, wrapped)
        assertEquals("Download failed. Check your connection. (E-DOWNLOAD)", shown)
        assertTrue("leaked the host", !shown.contains("huggingface"))
    }

    @Test
    fun `every connectivity type is recognised`() {
        listOf(
            java.net.UnknownHostException("x"),
            java.net.ConnectException("x"),
            java.net.SocketTimeoutException("x"),
            javax.net.ssl.SSLException("x")
        ).forEach { error ->
            assertEquals(
                "missed ${error::class.simpleName}",
                "Download failed. Check your connection. (E-DOWNLOAD)",
                FailureCopy.userMessageFor(FailureContext.DOWNLOAD, error)
            )
        }
    }

    @Test
    fun `a cyclic cause chain does not hang`() {
        val outer = RuntimeException("outer")
        // initCause would reject a self-reference; a mutual cycle is the reachable hazard.
        val inner = RuntimeException("inner", outer)
        outer.initCause(inner)
        assertEquals(
            "Download failed. (E-DOWNLOAD)",
            FailureCopy.userMessageFor(FailureContext.DOWNLOAD, outer)
        )
    }

    @Test
    fun `a non-connectivity download failure does not blame the connection`() {
        // ModelDownloadException extends IOException and covers HTTP 404. Telling the user to
        // check their connection here would be actively misleading.
        // Nested exactly as production does it: the 404 is raised inside the retry loop and
        // rethrown wrapped, and the inner one carries a null cause -- so the walk finds nothing.
        val notFound = ModelDownloadException(
            "Download stalled or failed after 3 attempts for ggml-base.en.bin: HTTP 404",
            ModelDownloadException("HTTP 404 for https://huggingface.co/ggml-base.en.bin")
        )
        val shown = FailureCopy.userMessageFor(FailureContext.DOWNLOAD, notFound)
        assertEquals("Download failed. (E-DOWNLOAD)", shown)
        assertTrue("leaked the url", !shown.contains("huggingface"))
    }

    @Test
    fun `connectivity types get no hint in a context that declares none`() {
        val offline = java.net.UnknownHostException("nope")
        assertEquals(
            "Dictation failed. Nothing was inserted. (E-DICT)",
            FailureCopy.userMessageFor(FailureContext.DICTATION, offline)
        )
    }

    @Test
    fun `a diagnostic names the class and the code but never the message`() {
        val error = IllegalStateException("database or disk is full (code 13 SQLITE_FULL[13])")
        val diagnostic = FailureCopy.diagnosticFor(FailureContext.DICTATION, error)
        assertEquals("E-DICT/IllegalStateException", diagnostic)
        assertTrue(!diagnostic.contains("SQLITE_FULL"))
    }

    @Test
    fun `a class with no simple name is recorded as Unknown`() {
        // Anonymous classes report a null simpleName.
        val anonymous = object : RuntimeException("x") {}
        assertEquals(
            "E-DICT/Unknown",
            FailureCopy.diagnosticFor(FailureContext.DICTATION, anonymous)
        )
    }

    @Test
    fun `the marker is actually attached to UnsupportedArchive`() {
        // Without this, deleting ", UserFacingMessage" from TranscriptArchive leaves the suite
        // green while a real user message silently degrades to "Import failed. (E-IMPORT)".
        val thrown = assertThrows(TranscriptArchive.UnsupportedArchive::class.java) {
            TranscriptArchive.decode("not json")
        }
        assertEquals(
            "This file is not a LocalScribe export.",
            FailureCopy.userMessageFor(FailureContext.IMPORT, thrown)
        )
    }

    @Test
    fun `a marked exception is still recorded by class for a report`() {
        assertEquals(
            "E-IMPORT/UserFacingException",
            FailureCopy.diagnosticFor(FailureContext.IMPORT, UserFacingException("nope"))
        )
    }
}
