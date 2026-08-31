package dev.chaseallbright.localscribe.domain

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Where a failure happened, carrying the copy and the short code shown in its place.
 *
 * [offlineHint] is appended to [generic] only when the failing [Throwable] is one of the
 * connectivity types [FailureCopy.isConnectivityFailure] recognizes -- a context that declares
 * no hint (`null`) never gets one, no matter what the error is.
 */
enum class FailureContext(val code: String, val generic: String, val offlineHint: String? = null) {
    DICTATION("E-DICT", "Dictation failed. Nothing was inserted."),
    EXPORT("E-EXPORT", "Export failed."),
    IMPORT("E-IMPORT", "Import failed."),
    DOWNLOAD("E-DOWNLOAD", "Download failed.", "Check your connection."),
}

/**
 * Decides what a user is shown when something fails, and what is recorded about it.
 *
 * Pure and total: every branch is unit-tested, and no path can place a third-party string in
 * front of a user or into a report.
 */
object FailureCopy {

    /**
     * The message to show. A [UserFacingMessage] with real content passes through; everything
     * else becomes generic copy plus [FailureContext.code], which is what makes an otherwise
     * unspecific message actionable in a bug report.
     */
    fun userMessageFor(context: FailureContext, error: Throwable): String {
        val message = error.message
        if (error is UserFacingMessage && !message.isNullOrBlank()) return message
        val hint = context.offlineHint?.takeIf { isConnectivityFailure(error) }
        val body = if (hint == null) context.generic else "${context.generic} $hint"
        return "$body (${context.code})"
    }

    /**
     * Deliberately these four types rather than IOException: ModelDownloadException extends
     * IOException and covers "HTTP 404" and "could not move completed download into place",
     * where telling someone to check their connection would be actively misleading.
     */
    private fun isConnectivityFailure(error: Throwable): Boolean =
        error is UnknownHostException ||
            error is ConnectException ||
            error is SocketTimeoutException ||
            error is SSLException

    /**
     * What to record for a report. Deliberately the exception's *class*, never its message:
     * a report leaves the device, and no third-party string is allowed to travel with it.
     * The message stays in logcat for anyone running `adb`.
     */
    fun diagnosticFor(context: FailureContext, error: Throwable): String {
        // Anonymous and lambda classes report a null simpleName.
        val className = error::class.simpleName?.takeIf { it.isNotBlank() } ?: "Unknown"
        return "${context.code}/$className"
    }
}
