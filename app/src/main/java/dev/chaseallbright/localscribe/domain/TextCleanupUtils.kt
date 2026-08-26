package dev.chaseallbright.localscribe.domain

/** Ported from the desktop project's cleanup.py so both apps apply the same heuristics. */
internal object TextCleanupUtils {
    val FILLERS = Regex("""\b(?:um+|uh+|erm+|ah+|you know|like)\b[,.]?\s*""", RegexOption.IGNORE_CASE)
    val REPEATED = Regex("""\b([\w'-]+)(?:\s+\1\b)+""", RegexOption.IGNORE_CASE)
    val SPACES = Regex("""[ \t]+""")
    private val WORDS = Regex("""[A-Za-z0-9']+""")
    private val NUMBERS = Regex("""\d+""")

    /**
     * Below this many content words the introduced-vocabulary ratio stops discriminating.
     * "Hi, how are you?" -> "I'm fine, how about you?" scores 0.50 and must be rejected, while
     * a legitimate business rewrite of a normal sentence scores 0.67 and must be accepted --
     * no flat threshold separates them. Source length does: a three-word denominator is noise,
     * and short dictations are exactly where a model is most likely to reply rather than edit.
     * So a relaxed mode policy only applies once there is enough text to measure.
     */
    private const val MIN_CONTENT_WORDS_FOR_RELAXED_POLICY = 8

    private val FUNCTION_WORDS = setOf(
        "a", "an", "and", "are", "as", "at", "be", "been", "being", "but", "by",
        "can", "could", "did", "do", "does", "for", "from", "had", "has", "have",
        "in", "is", "may", "might", "must", "of", "on", "or", "should", "the",
        "to", "was", "were", "will", "with", "would"
    )

    private fun contentWords(text: String): Set<String> {
        val words = mutableSetOf<String>()
        for (match in WORDS.findAll(text.lowercase())) {
            for (word in match.value.replace("'", " ").split(" ")) {
                if (word.length > 1 && word !in FUNCTION_WORDS) {
                    words.add(word)
                }
            }
        }
        return words
    }

    private fun numbers(text: String): Set<String> =
        NUMBERS.findAll(text).map { it.value.trimStart('0').ifEmpty { "0" } }.toSet()

    /**
     * Whether [edited] is a faithful edit of [source] under [mode]'s policy.
     *
     * Three guards apply to every mode regardless of policy, because they are what stop a
     * small instruction-tuned model from replying to the dictation instead of editing it:
     * a dictated question stays a question, no figure appears that was never dictated, and
     * the text cannot balloon. The vocabulary threshold is the only part that varies -- it
     * has to, or business mode's whole job reads as unfaithful.
     */
    fun isFaithful(source: String, edited: String, mode: CleanupMode = CleanupMode.STANDARD): Boolean {
        val sourceWords = contentWords(source)
        val editedWords = contentWords(edited)
        if (editedWords.isEmpty()) return sourceWords.isEmpty()

        // Invariant: never invent a figure. Cheap, and a far better hallucination signal than
        // vocabulary overlap, which is why relaxing the vocabulary rule below stays safe.
        if (!numbers(source).containsAll(numbers(edited))) return false

        // Invariant: a dictated question must survive as a question.
        val sourceQuestions = source.count { it == '?' }
        if (sourceQuestions > 0 && edited.count { it == '?' } < sourceQuestions) return false

        // Invariant: an edit is not an expansion.
        val policy = mode.policy
        val maxLength = maxOf(source.length * policy.maxLengthRatio, source.length + policy.maxLengthSlack.toDouble())
        if (edited.length > maxLength) return false

        // minOf, not a substitution: a mode stricter than standard stays stricter on short text.
        val maxIntroduced = if (sourceWords.size < MIN_CONTENT_WORDS_FOR_RELAXED_POLICY) {
            minOf(policy.maxIntroducedContentWordRatio, CleanupMode.STANDARD.policy.maxIntroducedContentWordRatio)
        } else {
            policy.maxIntroducedContentWordRatio
        }

        val introduced = editedWords - sourceWords
        return introduced.size.toDouble() / editedWords.size <= maxIntroduced
    }

    fun restoreWords(text: String, vocabulary: List<String>): String {
        var result = text
        for (word in vocabulary.filter { it.isNotBlank() }.sortedByDescending { it.length }) {
            result = Regex("\\b${Regex.escape(word)}\\b", RegexOption.IGNORE_CASE).replace(result, word)
        }
        return result
    }
}
