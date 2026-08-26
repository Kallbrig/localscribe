package dev.chaseallbright.localscribe.domain

/**
 * How much rewriting a mode is allowed to do before its output stops being a faithful edit
 * of the dictation and starts being the model's own composition.
 *
 * A flat rule cannot serve every mode: informal is supposed to leave the speaker's words
 * alone, while business is supposed to replace casual phrasing with professional wording --
 * which necessarily introduces new vocabulary. One shared threshold meant business rewrites
 * were rejected as unfaithful and silently fell back to the mode-blind rule cleaner, so
 * informal and business produced near-identical text.
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
    /** Sent to the model as the mode's editing brief. */
    val instruction: String,
    /** The mode's target output for [EXAMPLE_DICTATION], used as a one-shot example. */
    val exampleOutput: String,
    val policy: FaithfulnessPolicy
) {
    INFORMAL(
        displayName = "Informal",
        description = "Keeps your slang and voice. Removes stumbles only.",
        instruction = "Keep the speaker's own words, slang and casual grammar exactly as they are. " +
            "Remove only filler, stumbles and repeated words, then fix capitalisation and " +
            "punctuation. Do not make the wording more formal and do not shorten it.",
        exampleOutput = "So I was gonna call you yesterday but I totally forgot, sorry about that.",
        // Tight: informal changing vocabulary at all is a sign the model is rewriting.
        policy = FaithfulnessPolicy(0.20, maxLengthRatio = 1.30, maxLengthSlack = 40)
    ),
    CASUAL(
        displayName = "Casual",
        description = "Friendly and readable, like a message to a friend.",
        instruction = "Rewrite as a friendly, natural message. Fix grammar, tighten wordiness and " +
            "drop filler, but keep contractions and a warm conversational tone. Stay close to " +
            "the speaker's meaning and keep it about the same length or shorter.",
        exampleOutput = "I was going to call you yesterday but totally forgot -- sorry about that!",
        policy = FaithfulnessPolicy(0.45, maxLengthRatio = 1.40, maxLengthSlack = 40)
    ),
    STANDARD(
        displayName = "Standard",
        description = "Correct grammar and punctuation, tone untouched.",
        instruction = "Correct grammar, punctuation and capitalisation. Remove filler and stumbles. " +
            "Otherwise keep the speaker's wording and tone as they are.",
        exampleOutput = "So I was going to call you yesterday, but I totally forgot. Sorry about that.",
        // Unchanged from the original flat behaviour.
        policy = FaithfulnessPolicy(0.30, maxLengthRatio = 1.75, maxLengthSlack = 80)
    ),
    BUSINESS(
        displayName = "Business",
        description = "Polished and concise professional phrasing.",
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
