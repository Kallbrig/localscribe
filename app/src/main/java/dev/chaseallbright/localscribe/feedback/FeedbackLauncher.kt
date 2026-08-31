package dev.chaseallbright.localscribe.feedback

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * Hands a prefilled issue URL to the browser. The app never posts anything itself -- the user
 * reviews the issue on GitHub and submits it, which is what keeps LocalScribe's only network
 * call site the model downloader.
 *
 * Degrades to its own fallback rather than failing: a URL too long to carry, or a device with no
 * browser, copies the report to the clipboard instead.
 */
object FeedbackLauncher {

    const val LABEL_FEEDBACK = "feedback"
    const val LABEL_DEVICE_REPORT = "device-report"

    fun openIssue(context: Context, title: String, body: String, label: String) {
        val url = FeedbackReport.issueUrl(title, body, label)
        if (url == null) {
            copyReport(context, body, "Report too long to open in the browser — copied instead")
            return
        }
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: ActivityNotFoundException) {
            copyReport(context, body, "No browser found — report copied instead")
        }
    }

    fun copyReport(
        context: Context,
        body: String,
        message: String = "Report copied"
    ) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("LocalScribe feedback", body))
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
