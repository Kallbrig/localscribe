package dev.chaseallbright.localscribe.domain

enum class CleanupMode(val promptHint: String) {
    INFORMAL("Keep the speaker's voice and slang. Remove stumbles only."),
    CASUAL("Make it friendly and concise with natural conversational grammar."),
    STANDARD("Correct grammar and punctuation while preserving meaning and tone."),
    BUSINESS("Rewrite as polished, concise professional communication.")
}
