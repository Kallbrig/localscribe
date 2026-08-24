package dev.chaseallbright.localscribe.domain

/** Ported from the desktop project's cleanup.py so both apps apply the same heuristics. */
internal object TextCleanupUtils {
    val FILLERS = Regex("""\b(?:um+|uh+|erm+|ah+|you know|like)\b[,.]?\s*""", RegexOption.IGNORE_CASE)
    val REPEATED = Regex("""\b([\w'-]+)(?:\s+\1\b)+""", RegexOption.IGNORE_CASE)
    val SPACES = Regex("""[ \t]+""")
    private val WORDS = Regex("""[A-Za-z0-9']+""")

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

    /** Rejects conversational replies and rewrites that introduce substantial new meaning. */
    fun isFaithful(source: String, edited: String): Boolean {
        val sourceWords = contentWords(source)
        val editedWords = contentWords(edited)
        if (editedWords.isEmpty()) return sourceWords.isEmpty()
        val introduced = editedWords - sourceWords
        if (introduced.size.toDouble() / editedWords.size > 0.30) return false
        val sourceQuestions = source.count { it == '?' }
        val editedQuestions = edited.count { it == '?' }
        if (sourceQuestions > 0 && editedQuestions < sourceQuestions) return false
        return edited.length <= maxOf(source.length * 1.75, source.length + 80.0)
    }

    fun restoreWords(text: String, vocabulary: List<String>): String {
        var result = text
        for (word in vocabulary.filter { it.isNotBlank() }.sortedByDescending { it.length }) {
            result = Regex("\\b${Regex.escape(word)}\\b", RegexOption.IGNORE_CASE).replace(result, word)
        }
        return result
    }
}
