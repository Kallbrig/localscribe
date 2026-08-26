package dev.chaseallbright.localscribe.domain

/**
 * Deterministic cleanup.
 *
 * For [CleanupMode.INFORMAL] this is not a fallback but the intended path -- see
 * [VerbatimFormatter]. For the other modes it is what runs when no GGUF cleanup model is
 * loaded, or when the model's output was rejected as unfaithful.
 */
class RuleBasedCleaner : Cleaner {
    override fun clean(text: String, mode: CleanupMode, vocabulary: List<String>): CleanResult {
        if (mode == CleanupMode.INFORMAL) {
            return CleanResult(VerbatimFormatter.format(text, vocabulary), CleanupBackend.VERBATIM)
        }

        var value = TextCleanupUtils.SPACES.replace(text, " ").trim()
        value = TextCleanupUtils.REPEATED.replace(value, "$1")
        value = TextCleanupUtils.FILLERS.replace(value, "")
        value = Regex("""\s+([,.;!?])""").replace(value, "$1")
        value = Regex("""([.!?])(?=\S)""").replace(value, "$1 ")
        if (value.isNotEmpty()) {
            value = value[0].uppercaseChar() + value.substring(1)
            if (mode in setOf(CleanupMode.STANDARD, CleanupMode.BUSINESS) && value.last() !in ".!?") {
                value += "."
            }
        }
        return CleanResult(TextCleanupUtils.restoreWords(value, vocabulary), CleanupBackend.RULES)
    }
}
