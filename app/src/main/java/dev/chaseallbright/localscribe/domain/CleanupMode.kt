package dev.chaseallbright.localscribe.domain

/**
 * How much rewriting a mode is allowed to do before its output stops being a faithful edit
 * of the dictation and starts being the model's own composition.
 *
 * A flat rule cannot serve every mode. The four modes are a deliberate gradient in *what is
 * allowed to change*, and the vocabulary budget is what enforces it:
 *
 * | Mode     | Grammar & punctuation | Word choice        |
 * |----------|-----------------------|--------------------|
 * | Informal | left alone            | exact              |
 * | Casual   | corrected             | exact              |
 * | Standard | corrected             | tightened          |
 * | Business | corrected             | rewritten/polished |
 *
 * The guards that stop the model *answering* the dictation rather than editing it are not
 * part of this policy; they apply to every mode unconditionally. See [TextCleanupUtils].
 */
data class FaithfulnessPolicy(
    /** Share of the edited text's content words that may be words the dictation never used. */
    val maxIntroducedContentWordRatio: Double,
    /** Ceiling on how much longer the edit may be than the dictation. */
    val maxLengthRatio: Double,
    /**
     * Absolute headroom in characters, applied as `max(len * ratio, len + slack)`.
     *
     * Short dictations need some, or punctuation and capitalisation alone can breach a ratio.
     * But it has to vary per mode: a flat 80 characters swamps the ratio entirely at ordinary
     * dictation lengths -- at 74 characters it puts the cap at 154 either way, which made
     * business's stricter ratio inert and identical to standard's.
     */
    val maxLengthSlack: Int
)

private const val EXAMPLE_DICTATION =
    "um so i was gonna call you yesterday but i totally forgot sorry about that"

enum class CleanupMode(
    val displayName: String,
    /** Shown in Settings. */
    val description: String,
    /**
     * Whether this mode runs the local LLM at all. Informal does not: its contract is the
     * speaker's own words, which [VerbatimFormatter] delivers deterministically. A model
     * cannot be reliably talked out of tidying, and rejecting its tidying after the fact
     * costs a model load to reach the same result.
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
        exampleOutput = "so i was gonna call you yesterday but i totally forgot sorry about that",
        // Retained so isFaithful stays meaningful if informal is ever routed through a model
        // again. Effectively zero: informal introducing a word at all means it rewrote.
        policy = FaithfulnessPolicy(0.05, maxLengthRatio = 1.15, maxLengthSlack = 20)
    ),
    CASUAL(
        displayName = "Casual",
        description = "Grammar and punctuation fixed. Your exact words kept.",
        usesLlm = true,
        instruction = "Fix grammar, punctuation and capitalisation, and remove filler and " +
            "stumbles. Keep the speaker's exact word choices -- including slang and " +
            "contractions -- and never swap a word for a synonym. Change how it is punctuated, " +
            "not which words are used.",
        exampleOutput = "So I was gonna call you yesterday, but I totally forgot. Sorry about that.",
        // Tight: casual correcting grammar should barely introduce vocabulary at all, and what
        // it does introduce is mostly function words, which are not counted.
        policy = FaithfulnessPolicy(0.15, maxLengthRatio = 1.30, maxLengthSlack = 40)
    ),
    STANDARD(
        displayName = "Standard",
        description = "Grammar fixed and wording tightened up.",
        usesLlm = true,
        instruction = "Correct grammar, punctuation and capitalisation, remove filler and " +
            "stumbles, and tighten loose or repetitive wording. Keep the speaker's tone and " +
            "meaning; this is a tidy-up, not a rewrite.",
        exampleOutput = "So I was going to call you yesterday, but I completely forgot. Sorry about that.",
        policy = FaithfulnessPolicy(0.35, maxLengthRatio = 1.50, maxLengthSlack = 60)
    ),
    BUSINESS(
        displayName = "Business",
        description = "Polished and concise professional phrasing.",
        usesLlm = true,
        instruction = "Rewrite as polished professional communication. Replace casual phrasing and " +
            "slang with precise professional wording, remove hedging and filler, and make it " +
            "concise. Keep the same meaning, intent and facts -- change how it is said, never " +
            "what is said.",
        exampleOutput = "I intended to call you yesterday, but it slipped my mind. My apologies.",
        // Permissive on vocabulary by design -- that is the mode's whole job -- and held tight
        // on length instead, since "polished and concise" should never grow the text.
        policy = FaithfulnessPolicy(0.70, maxLengthRatio = 1.20, maxLengthSlack = 20)
    );

    /** The one-shot pair shown to the model for this mode. */
    val exampleDictation: String get() = EXAMPLE_DICTATION
}
