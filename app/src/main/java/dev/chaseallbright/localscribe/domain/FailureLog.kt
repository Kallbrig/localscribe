package dev.chaseallbright.localscribe.domain

/**
 * The last few failures, so a feedback report can say what just went wrong.
 *
 * Holds only the strings [FailureCopy.diagnosticFor] produces -- a code and a class name -- so
 * it cannot accumulate message text by construction.
 *
 * Deliberately **not persisted**. An app that keeps no audio and no failure history on disk
 * should not start writing a failure journal, and the case that matters is the one the user is
 * reporting: something that just happened, in this process.
 *
 * Process-scoped like [DictationController], and synchronized because failures are recorded
 * from service coroutines and read from the Settings composable.
 */
object FailureLog {
    private const val CAPACITY = 3

    private val entries = ArrayDeque<String>()

    fun record(diagnostic: String) = synchronized(this) {
        entries.addFirst(diagnostic)
        while (entries.size > CAPACITY) entries.removeLast()
    }

    /** Newest first. */
    fun recent(): List<String> = synchronized(this) { entries.toList() }

    fun clear() = synchronized(this) { entries.clear() }
}
