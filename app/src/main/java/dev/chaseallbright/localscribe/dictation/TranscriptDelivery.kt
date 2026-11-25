package dev.chaseallbright.localscribe.dictation

import dev.chaseallbright.localscribe.domain.Transcript
import kotlin.coroutines.cancellation.CancellationException

/**
 * Hands a finished transcript to the field, treating its history row as a side record rather
 * than a prerequisite.
 *
 * The save used to run immediately before [published] on the dictation coroutine, so a Room
 * failure (a full disk, say) meant the spoken text was never delivered at all -- the one
 * outcome this app's three-tier insertion exists to prevent. A failure to save is a lost
 * history row, which is recoverable; a failure to deliver is a lost dictation, which is not.
 *
 * Only ordinary save exceptions are tolerated. Cancellation still aborts, and a failure from
 * [published] is not a save failure and must not be reported as one.
 *
 * Android-free so the whole contract is unit-testable without a service or a database.
 *
 * @param save writes the history row. Runs first; failures are passed to [onSaveFailure].
 * @param published delivers the transcript. Runs once, after [save] settles either way.
 */
internal suspend fun deliverTranscript(
    transcript: Transcript,
    save: suspend () -> Unit,
    published: suspend (Transcript) -> Unit,
    onSaveFailure: (Exception) -> Unit = {}
) {
    try {
        save()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onSaveFailure(e)
    }
    // Outside the try: a delivery failure must propagate as itself, never be reported as a
    // history-save failure.
    published(transcript)
}
