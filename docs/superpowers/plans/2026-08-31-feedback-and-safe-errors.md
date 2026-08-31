# Feedback and User-Safe Errors Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop raw exception text reaching users, and add a feedback path that files a prefilled GitHub issue with no backend and no network call from the app. Ship as `v0.2.0-beta.4`.

**Architecture:** A marker interface (`UserFacingMessage`) separates copy written for users from incidental exception strings; a pure `FailureCopy` decides what to show and what to record. A pure `FeedbackReport` renders a markdown body from a `DeviceFacts` value — a type with nowhere to put a transcript — and builds a percent-encoded `issues/new` URL. Thin Android glue collects the facts and hands the URL to the browser via `ACTION_VIEW`.

**Tech Stack:** Kotlin, JUnit 4, Jetpack Compose (Material 3), `java.net.URLEncoder` (pure JVM, so the URL builder stays unit-testable).

**Spec:** [`docs/superpowers/specs/2026-08-31-feedback-and-safe-errors-design.md`](../specs/2026-08-31-feedback-and-safe-errors-design.md)

**Build note:** `JAVA_HOME` must point at the repo's vendored `.tools/jdk17`; the system JRE is 32-bit Java 8 and cannot run the build. In Bash: `export JAVA_HOME="$PWD/.tools/jdk17"` in *every* call that runs gradle.

**Baseline:** 175 tests passing on `master`.

---

## File Structure

| File | Responsibility |
|---|---|
| Create: `app/src/main/java/.../domain/UserFacingMessage.kt` | The marker interface + `UserFacingException`. |
| Create: `app/src/main/java/.../domain/FailureCopy.kt` | Pure: what to show a user, what to record. |
| Create: `app/src/main/java/.../domain/FailureLog.kt` | Last three diagnostics, in memory. |
| Create: `app/src/main/java/.../feedback/FeedbackReport.kt` | Pure: `DeviceFacts`, markdown body, encoded URL. |
| Create: `app/src/main/java/.../feedback/DeviceFactsCollector.kt` | Thin glue: reads Build/BuildConfig/prefs. |
| Create: `app/src/main/java/.../feedback/FeedbackLauncher.kt` | Thin glue: `ACTION_VIEW`, clipboard fallback. |
| Modify: `domain/... ModelSession.kt`, `transfer/TranscriptArchive.kt` | Mark deliberate user copy. |
| Modify: `service/DictationForegroundService.kt`, `ui/settings/SettingsScreen.kt`, `models/ModelDownloadManager.kt` | Use `FailureCopy` at the four leak sites. |
| Modify: `ui/common/UnsupportedDeviceNotice.kt`, `ui/onboarding/OnboardingScreen.kt` | `Report this device` button. |
| Create: tests for `FailureCopy`, `FailureLog`, `FeedbackReport` | |
| Modify: `app/build.gradle.kts`, `README.md`, `CHANGELOG.md`, `docs/HANDOFF.md` | Version bump + docs. |

Package root is `dev.chaseallbright.localscribe`.

---

## Task 1: The seam and the failure copy

**Files:**
- Create: `app/src/test/java/dev/chaseallbright/localscribe/domain/FailureCopyTest.kt`
- Create: `app/src/main/java/dev/chaseallbright/localscribe/domain/UserFacingMessage.kt`
- Create: `app/src/main/java/dev/chaseallbright/localscribe/domain/FailureCopy.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package dev.chaseallbright.localscribe.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FailureCopyTest {

    @Test
    fun `a user-facing exception is shown verbatim`() {
        val error = UserFacingException("This file is not a LocalScribe export.")
        assertEquals(
            "This file is not a LocalScribe export.",
            FailureCopy.userMessageFor(FailureContext.IMPORT, error)
        )
    }

    @Test
    fun `an ordinary exception never leaks its message`() {
        // A real SQLite string. This is exactly what users were being shown.
        val error = IllegalStateException("database or disk is full (code 13 SQLITE_FULL[13])")
        val shown = FailureCopy.userMessageFor(FailureContext.DICTATION, error)
        assertTrue("leaked the raw message: $shown", !shown.contains("SQLITE_FULL"))
        assertTrue("leaked the raw message: $shown", !shown.contains("disk is full"))
        assertEquals("Dictation failed. Nothing was inserted. (E-DICT)", shown)
    }

    @Test
    fun `a blank user-facing message falls back to the generic copy`() {
        // Marked, but with nothing worth showing -- must not render an empty toast.
        assertEquals(
            "Export failed. (E-EXPORT)",
            FailureCopy.userMessageFor(FailureContext.EXPORT, UserFacingException("   "))
        )
        assertEquals(
            "Export failed. (E-EXPORT)",
            FailureCopy.userMessageFor(FailureContext.EXPORT, UserFacingException(""))
        )
    }

    @Test
    fun `each context carries its own code and copy`() {
        val error = RuntimeException("boom")
        assertEquals("Export failed. (E-EXPORT)", FailureCopy.userMessageFor(FailureContext.EXPORT, error))
        assertEquals("Import failed. (E-IMPORT)", FailureCopy.userMessageFor(FailureContext.IMPORT, error))
    }

    @Test
    fun `a connectivity failure during a download says so`() {
        val offline = java.net.UnknownHostException("Unable to resolve host \"huggingface.co\"")
        val shown = FailureCopy.userMessageFor(FailureContext.DOWNLOAD, offline)
        assertEquals("Download failed. Check your connection. (E-DOWNLOAD)", shown)
        assertTrue("leaked the host", !shown.contains("huggingface"))
    }

    @Test
    fun `a non-connectivity download failure does not blame the connection`() {
        // ModelDownloadException extends IOException and covers HTTP 404. Telling the user to
        // check their connection here would be actively misleading.
        val notFound = java.io.IOException("HTTP 404 for https://huggingface.co/ggml-base.en.bin")
        val shown = FailureCopy.userMessageFor(FailureContext.DOWNLOAD, notFound)
        assertEquals("Download failed. (E-DOWNLOAD)", shown)
        assertTrue("leaked the url", !shown.contains("huggingface"))
    }

    @Test
    fun `connectivity types get no hint in a context that declares none`() {
        val offline = java.net.UnknownHostException("nope")
        assertEquals(
            "Dictation failed. Nothing was inserted. (E-DICT)",
            FailureCopy.userMessageFor(FailureContext.DICTATION, offline)
        )
    }

    @Test
    fun `a diagnostic names the class and the code but never the message`() {
        val error = IllegalStateException("database or disk is full (code 13 SQLITE_FULL[13])")
        val diagnostic = FailureCopy.diagnosticFor(FailureContext.DICTATION, error)
        assertEquals("E-DICT/IllegalStateException", diagnostic)
        assertTrue(!diagnostic.contains("SQLITE_FULL"))
    }

    @Test
    fun `a class with no simple name is recorded as Unknown`() {
        // Anonymous classes report a null simpleName.
        val anonymous = object : RuntimeException("x") {}
        assertEquals(
            "E-DICT/Unknown",
            FailureCopy.diagnosticFor(FailureContext.DICTATION, anonymous)
        )
    }

    @Test
    fun `a marked exception is still recorded by class for a report`() {
        assertEquals(
            "E-IMPORT/UserFacingException",
            FailureCopy.diagnosticFor(FailureContext.IMPORT, UserFacingException("nope"))
        )
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
export JAVA_HOME="$PWD/.tools/jdk17"; ./gradlew :app:testDebugUnitTest --tests "*FailureCopyTest*"
```

Expected: FAIL — `Unresolved reference: FailureCopy`.

- [ ] **Step 3: Write `UserFacingMessage.kt`**

```kotlin
package dev.chaseallbright.localscribe.domain

/**
 * Marks an exception whose message was written for a user and may be displayed verbatim.
 *
 * The default is the opposite: [FailureCopy] hides any message that is not marked. Exception
 * text is written for developers -- users were being shown things like
 * "database or disk is full (code 13 SQLITE_FULL[13])" -- but a blanket ban would also have
 * discarded the messages this app writes deliberately, such as
 * "This file is not a LocalScribe export." This interface is what separates the two.
 */
interface UserFacingMessage

/** Throw when the message is deliberate user copy. See [UserFacingMessage]. */
class UserFacingException(message: String) : Exception(message), UserFacingMessage
```

- [ ] **Step 4: Write `FailureCopy.kt`**

```kotlin
package dev.chaseallbright.localscribe.domain

/** Where a failure happened, carrying the copy and the short code shown in its place. */
enum class FailureContext(val code: String, val generic: String) {
    DICTATION("E-DICT", "Dictation failed. Nothing was inserted."),
    EXPORT("E-EXPORT", "Export failed."),
    IMPORT("E-IMPORT", "Import failed."),
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
```

- [ ] **Step 5: Run the tests**

```bash
export JAVA_HOME="$PWD/.tools/jdk17"; ./gradlew :app:testDebugUnitTest --tests "*FailureCopyTest*"
```

Expected: PASS, 10 tests.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dev/chaseallbright/localscribe/domain/UserFacingMessage.kt app/src/main/java/dev/chaseallbright/localscribe/domain/FailureCopy.kt app/src/test/java/dev/chaseallbright/localscribe/domain/FailureCopyTest.kt
git commit -m "feat: separate user copy from incidental exception text"
```

---

## Task 2: `FailureLog`

**Files:**
- Create: `app/src/test/java/dev/chaseallbright/localscribe/domain/FailureLogTest.kt`
- Create: `app/src/main/java/dev/chaseallbright/localscribe/domain/FailureLog.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package dev.chaseallbright.localscribe.domain

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class FailureLogTest {

    @Before
    fun reset() = FailureLog.clear()

    @Test
    fun `starts empty`() {
        assertEquals(emptyList<String>(), FailureLog.recent())
    }

    @Test
    fun `newest is first`() {
        FailureLog.record("E-DICT/A")
        FailureLog.record("E-DICT/B")
        assertEquals(listOf("E-DICT/B", "E-DICT/A"), FailureLog.recent())
    }

    @Test
    fun `keeps only the last three`() {
        listOf("A", "B", "C", "D").forEach { FailureLog.record("E-DICT/$it") }
        assertEquals(
            listOf("E-DICT/D", "E-DICT/C", "E-DICT/B"),
            FailureLog.recent()
        )
    }

    @Test
    fun `clear empties it`() {
        FailureLog.record("E-DICT/A")
        FailureLog.clear()
        assertEquals(emptyList<String>(), FailureLog.recent())
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
export JAVA_HOME="$PWD/.tools/jdk17"; ./gradlew :app:testDebugUnitTest --tests "*FailureLogTest*"
```

Expected: FAIL — `Unresolved reference: FailureLog`.

- [ ] **Step 3: Write the implementation**

```kotlin
package dev.chaseallbright.localscribe.domain

/**
 * The last few failures, so a feedback report can say what just went wrong.
 *
 * Holds only the strings [FailureCopy.diagnosticFor] produces -- a code and a class name -- so
 * it cannot accumulate message text by construction.
 *
 * Deliberately **not persisted**. An app that keeps no audio and no failure history on disk
 * should not start writing a failure journal, and the case that matters is the one the user is
 * reporting: something that just happened, in this process.
 *
 * Process-scoped like [DictationController], and synchronized because failures are recorded
 * from service coroutines and read from the Settings composable.
 */
object FailureLog {
    private const val CAPACITY = 3

    private val entries = ArrayDeque<String>()

    fun record(diagnostic: String) = synchronized(this) {
        entries.addFirst(diagnostic)
        while (entries.size > CAPACITY) entries.removeLast()
    }

    /** Newest first. */
    fun recent(): List<String> = synchronized(this) { entries.toList() }

    fun clear() = synchronized(this) { entries.clear() }
}
```

- [ ] **Step 4: Run the tests**

Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/chaseallbright/localscribe/domain/FailureLog.kt app/src/test/java/dev/chaseallbright/localscribe/domain/FailureLogTest.kt
git commit -m "feat: remember the last few failure codes for a report"
```

---

## Task 3: Close the three leaks

**Files:**
- Modify: `app/src/main/java/dev/chaseallbright/localscribe/transfer/TranscriptArchive.kt`
- Modify: `app/src/main/java/dev/chaseallbright/localscribe/transfer/ArchiveIo.kt`
- Modify: `app/src/main/java/dev/chaseallbright/localscribe/dictation/ModelSession.kt`
- Modify: `app/src/main/java/dev/chaseallbright/localscribe/service/DictationForegroundService.kt`
- Modify: `app/src/main/java/dev/chaseallbright/localscribe/ui/settings/SettingsScreen.kt`

- [ ] **Step 1: Mark the deliberate copy**

In `TranscriptArchive.kt`, find:

```kotlin
    class UnsupportedArchive(message: String) : IllegalArgumentException(message)
```

Replace with:

```kotlin
    /** Its messages are written for users, so they survive [FailureCopy]'s default of hiding. */
    class UnsupportedArchive(message: String) :
        IllegalArgumentException(message), UserFacingMessage
```

Add the import `dev.chaseallbright.localscribe.domain.UserFacingMessage`.

In `ArchiveIo.kt`, the two `error("Could not open the chosen file...")` calls are user copy. Replace each `error(...)` with `throw UserFacingException(...)`, keeping the same text, and add the import `dev.chaseallbright.localscribe.domain.UserFacingException`.

In `ModelSession.kt`, find:

```kotlin
                    if (!file.isFile) {
                        error("${tier.displayName} speech model isn't downloaded. Open LocalScribe to download it.")
                    }
```

Replace with:

```kotlin
                    if (!file.isFile) {
                        throw UserFacingException(
                            "${tier.displayName} speech model isn't downloaded. " +
                                "Open LocalScribe to download it."
                        )
                    }
```

And find:

```kotlin
                    val bridge = WhisperBridge.load(file.absolutePath)
                        ?: error("Failed to load speech model")
```

Replace with:

```kotlin
                    val bridge = WhisperBridge.load(file.absolutePath)
                        ?: throw UserFacingException(
                            "The speech model could not be loaded. It may have been " +
                                "interrupted mid-download -- delete and re-download it in Settings."
                        )
```

Add the import `dev.chaseallbright.localscribe.domain.UserFacingException`.

- [ ] **Step 2: Fix the dictation leak**

In `DictationForegroundService.kt`, add imports:

```kotlin
import dev.chaseallbright.localscribe.domain.FailureContext
import dev.chaseallbright.localscribe.domain.FailureCopy
import dev.chaseallbright.localscribe.domain.FailureLog
```

Find:

```kotlin
            } catch (e: Exception) {
                DictationController.setState(DictationUiState.Error(e.message ?: "Dictation failed"))
            } finally {
```

Replace with:

```kotlin
            } catch (e: Exception) {
                // The raw message is written for a developer, not a user, and since beta.3 it
                // is toasted. Log it in full and show vetted copy plus a code instead.
                Log.w(TAG, "Dictation failed", e)
                FailureLog.record(FailureCopy.diagnosticFor(FailureContext.DICTATION, e))
                DictationController.setState(
                    DictationUiState.Error(FailureCopy.userMessageFor(FailureContext.DICTATION, e))
                )
            } finally {
```

If `DictationForegroundService` has no `TAG` constant, add `private const val TAG = "DictationService"` alongside its other constants, and import `android.util.Log` if absent. Check before adding — do not duplicate an existing one.

- [ ] **Step 3: Fix the export and import leaks**

In `SettingsScreen.kt`, add imports:

```kotlin
import dev.chaseallbright.localscribe.domain.FailureContext
import dev.chaseallbright.localscribe.domain.FailureCopy
import dev.chaseallbright.localscribe.domain.FailureLog
```

Find:

```kotlin
                .onFailure { toast(it.message ?: "Export failed.") }
```

Replace with:

```kotlin
                .onFailure {
                    FailureLog.record(FailureCopy.diagnosticFor(FailureContext.EXPORT, it))
                    toast(FailureCopy.userMessageFor(FailureContext.EXPORT, it))
                }
```

Find:

```kotlin
                .onFailure { toast(it.message ?: "Import failed.") }
```

Replace with:

```kotlin
                .onFailure {
                    FailureLog.record(FailureCopy.diagnosticFor(FailureContext.IMPORT, it))
                    toast(FailureCopy.userMessageFor(FailureContext.IMPORT, it))
                }
```

- [ ] **Step 3b: Fix the download leak**

`ModelDownloadManager` renders its `Failed` message in both model surfaces (`setupStatusLabel` in
onboarding, `modelStatusLabel` in Settings), so a download error currently shows text like
`Unable to resolve host "huggingface.co"` or `HTTP 404 for https://...` under the model name.

In `ModelDownloadManager.kt`, add imports:

```kotlin
import dev.chaseallbright.localscribe.domain.FailureContext
import dev.chaseallbright.localscribe.domain.FailureCopy
import dev.chaseallbright.localscribe.domain.FailureLog
```

Find:

```kotlin
            } catch (e: Exception) {
                Log.w(TAG, "Download failed for ${spec.filename}", e)
                update(spec.id, ModelDownloadState.Failed(e.message ?: "Download failed"))
            }
```

Replace with:

```kotlin
            } catch (e: Exception) {
                Log.w(TAG, "Download failed for ${spec.filename}", e)
                FailureLog.record(FailureCopy.diagnosticFor(FailureContext.DOWNLOAD, e))
                update(
                    spec.id,
                    ModelDownloadState.Failed(FailureCopy.userMessageFor(FailureContext.DOWNLOAD, e))
                )
            }
```

The existing `Log.w` already keeps the real cause; only what the user sees changes.

- [ ] **Step 4: Verify no leak sites remain**

```bash
grep -rn "message ?:" app/src/main --include=*.kt
```

Expected: no results. If any remain, they are leaks — report them.

- [ ] **Step 5: Build and test**

```bash
export JAVA_HOME="$PWD/.tools/jdk17"; ./gradlew :app:assembleDebug :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL, 189 tests (175 + 10 + 4), 0 failures. Verify from the JUnit XML.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "fix: never show a raw exception message to the user"
```

---

## Task 4: `FeedbackReport`

**Files:**
- Create: `app/src/test/java/dev/chaseallbright/localscribe/feedback/FeedbackReportTest.kt`
- Create: `app/src/main/java/dev/chaseallbright/localscribe/feedback/FeedbackReport.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package dev.chaseallbright.localscribe.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackReportTest {

    private val facts = DeviceFacts(
        appVersion = "0.2.0-beta.4",
        versionCode = 12,
        androidRelease = "16",
        sdkInt = 36,
        manufacturer = "samsung",
        model = "SM-S938U",
        abi = "arm64-v8a",
        cpuVerdict = "supported",
        totalRamGb = 11.4,
        whisperTier = "base.en",
        whisperDownloaded = true,
        cleanupTier = "qwen-0.5b",
        cleanupDownloaded = false,
        cleanupMode = "Standard",
        recordingLimit = "2 minutes",
        recentFailures = listOf("E-DICT/IllegalStateException")
    )

    @Test
    fun `the body carries every fact`() {
        val body = FeedbackReport.body(facts, "It stopped working.")
        listOf(
            "0.2.0-beta.4", "12", "16", "36", "samsung", "SM-S938U", "arm64-v8a",
            "supported", "11.4", "base.en", "qwen-0.5b", "Standard", "2 minutes",
            "E-DICT/IllegalStateException", "It stopped working."
        ).forEach { assertTrue("body is missing '$it':\n$body", body.contains(it)) }
    }

    @Test
    fun `no recent failures still renders`() {
        val body = FeedbackReport.body(facts.copy(recentFailures = emptyList()), "hi")
        assertTrue(body.contains("none"))
    }

    @Test
    fun `over-long user text is truncated with a marker`() {
        val long = "x".repeat(FeedbackReport.MAX_USER_TEXT + 500)
        val body = FeedbackReport.body(facts, long)
        assertTrue(body.contains("truncated"))
        assertTrue(
            "user text was not truncated",
            body.length < long.length + 2000
        )
    }

    @Test
    fun `blank user text is replaced with a prompt rather than left empty`() {
        val body = FeedbackReport.body(facts, "   ")
        assertTrue(body.contains("No description given"))
    }

    @Test
    fun `the url percent-encodes the characters that would break a query string`() {
        val url = FeedbackReport.issueUrl("a b", "one&two#three\nfour", "feedback")
        assertNotNull(url)
        val encoded = url!!
        assertTrue(encoded.startsWith(FeedbackReport.ISSUE_BASE_URL + "?"))
        // A raw & or # would truncate the body at GitHub's end.
        assertTrue(!encoded.substringAfter("body=").substringBefore("&labels").contains("#"))
        assertTrue(encoded.contains("%26") || encoded.contains("%2526"))
        assertTrue(encoded.contains("labels=feedback"))
    }

    @Test
    fun `a url past the length cap is refused rather than silently truncated`() {
        val huge = "y".repeat(FeedbackReport.MAX_URL_LENGTH)
        assertNull(FeedbackReport.issueUrl("title", huge, "feedback"))
    }

    @Test
    fun `an ordinary report fits within the cap`() {
        val body = FeedbackReport.body(facts, "Something went wrong when I tapped the bubble.")
        assertNotNull(FeedbackReport.issueUrl("Feedback from 0.2.0-beta.4", body, "feedback"))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Expected: FAIL — `Unresolved reference: DeviceFacts`.

- [ ] **Step 3: Write the implementation**

```kotlin
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
```

- [ ] **Step 4: Run the tests**

Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/chaseallbright/localscribe/feedback/FeedbackReport.kt app/src/test/java/dev/chaseallbright/localscribe/feedback/FeedbackReportTest.kt
git commit -m "feat: build a feedback report that cannot carry dictated text"
```

---

## Task 5: The glue, and the UI

**Files:**
- Create: `app/src/main/java/dev/chaseallbright/localscribe/feedback/DeviceFactsCollector.kt`
- Create: `app/src/main/java/dev/chaseallbright/localscribe/feedback/FeedbackLauncher.kt`
- Modify: `app/src/main/java/dev/chaseallbright/localscribe/ui/common/UnsupportedDeviceNotice.kt`
- Modify: `app/src/main/java/dev/chaseallbright/localscribe/ui/onboarding/OnboardingScreen.kt`
- Modify: `app/src/main/java/dev/chaseallbright/localscribe/ui/settings/SettingsScreen.kt`

- [ ] **Step 1: `DeviceFactsCollector`**

```kotlin
package dev.chaseallbright.localscribe.feedback

import android.content.Context
import android.os.Build
import dev.chaseallbright.localscribe.BuildConfig
import dev.chaseallbright.localscribe.domain.FailureLog
import dev.chaseallbright.localscribe.models.ModelManager
import dev.chaseallbright.localscribe.platform.CpuSupport
import dev.chaseallbright.localscribe.platform.DeviceCpu
import dev.chaseallbright.localscribe.settings.AppPreferences

/**
 * Reads the facts a report is built from. Thin by design: it makes no decisions about what may
 * be included -- [DeviceFacts] does, by having nowhere to put anything else.
 */
object DeviceFactsCollector {

    fun collect(context: Context): DeviceFacts {
        val appContext = context.applicationContext
        val preferences = AppPreferences(appContext)
        val models = ModelManager(appContext)
        val whisperTier = preferences.whisperTier
        val cleanupTier = preferences.cleanupTier

        return DeviceFacts(
            appVersion = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            androidRelease = Build.VERSION.RELEASE ?: "unknown",
            sdkInt = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER ?: "unknown",
            model = Build.MODEL ?: "unknown",
            abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
            cpuVerdict = when (val support = DeviceCpu.support) {
                is CpuSupport.Supported -> "supported"
                is CpuSupport.Unsupported ->
                    "unsupported, missing ${support.missingFeatures.joinToString(", ")}"
            },
            totalRamGb = models.totalRamGb(),
            whisperTier = whisperTier.id,
            whisperDownloaded = models.isWhisperModelReady(whisperTier),
            cleanupTier = cleanupTier.id,
            cleanupDownloaded = models.isCleanupModelReady(cleanupTier),
            cleanupMode = preferences.cleanupMode.displayName,
            recordingLimit = preferences.recordingLimit.displayName,
            recentFailures = FailureLog.recent()
        )
    }
}
```

- [ ] **Step 2: `FeedbackLauncher`**

```kotlin
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
```

- [ ] **Step 3: Add the button to the notice**

In `UnsupportedDeviceNotice.kt`, change the signature and add a button. Find:

```kotlin
@Composable
fun UnsupportedDeviceNotice(
    unsupported: CpuSupport.Unsupported,
    modifier: Modifier = Modifier
) {
```

Replace with:

```kotlin
@Composable
fun UnsupportedDeviceNotice(
    unsupported: CpuSupport.Unsupported,
    onReport: () -> Unit,
    modifier: Modifier = Modifier
) {
```

Then find the last `Text(` block inside the `Column` (the "Missing processor features: …" one) and add a button immediately after it, still inside the `Column`:

```kotlin
            // The only channel from the one population the project cannot otherwise hear from.
            // Whether it is worth building a second, baseline-compiled native library depends
            // entirely on whether anyone actually owns such a device.
            TextButton(onClick = onReport) { Text("Report this device") }
```

Add imports `androidx.compose.material3.TextButton`.

- [ ] **Step 4: Wire onboarding**

In `OnboardingScreen.kt`, add imports:

```kotlin
import dev.chaseallbright.localscribe.feedback.DeviceFactsCollector
import dev.chaseallbright.localscribe.feedback.FeedbackLauncher
import dev.chaseallbright.localscribe.feedback.FeedbackReport
```

Find:

```kotlin
        if (cpuSupport is CpuSupport.Unsupported) {
            UnsupportedDeviceNotice(cpuSupport)
        }
```

Replace with:

```kotlin
        if (cpuSupport is CpuSupport.Unsupported) {
            UnsupportedDeviceNotice(
                unsupported = cpuSupport,
                onReport = {
                    val facts = DeviceFactsCollector.collect(context)
                    FeedbackLauncher.openIssue(
                        context = context,
                        title = "Unsupported device: ${facts.manufacturer} ${facts.model}",
                        body = FeedbackReport.body(facts, ""),
                        label = FeedbackLauncher.LABEL_DEVICE_REPORT
                    )
                }
            )
        }
```

- [ ] **Step 5: Wire Settings, and add the Feedback section**

In `SettingsScreen.kt`, add the same three feedback imports, plus `androidx.compose.material3.OutlinedTextField` and `androidx.compose.foundation.layout.heightIn` if not already present.

Update the notice call the same way as Step 4 (the `context` val already exists in this composable).

Then add a new section immediately **before** `SettingsSection(title = "Permissions") {`:

```kotlin
        SettingsSection(title = "Feedback") {
            var feedbackText by remember { mutableStateOf("") }
            val facts = DeviceFactsCollector.collect(context)
            val reportBody = FeedbackReport.body(facts, feedbackText)

            Text(
                text = "Opens a pre-filled issue on GitHub for you to review and submit. " +
                    "LocalScribe sends nothing itself.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = feedbackText,
                onValueChange = { feedbackText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("What happened?") },
                minLines = 3
            )
            // Shown in full before anything leaves, for the same reason the backup screen names
            // exactly what travels.
            Text(
                text = "This is what gets attached:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = reportBody,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState())
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = {
                        FeedbackLauncher.openIssue(
                            context = context,
                            title = "Feedback from ${facts.appVersion}",
                            body = reportBody,
                            label = FeedbackLauncher.LABEL_FEEDBACK
                        )
                    }
                ) { Text("Open GitHub issue") }
                TextButton(
                    onClick = { FeedbackLauncher.copyReport(context, reportBody) }
                ) { Text("Copy report") }
            }
        }
```

**Note on the nested scroll:** the screen's root `Column` already has `verticalScroll`. A nested `verticalScroll` on a fixed-height child is legal in Compose and is what makes the preview scrollable inside the page. If it misbehaves at runtime, prefer removing the inner scroll and keeping `heightIn` — do not restructure the screen.

- [ ] **Step 6: Build and test**

```bash
export JAVA_HOME="$PWD/.tools/jdk17"; ./gradlew :app:assembleDebug :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL, 196 tests, 0 failures. Verify from the JUnit XML.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: add a feedback path that files a prefilled GitHub issue"
```

---

## Task 6: Labels, docs, and the version bump

- [ ] **Step 1: Create the labels**

```bash
gh label create feedback --description "User-submitted feedback from in-app reporting" --color 0E8A16 --force
gh label create device-report --description "Unsupported-device report from in-app reporting" --color D93F0B --force
```

- [ ] **Step 2: Bump the version**

In `app/build.gradle.kts`: `versionCode = 12`, `versionName = "0.2.0-beta.4"`. The release workflow fails if the tag does not match `versionName`.

- [ ] **Step 3: CHANGELOG**

Add to the existing `### Fixed` and `### Added` lists under `## [Unreleased]` — betas do not get their own section. Fixed: error messages are now written for people rather than being whatever text an exception happened to carry. Added: the Feedback section, saying plainly that the app sends nothing itself and that no transcript text, vocabulary or audio is included.

- [ ] **Step 4: README**

Add a short "Feedback" section after Install: report via Settings → Feedback, which opens a pre-filled GitHub issue you review and submit; it attaches device and version details and never transcript text.

- [ ] **Step 5: HANDOFF**

Header to `v0.2.0-beta.4` and the test count re-derived from the XML. Add a "What changed in this session" entry covering both halves. Update the **privacy posture table** — the "No telemetry" row should note that feedback is user-initiated, sends nothing from the app itself, and carries no transcript text. Remove the "worth doing only if someone reports owning such a device" blocker language from the dual-variants entry in Left undone, replacing it with a pointer to the `device-report` label as the channel that now exists.

- [ ] **Step 6: Forced full test run**

```bash
export JAVA_HOME="$PWD/.tools/jdk17"
./gradlew --stop
./gradlew :app:testDebugUnitTest --rerun-tasks
grep -rho 'tests="[0-9]*"' app/build/test-results/testDebugUnitTest/*.xml | grep -o '[0-9]*' | awk '{s+=$1} END {print s}'
```

Put that number in the HANDOFF header. Sum it; do not add to the previous figure.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "docs: record the feedback path and prepare v0.2.0-beta.4"
```

---

## Verification before release

- [ ] `grep -rn "message ?:" app/src/main --include=*.kt` returns nothing.
- [ ] Full suite green on a forced rerun; count derived from XML.
- [ ] `grep -n 'versionName\|versionCode' app/build.gradle.kts` reads `12` and `0.2.0-beta.4`.
- [ ] `gh label list` shows `feedback` and `device-report`.
- [ ] Confirm by reading `DeviceFacts` that no field can carry transcript text.
