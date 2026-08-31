# Feedback and User-Safe Errors Design

**Date:** 2026-08-31
**Status:** Approved
**Scope:** Stop raw exception text reaching the user, and add a feedback path that files a GitHub
issue without any backend, database, or network call from the app.

## Problem

### Raw exception text is shown to users

Three sites display whatever string an exception happened to carry:

| Site | Code |
|---|---|
| `DictationForegroundService.kt:208` | `Error(e.message ?: "Dictation failed")` |
| `SettingsScreen.kt:88` | `toast(it.message ?: "Export failed.")` |
| `SettingsScreen.kt:105` | `toast(it.message ?: "Import failed.")` |

The dictation one became far more visible in `v0.2.0-beta.3`, which started toasting `Error`
states that were previously set and never rendered. A user can now be shown, for example,
`database or disk is full (code 13 SQLITE_FULL[13])` — a string written by SQLite for a developer.

A blanket ban on `e.message` would be wrong. Some exceptions carry **copy deliberately written for
users**, and losing it would make the app *less* clear:

- `TranscriptArchive.UnsupportedArchive` — "This file is not a LocalScribe export."
- `ModelSession.loadWhisper` — "Base (English) speech model isn't downloaded. Open LocalScribe to
  download it."

So the fix is a seam that distinguishes the two, not a filter.

### There is no way for a user to report anything

LocalScribe has no telemetry by design, no crash reporting, and no feedback channel. That is
mostly a feature, but it leaves one concrete gap the previous session's work made explicit:
`HANDOFF.md` records that dual library variants for pre-2018 CPUs are "worth doing only if someone
reports owning such a device" — and there is currently no way for such a person to say so. The
population most affected by the newest change is the one the project cannot hear from.

## Decisions (agreed in brainstorming)

1. **Prefilled GitHub issue, opened in the browser, plus a copy-to-clipboard fallback.** The app
   builds a URL and hands it to the browser with `ACTION_VIEW`; the user reviews the issue on
   GitHub and presses Submit themselves. **The app itself makes no network call**, so the privacy
   posture's "one network call site (`ModelDownloader`), one host (`huggingface.co`)" stays
   literally true. The copy fallback covers anyone without a GitHub account. Rejected: embedding a
   GitHub token to file issues via the API — it would be extractable from a public APK and abusable
   as a spam vector, and it would make the app a network client for something other than models.
2. **Diagnostics carry an exception's class name and our own failure code, never its message.**
   `IllegalStateException / E-DICT`, not the string. This is an *ironclad* guarantee that no
   dictated text can ride along, because no third-party string is ever placed in a report. The raw
   message remains available in logcat for anyone running `adb`. Chosen over including the message
   because the app's central promise is that dictated text never leaves the device, and a report is
   by definition a thing that leaves; a guarantee beats a best-effort assurance the user would have
   to audit by reading the payload.
3. **Two entry points: a Feedback section in Settings, and a "Report this device" button on the
   unsupported-device notice.** The second exists specifically to close the gap above.

## Design

### `UserFacingMessage` — the seam

`domain/UserFacingMessage.kt`.

```kotlin
/** An exception whose message was written for a user and may be displayed verbatim. */
interface UserFacingMessage

/** Throw when the message is deliberate user copy. */
class UserFacingException(message: String) : Exception(message), UserFacingMessage
```

`TranscriptArchive.UnsupportedArchive` gains `UserFacingMessage`; it already extends
`IllegalArgumentException` and its messages are already user copy. `ModelSession`'s two
`error(...)` calls whose text is user copy become `throw UserFacingException(...)`.

An exception is shown verbatim **only** if it is marked. The default is to hide.

### `FailureCopy` — the pure decision

`domain/FailureCopy.kt`. No Android imports.

```kotlin
enum class FailureContext(val code: String, val generic: String) {
    DICTATION("E-DICT", "Dictation failed. Nothing was inserted."),
    EXPORT("E-EXPORT", "Export failed."),
    IMPORT("E-IMPORT", "Import failed."),
}

object FailureCopy {
    /** The message to show a user. */
    fun userMessageFor(context: FailureContext, error: Throwable): String

    /** The class name to record for a report -- never shown to the user. */
    fun diagnosticFor(context: FailureContext, error: Throwable): String
}
```

`userMessageFor` returns the throwable's message when it is a `UserFacingMessage` **and** its
message is non-blank; otherwise `"${context.generic} (${context.code})"`. The code is what makes an
otherwise generic message actionable in a report.

`diagnosticFor` returns `"${context.code}/${error::class.simpleName}"` — e.g. `E-DICT/SQLiteFullException`.
Anonymous and lambda classes can yield a null `simpleName`, so that case falls back to `"Unknown"`.

### `FailureLog` — recent failures, in memory

`domain/FailureLog.kt`. A process-scoped holder of the **last three** diagnostic strings, newest
first. Deliberately **not persisted**: an app that retains no audio and no failure history on disk
should not start writing a failure journal, and a report covering the current process is the case
that matters — the user is reporting something that just happened. Holds no message text by
construction, only the strings `diagnosticFor` produces.

### `FeedbackReport` — pure report building

`feedback/FeedbackReport.kt`. No Android imports.

```kotlin
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

object FeedbackReport {
    const val ISSUE_BASE_URL = "https://github.com/Kallbrig/localscribe/issues/new"
    const val MAX_USER_TEXT = 2000
    const val MAX_URL_LENGTH = 6000

    fun body(facts: DeviceFacts, userText: String): String
    fun issueUrl(title: String, body: String, label: String): String
}
```

**The type is the privacy guarantee.** `DeviceFacts` has no field capable of holding a transcript,
vocabulary word, or exception message, so a report cannot contain one — this is enforced by the
data model rather than by remembering to sanitise.

`body` renders markdown: the user's text, then a `Diagnostics` section as a table. User text is
truncated to `MAX_USER_TEXT` with a marker, since a URL cannot carry an essay.

`issueUrl` percent-encodes title, body and label, and returns `null` if the assembled URL still
exceeds `MAX_URL_LENGTH` — the caller then falls back to copy-to-clipboard rather than launching a
URL the browser or GitHub would truncate silently. Silent truncation is the failure mode worth
designing against: it would produce a report that looks complete and is not.

### `DeviceFactsCollector` — the thin glue

`feedback/DeviceFactsCollector.kt`. Reads `BuildConfig`, `Build`, `DeviceCpu`, `ModelManager` and
`AppPreferences` and returns a `DeviceFacts`. Everything untestable lives here; it makes no
decisions.

### UI

**Settings — a `Feedback` section.** A multiline text field, the rendered report shown read-only in
a scrollable box so the user sees exactly what will be sent before anything happens, then two
buttons: *Open GitHub issue* and *Copy report*. Showing the payload is the same reasoning that made
the backup screen name what travels.

**The unsupported-device notice — a `Report this device` button.** `UnsupportedDeviceNotice` gains
an `onReport: () -> Unit`. There the diagnostics *are* the report, so it opens a prefilled issue
directly with no free-text step; requiring someone to compose a paragraph is exactly the friction
that would stop the reports this exists to collect. Both `OnboardingScreen` and `SettingsScreen`
wire it to the same launcher.

**Titles and labels.** The Settings path uses `Feedback from <appVersion>` with label `feedback`;
the device path uses `Unsupported device: <manufacturer> <model>` with label `device-report`. The
device title carries the identifying fact so a maintainer can triage a list of these without
opening each one, which is the whole reason that path exists. Both labels are created in the repo,
since GitHub silently drops a label that does not exist — creating them is what makes the parameter
meaningful rather than decorative.

### Failure handling of the feedback path itself

No browser, or no activity able to handle the intent, throws `ActivityNotFoundException`. Caught,
and the report is copied to the clipboard with a toast saying so — the feature degrades to its own
fallback rather than crashing on a device with no browser.

## Testing

All logic is in pure units:

- `FailureCopy.userMessageFor`: a `UserFacingMessage` passes through; a blank-message
  `UserFacingMessage` falls back to generic; an ordinary exception never leaks its message, and the
  result carries the context code. A test asserts a realistic SQLite message does not appear in the
  output.
- `FailureCopy.diagnosticFor`: class name plus code; null `simpleName` yields `Unknown`.
- `FailureLog`: caps at three, newest first, and clears.
- `FeedbackReport.body`: contains every fact; is stable; truncates over-long user text.
- `FeedbackReport.issueUrl`: percent-encodes spaces, newlines, `#` and `&` correctly; returns
  `null` past the length cap.

### What cannot be verified

The Compose surfaces and the `ACTION_VIEW` launch have no instrumented coverage — the project has
no `androidTest` source set and no Robolectric, which the handoff already records. The pure units
under them are tested; the wiring is not. The prefilled-URL round trip against real GitHub is not
exercised by any test.

## Out of scope

- Persisting failures across process death.
- Any in-app network call, GitHub API use, or embedded token.
- Attaching logs, transcripts, or audio.
- A crash handler. This reports *handled* failures; an uncaught `Error` (OOM, `UnsatisfiedLinkError`)
  still kills the process silently, as recorded in the handoff.
