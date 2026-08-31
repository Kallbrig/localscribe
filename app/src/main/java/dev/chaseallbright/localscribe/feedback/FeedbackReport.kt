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

    const val ISSUE_BASE_URL = "https://github.com/Kallbrig/localscribe/issues/new"

    /** A URL cannot carry an essay; past this the description is cut. */
    const val MAX_USER_TEXT = 2000

    /**
     * Browsers and servers cap query strings, and the failure mode is silent truncation -- a
     * report that looks complete and is not. Past this, [issueUrl] refuses and the caller falls
     * back to the clipboard.
     */
    const val MAX_URL_LENGTH = 6000

    fun body(facts: DeviceFacts, userText: String): String {
        val trimmed = userText.trim()
        val description = when {
            trimmed.isEmpty() -> "_No description given._"
            trimmed.length > MAX_USER_TEXT ->
                trimmed.take(MAX_USER_TEXT) + "\n\n_(truncated)_"
            else -> trimmed
        }
        val failures = facts.recentFailures.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "none"

        return """
            $description

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

            _No transcript text, vocabulary, or audio is included in this report._
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

    private fun downloadState(downloaded: Boolean) = if (downloaded) "downloaded" else "not downloaded"

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
