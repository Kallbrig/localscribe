package dev.chaseallbright.localscribe.feedback

/**
 * When to ask for a GitHub star: after every [INTERVAL] completed dictations, until the user
 * either goes to the repo or says not to ask. A use is a dictation whose text was inserted --
 * taps, cancels, failures and clipboard fallbacks do not count.
 */
data class StarPromptPolicy(
    val uses: Int = 0,
    val nextAt: Int = INTERVAL,
    val finished: Boolean = false
) {
    val due: Boolean get() = !finished && uses >= nextAt

    fun recordUse(): StarPromptPolicy = if (finished) this else copy(uses = uses + 1)

    /** "Remind me later", or the card dismissed by tapping outside it. */
    fun remindLater(): StarPromptPolicy = copy(nextAt = uses + INTERVAL)

    /** "Take me there" or "Don't remind me": never ask again. */
    fun finish(): StarPromptPolicy = copy(finished = true)

    companion object {
        const val INTERVAL = 100
    }
}
