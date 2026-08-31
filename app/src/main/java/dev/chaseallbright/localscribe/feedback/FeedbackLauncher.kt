package dev.chaseallbright.localscribe.feedback

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
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

    const val DEFAULT_COPY_MESSAGE = "Report copied"

    const val LABEL_FEEDBACK = "feedback"
    const val LABEL_DEVICE_REPORT = "device-report"

    /**
     * Reports an unsupported CPU. Shared by onboarding and Settings so the two cannot drift --
     * the same reason `BackupChoicesSection` is a shared component rather than two copies.
     *
     * No free-text step: here the diagnostics *are* the report, and asking someone to compose a
     * paragraph first is exactly the friction that would stop the reports this exists to collect.
     */
    fun reportDevice(context: Context) {
        val facts = DeviceFactsCollector.collect(context)
        openIssue(
            context = context,
            title = "Unsupported device: ${facts.manufacturer} ${facts.model}",
            body = FeedbackReport.body(facts, ""),
            label = LABEL_DEVICE_REPORT
        )
    }

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
        } catch (_: ActivityNotFoundException) {
            copyReport(context, body, "No browser found — report copied instead")
        }
    }

    fun copyReport(
        context: Context,
        body: String,
        message: String = DEFAULT_COPY_MESSAGE
    ) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("LocalScribe feedback", body))
        // Android 13+ shows its own copy confirmation, so the default message would be a second
        // notice saying the same thing. The two override messages still need saying: they
        // explain why the browser did not open, which the system notice cannot.
        val systemConfirms = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            message == DEFAULT_COPY_MESSAGE
        if (!systemConfirms) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}
