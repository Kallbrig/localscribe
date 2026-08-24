package dev.chaseallbright.localscribe.domain

/** Deterministic offline fallback used when no GGUF cleanup model is loaded. */
class RuleBasedCleaner : Cleaner {
    override fun clean(text: String, mode: CleanupMode, vocabulary: List<String>): CleanResult {
        var value = TextCleanupUtils.SPACES.replace(text, " ").trim()
        value = TextCleanupUtils.REPEATED.replace(value, "$1")
        if (mode != CleanupMode.INFORMAL) {
            value = TextCleanupUtils.FILLERS.replace(value, "")
        }
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
