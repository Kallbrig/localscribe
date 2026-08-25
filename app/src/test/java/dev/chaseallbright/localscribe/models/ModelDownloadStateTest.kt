package dev.chaseallbright.localscribe.models

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelDownloadStateTest {

    @Test
    fun `fraction reflects progress`() {
        val state = ModelDownloadState.Downloading(bytesDone = 50, bytesTotal = 200)

        assertEquals(0.25f, state.fraction, 0.001f)
    }

    @Test
    fun `an unknown total reports no progress rather than dividing by zero`() {
        // ModelDownloader falls back to a HEAD request for the size, which can come back empty.
        val state = ModelDownloadState.Downloading(bytesDone = 1_000, bytesTotal = 0)

        assertEquals(0f, state.fraction, 0.001f)
    }

    @Test
    fun `overshooting the estimated size is clamped to full`() {
        // approxSizeBytes is an estimate; a slightly larger real file must not exceed 100%.
        val state = ModelDownloadState.Downloading(bytesDone = 220, bytesTotal = 200)

        assertEquals(1f, state.fraction, 0.001f)
    }
}
