package dev.chaseallbright.localscribe.audio

import android.media.AudioManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFocusPolicyTest {

    @Test
    fun `full loss stops recording`() {
        assertTrue(shouldStopRecordingForFocusChange(AudioManager.AUDIOFOCUS_LOSS))
    }

    @Test
    fun `transient loss stops recording`() {
        assertTrue(shouldStopRecordingForFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT))
    }

    @Test
    fun `duckable transient loss does not stop recording`() {
        assertFalse(shouldStopRecordingForFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK))
    }

    @Test
    fun `regaining focus does not stop recording`() {
        assertFalse(shouldStopRecordingForFocusChange(AudioManager.AUDIOFOCUS_GAIN))
    }
}
