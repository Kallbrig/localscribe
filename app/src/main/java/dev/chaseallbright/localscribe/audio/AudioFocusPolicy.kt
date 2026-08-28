package dev.chaseallbright.localscribe.audio

import android.media.AudioManager

/**
 * Decides whether an audio focus change received while recording should cancel the recording.
 * A full or transient loss means something else (a call, another app) now owns the mic's
 * priority, so LocalScribe backs off. A duckable transient loss is just a hint to lower output
 * volume -- meaningless for a recorder, since we produce no output -- so it's ignored.
 */
fun shouldStopRecordingForFocusChange(focusChange: Int): Boolean =
    when (focusChange) {
        AudioManager.AUDIOFOCUS_LOSS,
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> true
        else -> false
    }
