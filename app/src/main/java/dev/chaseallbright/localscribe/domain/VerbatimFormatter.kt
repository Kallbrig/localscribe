package dev.chaseallbright.localscribe.domain

/**
 * Informal cleanup: the speaker's own words, in a texting register.
 *
 * Deliberately deterministic rather than LLM-backed. Informal's contract is "what I said,
 * minus the stumbles", and a language model cannot be reliably talked out of tidying -- it
 * capitalises, inserts commas, and swaps words for near-synonyms ("what's going on" ->
 * "what's up"). None of that is wanted here, and rejecting it after the fact wastes a model
 * load to arrive at the same place this gets to directly.
 *
 * Whisper emits prose-formatted text: sentence case, commas, terminal punctuation. That is
 * the formatting being undone.
 *
 * What is preserved on purpose:
 * - Every word, exactly. No substitutions are possible here.
 * - Apostrophes already present. Removing them ("what's" -> "whats") would be degrading the
 *   transcript rather than declining to correct it.
 * - Capitalisation that carries meaning: names, acronyms, and "I". A word is treated as a
 *   name when Whisper capitalised it somewhere other than the start of a sentence.
 * - Sentence breaks, as a bare full stop -- "major sentence breaks" without the rest.
 */
object VerbatimFormatter {

    /** Sounds, not words. "like" and "you know" are slang and stay -- they are the register. */
    private val DISFLUENCY = Regex("""^(?:um+|uh+|erm+|ah+|mm+|hmm+)$""", RegexOption.IGNORE_CASE)
    private val SENTENCE_END = Regex("""[.!?]""")
    private val TRAILING_PUNCTUATION = charArrayOf('.', '!', '?', ',', ';', ':', '"')
    private val I_FORMS = setOf("i", "i'm", "i'll", "i've", "i'd")

    fun format(text: String, vocabulary: List<String> = emptyList()): String {
        val collapsed = TextCleanupUtils.SPACES.replace(text, " ").trim()
        if (collapsed.isEmpty()) return ""

        val tokens = TextCleanupUtils.REPEATED.replace(collapsed, "$1")
            .split(" ")
            .filter { it.isNotBlank() }

        val preserved = casingToPreserve(tokens)

        val out = StringBuilder()
        var breakPending = false
        for (token in tokens) {
            val endsSentence = SENTENCE_END.containsMatchIn(token)
            val bare = token.trim(*TRAILING_PUNCTUATION)

            if (bare.isEmpty() || DISFLUENCY.matches(bare)) {
                // A dropped token must not swallow the sentence break it was carrying.
                breakPending = breakPending || (endsSentence && out.isNotEmpty())
                continue
            }

            if (out.isNotEmpty()) out.append(if (breakPending) ". " else " ")
            out.append(preserved[bare.lowercase()] ?: bare.lowercase())
            breakPending = endsSentence
        }

        // No terminal full stop: a text message does not end in one.
        return TextCleanupUtils.restoreWords(out.toString(), vocabulary)
    }

    /**
     * Words whose capitalisation is meaningful rather than positional. Sentence-initial
     * capitals are Whisper formatting and get folded away; a capital anywhere else is a name,
     * so the first such spelling wins for every later occurrence of the same word.
     */
    private fun casingToPreserve(tokens: List<String>): Map<String, String> {
        val preserved = mutableMapOf<String, String>()
        var atSentenceStart = true

        for (token in tokens) {
            val bare = token.trim(*TRAILING_PUNCTUATION)
            if (bare.isNotEmpty()) {
                val lower = bare.lowercase()
                val isAcronym = bare.length > 1 && bare.none { it.isLowerCase() } && bare.any { it.isLetter() }
                val meaningful = lower in I_FORMS ||
                    isAcronym ||
                    (!atSentenceStart && bare.first().isUpperCase())
                if (meaningful) preserved.putIfAbsent(lower, bare)
                atSentenceStart = SENTENCE_END.containsMatchIn(token)
            } else if (SENTENCE_END.containsMatchIn(token)) {
                atSentenceStart = true
            }
        }
        return preserved
    }
}
