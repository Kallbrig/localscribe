package dev.chaseallbright.localscribe.domain

/**
 * How much rewriting a mode may do before its output stops being a faithful edit of the
 * dictation and starts being the model's own composition.
 *
 * Two modes, and they differ in who does the work rather than in how much latitude they get.
 * Informal never touches a model: it preserves the speaker's register and only drops
 * disfluencies. Standard sends the text to the local model with a brief that fixes grammar and
 * collapses repeats without replacing words. Both keep the speaker's exact word choices, so
 * neither is allowed to rewrite.
 *
 * The vocabulary budget is what enforces that. It is tight in both modes, so a synonym swap is
 * rejected and the output falls back to the deterministic [RuleBasedCleaner].
 *
 * The guards that stop a model *answering* the dictation rather than editing it are not part of
 * this policy; they apply to every mode unconditionally. See [TextCleanupUtils].
 */
data class FaithfulnessPolicy(
    /** Share of the edited text's content words that the dictation never contained. */
    val maxIntroducedContentWordRatio: Double,
    /** How much longer than the dictation the edit may be. */
    val maxLengthRatio: Double,
    /**
     * Absolute headroom in characters, so `max(ratio, slack)` never punishes a short dictation
     * for terminal punctuation and capitalisation alone.
     */
    val maxLengthSlack: Int
)

private const val EXAMPLE_DICTATION =
    "um I'm gonna call you tomorrow, but I haven't haven't checked the schedule yet"

enum class CleanupMode(
    val displayName: String,
    /** Shown in Settings. */
    val description: String,
    /**
     * Whether the mode runs the local LLM. Informal does not: its contract is the speaker's
     * register, and [VerbatimFormatter] delivers that deterministically. A model only gets
     * rejected for tidying it, so loading one would be wasted work.
     */
    val usesLlm: Boolean,
    /** Sent to the model as the mode's editing brief. Unused when [usesLlm] is false. */
    val instruction: String,
    /** The mode's target output for [EXAMPLE_DICTATION], used as a one-shot example. */
    val exampleOutput: String,
    val policy: FaithfulnessPolicy
) {
    INFORMAL(
        displayName = "Informal",
        description = "Your words, as you said them. Texting register, no tidying.",
        usesLlm = false,
        instruction = "",
        exampleOutput = "um I'm gonna call you tomorrow, but I haven't haven't checked the schedule yet",
        // Tightest policy in the app. Informally, "faithful" means the words survived, so any
        // introduced content word is a rewrite rather than an edit.
        policy = FaithfulnessPolicy(0.05, maxLengthRatio = 1.30, maxLengthSlack = 40)
    ),
    STANDARD(
        displayName = "Standard",
        description = "Grammar and punctuation fixed. Your exact words kept.",
        usesLlm = true,
        instruction = "Fix grammar, punctuation and capitalisation, and remove filler and " +
            "stumbles. Collapse words or phrases repeated twice in a row. Keep the speaker's " +
            "exact word choices -- including slang and contractions -- and never swap a word " +
            "for a synonym. Change how it is punctuated and trimmed, not which words are used.",
        exampleOutput = "I'm gonna call you tomorrow, but I haven't checked the schedule yet.",
        // Tight: correcting grammar should barely introduce vocabulary at all. A looser budget
        // is what lets a model drift from tidying into rewriting.
        policy = FaithfulnessPolicy(0.15, maxLengthRatio = 1.30, maxLengthSlack = 40)
    );

    val exampleDictation: String get() = EXAMPLE_DICTATION

    companion object {
        /**
         * Resolves a stored mode name. Names are matched against the enum, so a name this build
         * does not offer -- written by an older version or a newer one -- resolves to
         * [STANDARD], the default, rather than becoming an invisible selection or failing.
         */
        fun fromStoredName(name: String?): CleanupMode =
            entries.firstOrNull { it.name == name } ?: STANDARD
    }
}
