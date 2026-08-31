package dev.chaseallbright.localscribe.domain

import org.junit.Assert.assertEquals
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
    fun `a non-connectivity download failure does not blame the connection`() {
        // ModelDownloadException extends IOException and covers HTTP 404. Telling the user to
        // check their connection here would be actively misleading.
        val notFound = java.io.IOException("HTTP 404 for https://huggingface.co/ggml-base.en.bin")
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
    fun `a marked exception is still recorded by class for a report`() {
        assertEquals(
            "E-IMPORT/UserFacingException",
            FailureCopy.diagnosticFor(FailureContext.IMPORT, UserFacingException("nope"))
        )
    }
}
