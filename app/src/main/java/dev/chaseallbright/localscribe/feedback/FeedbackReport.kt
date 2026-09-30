package dev.chaseallbright.localscribe.feedback

import java.net.URLEncoder

/**
 * Everything a feedback report may contain.
 *
 * **The type is the privacy guarantee.** There is no field here capable of holding a transcript,
 * a vocabulary word, an audio sample, or an exception message, so a report cannot contain one --
 * enforced by the data model rather than by remembering to sanitise. [recentFailures] holds only
 * the code/class-name strings `FailureCopy.diagnosticFor` produces.
 */
data class DeviceFacts(
    val appVersion: String,
    val versionCode: Int,
    val androidRelease: String,
    val sdkInt: Int,
    val manufacturer: String,
    val model: String,
    val abi: String,
    val cpuVerdict: String,
    val totalRamGb: Double,
    val whisperTier: String,
    val whisperDownloaded: Boolean,
    val cleanupTier: String,
    val cleanupDownloaded: Boolean,
    val cleanupMode: String,
    val recordingLimit: String,
    val recentFailures: List<String>
)

/**
 * Builds the markdown body of a feedback issue and the prefilled `issues/new` URL that carries
 * it. Pure, so what a report can and cannot contain is decided by tested code rather than by the
 * UI that happens to call it.
 *
 * The app never posts this. It hands the URL to the browser and the user presses Submit, which
 * is what keeps the privacy posture's "one network call site, one host" true.
 */
object FeedbackReport {

    const val REPO_URL = "https://github.com/Kallbrig/localscribe"
    const val ISSUE_BASE_URL = "$REPO_URL/issues/new"

    /** A URL cannot carry an essay; past this the description is cut. */
    const val MAX_USER_TEXT = 2000

    /**
     * Browsers and servers cap query strings, and the failure mode is silent truncation -- a
     * report that looks complete and is not. Past this, [issueUrl] refuses and the caller falls
     * back to the clipboard.
     */
    const val MAX_URL_LENGTH = 6000

    /**
     * Room left for everything in the URL that is not the body: the base URL, the encoded
     * title, and the label. Generous on purpose -- being wrong here costs a clipboard fallback,
     * whereas being tight costs a truncated issue.
     */
    private const val URL_OVERHEAD_RESERVE = 300

    fun body(facts: DeviceFacts, userText: String): String {
        val diagnostics = diagnostics(facts)
        // Built separately and concatenated, NOT interpolated into one raw string. Kotlin
        // interpolates before trimIndent() runs, so a user's second line -- at indent zero --
        // would drag the common indent to zero and leave every template line with its twelve
        // leading spaces. Four spaces is an indented code block in GitHub markdown, so the
        // whole table used to render as literal text the moment anyone pressed Enter.
        return fitDescription(userText, diagnostics) + "\n\n" + diagnostics
    }

    /**
     * Truncates the description so the finished report still fits a URL.
     *
     * [MAX_USER_TEXT] is a *character* cap, but the budget that actually binds is the *encoded*
     * length: an ASCII character costs one, a Cyrillic one three, a CJK character or emoji up
     * to twelve. A character cap alone would let an English user write three times as much as
     * a Russian one before either noticed.
     *
     * This happens inside [body] rather than at the URL, deliberately: the Settings screen
     * previews exactly this string, so shortening later would send less than the user was
     * shown. Truncation stays visible.
     */
    private fun fitDescription(userText: String, diagnostics: String): String {
        val trimmed = userText.trim()
        if (trimmed.isEmpty()) return "_No description given._"

        var candidate = trimmed.takeChars(MAX_USER_TEXT)
        while (candidate.isNotEmpty()) {
            val marked = if (candidate.length < trimmed.length) {
                candidate + "\n\n_(truncated)_"
            } else {
                candidate
            }
            val encodedLength = encode(marked + "\n\n" + diagnostics).length
            if (encodedLength + URL_OVERHEAD_RESERVE <= MAX_URL_LENGTH) return marked
            candidate = candidate.takeChars(candidate.length / 2)
        }
        return "_(description too long to include)_"
    }

    private fun diagnostics(facts: DeviceFacts): String {
        val failures = facts.recentFailures.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "none"
        return """
            ### Diagnostics

            | | |
            |---|---|
            | App | ${facts.appVersion} (${facts.versionCode}) |
            | Android | ${facts.androidRelease} (API ${facts.sdkInt}) |
            | Device | ${facts.manufacturer} ${facts.model} |
            | ABI | ${facts.abi} |
            | CPU | ${facts.cpuVerdict} |
            | RAM | ${facts.totalRamGb} GB |
            | Speech model | ${facts.whisperTier} (${downloadState(facts.whisperDownloaded)}) |
            | Cleanup model | ${facts.cleanupTier} (${downloadState(facts.cleanupDownloaded)}) |
            | Cleanup style | ${facts.cleanupMode} |
            | Recording limit | ${facts.recordingLimit} |
            | Recent failures | $failures |

            _LocalScribe added nothing beyond the diagnostics above: no transcript text, no
            vocabulary, no audio. Anything else in this issue was typed by the reporter._
        """.trimIndent()
    }

    /** Null when the assembled URL would exceed [MAX_URL_LENGTH]. */
    fun issueUrl(title: String, body: String, label: String): String? {
        val url = ISSUE_BASE_URL +
            "?title=" + encode(title) +
            "&body=" + encode(body) +
            "&labels=" + encode(label)
        return url.takeIf { it.length <= MAX_URL_LENGTH }
    }

    /**
     * Like [take], but never cuts between the two halves of a surrogate pair. A lone surrogate
     * survives to the URL as a literal "?" and renders as a tofu glyph -- harmless, but a
     * pointless way to mangle the last emoji in a truncated report.
     */
    private fun String.takeChars(count: Int): String {
        val cut = count.coerceIn(0, length)
        val safe = if (cut > 0 && this[cut - 1].isHighSurrogate()) cut - 1 else cut
        return take(safe)
    }

    private fun downloadState(downloaded: Boolean) = if (downloaded) "downloaded" else "not downloaded"

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
