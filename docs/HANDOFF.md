# LocalScribe Android — Handoff

_Last updated: 2026-09-27. Repo: https://github.com/Kallbrig/localscribe (public). Default branch `master`._

**Stable: `v0.1.7`. In flight: `v0.2.0-beta.5`.** Unit suite: 250 tests, all passing.

Verified on a Galaxy S25 Ultra (Android 16, 8 cores, 11.4 GB RAM, arm64-v8a).

Build with `./gradlew :app:assembleDebug`. `JAVA_HOME` **must** point at the repo's vendored
`.tools/jdk17` — the system JRE is 32-bit Java 8 and cannot run the build. `keytool` is not on
PATH either; use `.\.tools\jdk17\bin\keytool.exe`.

---

## Release process

Two channels, decided by the tag alone. Full detail in [RELEASING.md](RELEASING.md).

| Tag | Channel | Obtainium |
|---|---|---|
| `vX.Y.Z-beta.N`, `-rc.N` | prerelease, not marked Latest | skipped unless the user opts in |
| `vX.Y.Z` | stable, marked Latest | installed |

Work accumulates as betas and ships once under a version that means something. Every release
shares certificate `73ef2d6d…`, so all of them install over one another.

CI builds and publishes both channels end to end. The gates, in order: tag channel recognised →
tag matches `versionName` → keystore verified against its SHA-256 and opened with the supplied
password (this runs *before* the ~8 minute native build, so a bad secret fails in ~50 seconds and
names which one) → tests and release build → `apksigner` confirms not debug-signed.

**The keystore exists in exactly one place.** `localscribe-release.jks` and its password live only
on the local machine, both gitignored. Losing either means never being able to update anyone who
installed v0.1.1 or later. Back both up off that machine.

---

## What changed in this session

### The mic bubble collapses, takes a colour, and can be dragged away (`v0.2.0-beta.5`)

Asked for with Wispr Flow as the reference: its icon shrinks to a dot a few seconds after
appearing. Design and plan: [`specs/2026-09-27-bubble-collapse-and-style-design.md`](superpowers/specs/2026-09-27-bubble-collapse-and-style-design.md),
[`plans/2026-09-27-bubble-collapse-and-style.md`](superpowers/plans/2026-09-27-bubble-collapse-and-style.md).
Every decision in the spec's table was Chase's, made in chat before any code.

- **What restarts the timer is exactly two things**: a text field gaining focus, and a
  dictation finishing (inserted, cancelled or failed). Dragging deliberately does not. A timer
  that expires mid-drag collapses on release, never under the finger.
- **`DictationUiState` could not carry the wake.** Moving from one field to another is
  `Idle -> Idle`, which `StateFlow` does not re-emit, so a second field would never have
  restarted the timer. `BubbleWake` is a counter: `onFieldFocused()` from the accessibility
  service, plus one rule inside `setState` -- leaving `{Recording, Processing}` is a finish --
  rather than a call at each finishing site. `setState` now uses `getAndUpdate` because it is
  called from both the main thread and `Dispatchers.Default`, and the rule must see the real
  transition.
- **`BubbleCollapse` is a pure reducer with a generation**, the same shape as the recording
  limit's: a superseded timer's expiry is dropped rather than collapsing early.
- **A tap on the dot gets at least 3 seconds.** Found while writing the service, not in the
  agreed design: at "Immediately" the expanded bubble would otherwise re-collapse before it
  could be tapped, making dictation unreachable. `TAP_GRACE_MS`, unit-tested.
- **Collapse shifts the window.** The overlay is `WRAP_CONTENT`, so a 56 dp window around a
  small dot would swallow taps meant for the app underneath. The window shrinks to 28 dp and
  moves by half the difference so the dot sits on the bubble's centre. The shift is driven by
  what is *shown* (`showingDot` = collapsed *and* idle), not by the collapse state, so a timer
  expiring during a recording cannot nudge the pill.
- **Drag-to-dismiss** uses a second, `FLAG_NOT_TOUCHABLE` overlay window at bottom centre.
  Hit testing derives the bubble's position from the drag-start snapshot plus window movement,
  because `getLocationOnScreen` lags `updateViewLayout` by a frame. A dismissed bubble is put
  back where the drag began, so it does not reappear parked over the keyboard. A cancelled
  gesture is never a drop.
- **Colour and opacity cover every overlay state**, as asked. The recording dot stays red
  (it is the live-mic signal) with a ring in the glyph colour, because on the red swatch it
  would otherwise vanish. Glyph colour is chosen by WCAG contrast ratio, and a test holds every
  swatch at 3:1 or better.
- Review of the branch found five real defects, all fixed before merge: the opacity slider
  saving a composition-time capture (one step stale), the non-atomic `setState`, cancel treated
  as a drop, a mid-drag wake leaving the drag snapshot 14 dp off, and the target re-measured on
  every drag event.

**CI broke underneath this release, not because of it.** Runners now ship cmdline-tools 16.0,
which dropped the legacy `tools` package that `android-actions/setup-android@v3` installs by
default, so both workflows died at setup in ~30 s. Both now pass `packages: platform-tools`. The
first `v0.2.0-beta.5` tag was deleted and re-pointed at the fix; it had never produced a release.

**Not verified on a device.** No phone was attached this session. Everything under
`ui/overlay/` that is pure is unit-tested (46 new tests); the windows, gestures, timer wiring
and the Settings section are compile-checked only. The first install of beta.5 is the first
time any of it runs.

### Errors are written for people, and there is a way to report them

Four sites displayed whatever string an exception happened to carry. Users could be shown
`database or disk is full (code 13 SQLITE_FULL[13])` after a dictation, or
`Unable to resolve host "huggingface.co"` under a model download. `v0.2.0-beta.3` made the
dictation one far more visible, because it started toasting `Error` states that had previously
been set and never rendered.

- **A blanket ban on `e.message` would have been wrong**, and that is the whole design. Some
  exceptions carry copy deliberately written for users -- `UnsupportedArchive`'s "This file is not
  a LocalScribe export.", the missing-model refusal. A `UserFacingMessage` marker interface
  separates the two, and the default is to hide. `FailureCopy` is pure and decides both what to
  show and what to record.
- **What is recorded is the exception's class, never its message.** A diagnostic reads
  `E-DICT/SQLiteFullException`. That is what makes it safe for a report to travel, and the real
  message still reaches logcat.
- **One deliberate exception, for connectivity.** Replacing every download error with "Download
  failed." would remove the one diagnosis a user can act on, so `UnknownHostException`,
  `ConnectException`, `SocketTimeoutException` and `SSLException` add "Check your connection."
  Deliberately not `IOException`: `ModelDownloadException` extends it and covers `HTTP 404`,
  where that advice would be actively misleading. The check **walks the cause chain**, because
  `ModelDownloader` retries and rethrows everything wrapped -- checking only the top level made
  the entire branch unreachable, which is how it first shipped in review.

Feedback needs no backend, no database and no account of the maintainer's:

- `FeedbackReport` builds a markdown body and a percent-encoded `issues/new` URL; the app hands it
  to the browser with `ACTION_VIEW` and the user presses Submit. **LocalScribe makes no network
  call**, so "one network call site, one host" stays literally true.
- **`DeviceFacts` is the privacy guarantee.** It has no field capable of holding a transcript, a
  vocabulary word, audio, or an exception message, so a report cannot carry one. The precise claim
  is "LocalScribe never puts dictated text in a report; only you can, by typing it" -- the free-text
  box is the one place user content can enter, and Settings shows the finished report in full
  before anything happens.
- **Honest about when it travels.** The details are in the URL, so they reach GitHub the moment
  the browser loads the page -- before Submit, and whether or not the user goes through with it.
  README says so rather than implying the report waits for consent.
- A **Report this device** button on the unsupported-CPU notice, shared by both screens via
  `FeedbackLauncher.reportDevice`. Labels `feedback` and `device-report` exist on the repo. Note
  GitHub only honours `?labels=` for people with triage rights, so the `Unsupported device:
  <model>` title prefix is what actually makes triage work.

Two defects worth remembering, both caught in review rather than by tests:

- **Multi-line feedback rendered as a code block.** Kotlin interpolates before `trimIndent()`
  runs, so a user's second line at indent zero dragged the common indent to zero and left every
  template line with twelve leading spaces -- an indented code block in GitHub markdown. The
  diagnostics table stopped being a table the moment anyone pressed Enter, in a box configured
  with `minLines = 3`. That is the modal case, not an edge case.
- **A character cap is not a URL budget.** Truncating at 2000 characters only holds for ASCII;
  Cyrillic costs three bytes per character and emoji up to twelve, so a Russian user writing a
  short paragraph hit the clipboard fallback while an English user wrote three times as much.
  Truncation is now against the encoded length, and stays inside `body()` so the preview still
  shows exactly what is sent.

### Old processors are refused, not crashed

Both native modules compile with `-march=armv8.2-a+fp16+dotprod`. On a pre-2018 arm64 CPU that
code does not run slowly — it executes an instruction the silicon does not implement and the
kernel kills the process with **SIGILL**. `minSdk 28` gates on OS version, not CPU generation: a
Pixel 2 has a 2017 Snapdragon 835 and runs Android 11, so it installed and died, silently, every
launch. Android has no `<uses-feature>` for an ARM extension, and distribution is via
GitHub/Obtainium rather than Play, so **runtime is the only place this could be caught**.

- `CpuSupport.evaluate(primaryAbi, cpuinfo)` is pure — it takes the ABI and the *text* of
  `/proc/cpuinfo` rather than reading either, so every branch of it is unit-tested.
  `DeviceCpu` holds everything untestable: a static field read, a `/proc` read, and a cache.
  Fourteen tests cover its five decision rules, including every fail-open path.
- **The gates run before `System.loadLibrary`, not inside the bridges.** That call sits in each
  bridge's `companion object` initializer, and `dlopen` runs `.init_array`, so the fault may
  happen at load time rather than at first inference. Waiting until the bridge is touched would
  be too late. Gates live at `startRecording()`, `ModelSession.prewarm()` and
  `ModelSession.withModels()`.
- **It fails open at every uncertain step.** A non-arm64 ABI, an unreadable `/proc/cpuinfo`, a
  missing `Features` line, or a `Features` line with no tokens all return `Supported`. Only
  positive evidence that `asimdhp` or `asimddp` is absent disables anything — a false negative
  would kill dictation on hardware that works perfectly, which is strictly worse than the crash
  being fixed.
- Checks `asimdhp` (`+fp16`) and `asimddp` (`+dotprod`) but deliberately **not** `atomics`
  (FEAT_LSE), which `armv8.2-a` also implies: every core with dot product necessarily has LSE, so
  checking it adds no detection power and only adds a way to wrongly reject a device.
- Intersects the feature sets across every reported core rather than taking the union — a thread
  scheduled onto a weaker core would fault. Compares whole tokens, never substrings, because
  `asimd`, `asimdhp`, `asimdrdm` and `asimddp` share prefixes.
- **Never verified on real pre-2018 hardware**, and it will not be: no such device is available,
  and none was attached at all. The unsupported path is exercised only by unit tests against
  captured `Features` strings. What protects working devices is the fail-open design, not testing.

Three further defects, the first two pre-existing and surfaced by this work, the third created by
it. Without the first two the gate would have replaced a loud crash with a silent one:

- **`DictationUiState.Error` was never rendered.** Three refusal paths (unsupported CPU, missing
  permission, missing speech model) set an Error carrying the reason, but `OverlayContent` drew
  Error identically to `Idle` and nothing read `.message`. The user tapped the mic bubble and
  nothing happened. It is now toasted, via the same collector pattern the cleanup fallback uses.
- **Error was sticky, and then it conflated.** `clearFocus()` and `updateFocusFromEvent()` matched
  exact states, so once state was Error neither could fire and the bubble stayed on screen over
  every app. Both now treat Error as clearable. That alone was not enough: `StateFlow` does not
  re-emit an equal value and the overlay window is `FLAG_NOT_FOCUSABLE`, so nothing cleared Error
  between two taps on the same field and only the *first* refusal toasted. The collector now
  returns the state to `Idle` once the message is delivered.
- Settings hides the model sections on an unsupported device, which removed the app's only
  `ModelDownloadManager.delete` call site. Downloading a model never touches native code, so such
  a device can be holding up to ~2.3 GB from an earlier build, in app-private files no file
  manager reaches — while the notice tells the user to keep the app for history and export. A
  delete-only section covers that.

**The overlay bubble is deliberately left alone on an unsupported device.** It still appears in
every text field, and every tap produces the same toast. Suppressing it was considered and
rejected: a bubble that silently never appears is indistinguishable from a broken accessibility
service, and the toast is the thing that makes the failure legible. Raised in final review as a
possible omission, so it is recorded here as a decision.

### The capture buffer is bounded

`AudioRecorder` buffered 16 kHz PCM with no limit and no spill, at roughly **5×** the recorded
bytes in peak heap — a `ByteArrayOutputStream` that doubles on growth, plus `toByteArray()`'s copy,
plus a `FloatArray` at twice the byte width. Around 20–40 minutes that exhausts the Java heap as an
`OutOfMemoryError`, killing the process and the foreground service with it, and the user loses the
dictation with no message. The realistic long recording is not a long dictation; it is one that was
never stopped.

- The budget is enforced **on the write path**, not by a timer. A timer bounds elapsed time rather
  than memory and leaves the buffer unbounded whenever it fails to fire; `RecordingBudget.accept()`
  makes overflow unrepresentable.
- Hitting the limit **finalizes the dictation** rather than discarding it — the same reasoning that
  made `startRecording()` refuse to open the microphone without a speech model. The notification
  says so, rather than appearing to stop on a whim.
- The limit callback is tagged with a **recording generation**. A capture thread orphaned by
  `stopInternal()`'s one-second join can post long after its own recording ended, by which point a
  bare `isRecording` check would be true again for an unrelated dictation and would finalize it
  early. The generation check drops the stale callback.
- Chunked storage plus a draining decode cut peak from 5× to **3×**: 2 minutes now costs ~11 MB
  where it cost ~19 MB, and 10 minutes ~58 MB where it cost ~96 MB. That is what makes the
  10-minute notch defensible rather than merely survivable.
- **`stop()` never reset the buffer** — only `cancel()` and `start()` did — so PCM stayed resident
  through the whole pipeline and beyond, until the next recording. The privacy table's
  "`buffer.reset()` on stop and cancel" was true of cancel only. Draining now empties it, and the
  claim is true as written.
- The limit is a setting: notches at 1/2/3/5/10 minutes, defaulting to 2, with a confirmation
  dialog at 5 and above that names both costs — the processing time and the fact that cleanup only
  sees about 2.5 minutes of speech at once.
- No disk spill. `README.md` promises "no audio is ever retained"; a spill file survives a crash and
  is the same class of leak `LocalScribeBackupAgent` was written to close.

### Release signing, and a pipeline that had never worked

Release builds were signed with the **debug keystore**, whose password is public and which CI
regenerates per run. Consecutive releases would have carried different signatures and been
uninstallable over one another (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`, taking transcript history
with them).

- Signing resolves from `keystore.properties` (gitignored; template committed as
  `keystore.properties.example`) then `LOCALSCRIBE_*` environment variables. No debug fallback.
- A `taskGraph.whenReady` guard fails any `assemble*/bundle*/package*Release` task outright when
  signing is unconfigured, so a tag cannot quietly publish an unsigned APK. Debug builds unaffected.
- v3 signing enabled — it carries a certificate lineage, the only mechanism for rotating this key
  later. At `minSdk 28` apksigner emits v3 alone and reports v2 absent; that is correct.
- **`gradlew` was committed as mode `100644`.** Every CI and release job since the repo was created
  died with "Permission denied" (exit 126) before Gradle started. It stayed invisible because the
  push trigger pointed at `main` while the branch is `master`, and no PR has ever been opened. Both
  fixed. The "67 tests passing" claim in the previous handoff had only ever been true locally.
- Getting the first release out took four distinct failures: the base64 secret corrupted by
  PowerShell's pipe (it applies console encoding and line-wrapping to strings piped into a native
  command — use `--body`, not a pipe), the `gradlew` bit, a bad secret only surfacing eight minutes
  in, and a wrong password. v0.1.1 and v0.1.2 were published from the local machine as a workaround;
  v0.1.3 onward are CI-built.

### Cleanup styles are a real gradient

Informal and business produced near-identical text. The prompt was only half the cause; the
dominant one was `isFaithful` rejecting any output introducing more than 30% new content words —
which is precisely what a business rewrite *is* — so business silently fell back to the mode-blind
`RuleBasedCleaner`.

| Style | Grammar & punctuation | Word choice | Budget | Uses LLM |
|---|---|---|---|---|
| Informal | left alone | exact | 0.05 | **no** |
| Casual | corrected | exact | 0.15 | yes |
| Standard | corrected | tightened | 0.35 | yes |
| Business | corrected | rewritten | 0.70 | yes |

- **Informal does not run the LLM.** `VerbatimFormatter` is deterministic, so substitution is
  *unrepresentable* rather than merely rejected. A 0.5B model cannot be reliably talked out of
  tidying — it returned `Hey man, what's up?` for `hey man what's going on`, swapping words as well
  as punctuation — and rejecting that afterwards spends a model load to reach the same place.
- Whisper emits prose-formatted text (sentence case, commas, terminal punctuation). Informal's job
  is undoing that. Kept on purpose: apostrophes already present (removing them degrades the
  transcript rather than declining to correct it), the pronoun "I", acronyms, custom vocabulary, and
  names — a word Whisper capitalised anywhere other than a sentence start, which then stays
  capitalised everywhere. Major sentence breaks survive as a bare full stop; the text does not end
  on one. Disfluencies go, slang stays.
- **No flat threshold can work.** A reply to "Hi, how are you?" scores 0.50 and must be rejected; a
  legitimate business rewrite scores 0.67 and must be accepted. Source *length* separates them — a
  three-word denominator is noise, and short dictations are exactly where a model is most likely to
  reply rather than edit. Relaxed budgets apply only above 8 source content words.
- Guards holding unconditionally in every mode: a dictated question stays a question, the text
  cannot grow, and a figure never dictated is rejected. Those are what stop the model answering
  rather than editing; the vocabulary budget is the only part that varies.
- The length cap was `max(len × ratio, len + 80)` with a flat slack, so at a typical 74-character
  dictation business and standard both capped at 154 and business's stricter ratio never applied.
  Slack is now per-mode. Caught in self-review, not by a test — the comment claimed a conciseness
  the code did not enforce.
- `CleanupBackend.VERBATIM` keeps informal out of the "basic cleanup" tag and the fallback toast.

### History you can act on

- Tap to expand: raw beside cleaned, with copy, share and delete. Raw is **always** shown, labelled
  "unchanged" when identical — hiding it on a match looks like a bug when the row was opened
  precisely to compare.
- Long-press starts multi-select; select-all and bulk delete via `deleteByIds`. Deletions confirm
  and state exactly what they remove.
- `HistorySelection` is a pure value class. Selection is held **by id and pruned against visible
  rows** — the list is a live Flow, so a new dictation or a changed query re-filters underneath it,
  and index-based selection would delete rows the user could not see.
- Expanding is disabled during multi-select: one gesture with two meanings is how you delete the
  wrong transcript.
- Footer durations were one unlabelled figure that was **audio length** (`samples.size / 16000`) but
  read as processing time. Stage timings are now persisted (Room v2→v3) and every figure labelled:
  `Informal · 16.6s spoken · 512ms to transcribe · 1.5s to clean up`. Rows written before this show
  no timing rather than a fabricated zero.

### Onboarding covers models

Onboarding ended at "You're all set" while no model was downloaded, so a first dictation failed
pointing at a Settings screen nothing had mentioned — after the user had already spoken.

- `ModelSetupRow` shows the speech model as required (size, progress, retry) and the cleanup model
  as explicitly optional, naming rules cleanup as the fallback.
- "You're all set" is gated on the speech model being on disk, not just permissions.
- `startRecording()` refuses to open the microphone without a speech model, so a missing one can no
  longer discard a dictation already spoken.
- Selecting an undownloaded model used to switch to it silently. It now asks, reports the size, and
  on confirm selects *and* downloads — in that order, so finishing leaves the user on the model they
  asked for.

### Backup was leaking transcripts

`android:allowBackup="true"` was set with no rules, so Android Auto Backup included app-private
storage by default — and the Room database holding every transcript sits in exactly the directory it
sweeps. **Nothing in the app's own code sent anything anywhere**; this was an inherited platform
default that contradicted the app's stated promise for three releases.

- `LocalScribeBackupAgent` overrides `onFullBackup` and hands over files individually, never calling
  `super` (the default sweep is the bug). Android's XML backup rules are static and cannot express a
  user setting, which is the entire reason the agent exists.
- **Every category defaults off**, master switch included. Existing choices survive: each flag reads
  with the default as fallback, so anyone who configured backup keeps what they picked.
- Onboarding presents the choice alongside permissions and models, via a component shared with
  Settings so the two cannot drift. It deliberately does not gate "You're all set" — off is the safe
  state, and blocking setup on it would train people to click through the one screen that matters.
- Transcripts and vocabulary share one database file and cannot be separated by path. Vocabulary
  alone travels as an export generated at backup time, merged back on first launch after a restore
  (merged, not replaced, so a restore does not discard words added before the backup arrived).
  Enabling transcripts pulls vocabulary along unavoidably; the UI says so.
- The WAL is checkpointed before the database is handed over, or recent dictations live only in the
  `-wal` and are missing from the restored copy.
- Models are never backed up. XML exclusions are kept as a fallback if the agent is ever removed.

### Export and import

A fully local device move, no cloud involved. Export writes one JSON file through the system file
picker, so no storage permission is needed and the user chooses where it lands.

- **JSON, not CSV.** A transcript is arbitrary dictated text containing commas, quotes and
  newlines — all CSV escaping hazards. An export that round-trips only until someone dictates a
  comma is worse than none. Tests cover quotes, embedded newlines, tabs, unicode and emoji.
- Import **merges** and is idempotent. Transcripts are identified by timestamp plus cleaned text
  because ids are per-device autoincrement values and mean nothing across a transfer; two dictations
  sharing an instant with different text both survive.
- The envelope is versioned: a newer file is refused with an explanation rather than mis-parsed, and
  a row naming a mode or backend this build does not know is **kept** — the text is the valuable
  part and an enum growing should not cost the user transcripts.
- `org.json` is stubbed to throw in `android.jar`, so a real implementation is a test-only
  dependency; runtime uses the platform copy.

### Earlier work, retained

- **Resident models.** `ModelSessionEngine` / `ModelSession`: pre-warm on focus, unload after 5 idle
  minutes. `withModels { }` is the only dictation path, so an unbalanced pin is unrepresentable.
- **Native performance.** The debug variant compiled ggml with no `-O` flag, so clang defaulted to
  `-O0` and the whole numerical library ran unoptimised. With `-O3` and
  `-march=armv8.2-a+fp16+dotprod`: **93,501 ms → 2,052 ms** end to end (transcribe 19,363 → 510 ms,
  cleanup 74,113 → 1,514 ms).
- **llama KV cache.** `nativeGenerate` never reset it, so once the model became resident, context
  accumulated across dictations and at ~2048 tokens decode failed into the rules fallback.
- **Text insertion.** `ACTION_SET_TEXT` replaces a node's entire contents, so dictation was always
  replacing, not inserting. Now splices at the cursor, replaces selections, restores the caret, and
  adds a separating space only where its absence would run two words together.
- **Model downloads moved into Settings**, out of the dictation path.
- **Build correctness.** `minSdk` 26→28; native libraries 16 KB page-aligned; `ndkVersion` pinned.

---

## How the recording limit was built

Recorded because the artefacts outlive the session and are worth finding again, and because two
of the defects below were caught by process rather than by luck.

The work ran as **spec → plan → task-by-task execution, with two reviews after every task**:

| Stage | Artefact |
|---|---|
| Design, agreed before any code | [`docs/superpowers/specs/2026-08-30-recording-limit-design.md`](superpowers/specs/2026-08-30-recording-limit-design.md) |
| Eight tasks, TDD, exact code per step | [`docs/superpowers/plans/2026-08-30-recording-limit.md`](superpowers/plans/2026-08-30-recording-limit.md) |

Each task got a **spec-compliance review** (did it build what was asked, nothing more, nothing
less) and then a **code-quality review**. Both were told not to trust the implementer's report and
to re-derive every claim — test counts, arithmetic, and hand-traces — from the code. That is what
produced the two findings the plan itself had missed:

- **The stale limit callback.** `onRecordingLimitReached()` originally guarded on the service-wide
  `isRecording` flag, which says *a* recording is live, not *which* one. A capture thread orphaned
  by `stopInternal()`'s one-second join can post long after its own recording ended; by then the
  flag may be true again for an unrelated dictation, and the callback would finalize that one early
  and mislabel it. Fixed with a per-recording generation counter, captured by value in the callback
  closure.
- **The slider had no accessible label.** It is the app's first `Slider`, and Material3 sets no
  default `contentDescription`, so TalkBack would have announced a raw index — "1 of 0 to 4" — for a
  control whose entire meaning is minutes. Every other interactive element in the app already labels
  itself, so this was below the codebase's own bar rather than a new standard.

Ordering mattered once: the audio-focus work ([#1](https://github.com/Kallbrig/localscribe/pull/1))
was still an open PR touching the same four methods of `DictationForegroundService`. It was merged
first so the limit work could branch from it, rather than both landing and conflicting.

**A stale figure worth knowing about.** The header claimed 135 tests; the real pre-change count was
139. Nothing had updated it. The number is now derived from the JUnit XML rather than from
arithmetic — if you change it, sum it rather than adding to the last figure:

```bash
grep -rho 'tests="[0-9]*"' app/build/test-results/testDebugUnitTest/*.xml | grep -o '[0-9]*' | awk '{s+=$1} END {print s}'
```

`--rerun-tasks` occasionally fails on a stale Gradle daemon holding a locked jar in
`whisper-jni`/`llama-jni`. `./gradlew --stop` then retry; it is not a real failure, but a cached
`UP-TO-DATE` pass is not a verification either — force the rerun before trusting a green suite.

---

## How the CPU gate was built

Same shape as the recording limit: **spec → plan → task-by-task execution, with review after every
task**, reviewers told not to trust the implementer's report and to re-derive every claim.

| Stage | Artefact |
|---|---|
| Design, agreed before any code | [`docs/superpowers/specs/2026-08-31-cpu-support-gate-design.md`](superpowers/specs/2026-08-31-cpu-support-gate-design.md) |
| Six tasks, TDD, exact code per step | [`docs/superpowers/plans/2026-08-31-cpu-support-gate.md`](superpowers/plans/2026-08-31-cpu-support-gate.md) |

Four findings came from process rather than luck, and three were defects in the *spec*, not the
code — worth knowing, because the plan was followed faithfully and would have shipped all of them:

- **The empty-`Features` rule was backwards.** The spec asserted that a `Features` line with no
  tokens was a real answer from the kernel and therefore evidence of an old CPU. It is not: `fp`
  and `asimd` are architecturally mandatory on every ARMv8-A core, so a real arm64 kernel never
  emits one. An empty list means redacted or synthesised procfs — a hardened ROM, a container — and
  the original rule would have **permanently disabled dictation on working hardware**, the exact
  failure the whole fail-open design exists to prevent.
- **Splitting feature tokens on space and tab alone** leaned fail-closed in the one file whose
  stated invariant is fail-open. Now any whitespace.
- **Only the first refusal ever toasted.** Caught by tracing `StateFlow` conflation against the
  overlay's `FLAG_NOT_FOCUSABLE` window — two facts in different files that only bite together.
- **Hiding the Settings model sections stranded up to ~2.3 GB** by removing the app's only delete
  control, on precisely the devices being told to keep the app installed.

A fifth was caught during *planning*, before any code: wrapping the onboarding Models section in a
visibility check would have moved `whisperState` out of scope for the "You're all set" gate further
down the file. The four `val`s are hoisted for that reason, and the comment says so.

Reviewers also correctly declined to flag several things — that `check()` in `withModels` is right
because the gate above makes it genuinely unreachable, that the main-thread `/proc/cpuinfo` read is
a sub-millisecond synthetic-file read worth leaving alone, and that the notice needs no extra
TalkBack labelling because it contains no interactive element.

## Left undone

Highest value first.

- **The LLM-backed styles are unverified on-device.** Informal is deterministic and covered by
  exact-output tests. Casual, standard and business have thresholds validated against hand-written
  examples, not real Qwen output. The measurement already exists: transcripts record `backend`, so
  query the `RULES_FALLBACK` rate per mode. If business still falls back far more than informal, the
  budgets are still too tight. That is a query against existing data, not a guess.
- **Casual, standard and business are identical without a cleanup model.** Informal has its own
  deterministic path now, but the other three fall to the same rules branch, which varies only by
  filler stripping and a trailing full stop. Fix with per-mode deterministic transforms, or tell the
  user in Settings that three of the four styles need the model.
- **Dual library variants, to actually run on pre-2018 arm64.** The CPU gate makes those devices
  fail honestly; only this makes them work. whisper.cpp's own Android example ships the pattern:
  build a baseline arm64 library alongside the armv8.2 one and pick at load time. Sized **M**, and
  the blocker is not the code — it is that **the baseline path cannot be verified without a
  pre-2018 device**, so it would ship untested. It also roughly doubles an already ~8-minute
  native build and the APK's native payload. There is now a channel for finding out whether
  anyone owns such a device: the **Report this device** button files an issue labelled
  `device-report`, titled `Unsupported device: <model>`. Check that label before deciding.
- **No instrumented tests on the riskiest code.** The three accessibility insertion tiers, focus
  tracking, and the JNI boundary have none. Nor do the new Compose surfaces: history interactions,
  delete confirmations, clipboard/share intents, the backup agent's `onFullBackup`. The pure logic
  under each (`TextSplice`, `HistorySelection`, `BackupPlan`, `TranscriptArchive`) is tested; the
  wiring is not.
- **Voice activity detection.** Auto-stop on silence, and trim leading/trailing silence before
  inference. whisper.cpp ships an energy-based VAD reachable through the existing JNI bridge.
- **Room migration tests.** `exportSchema = false`, so there is no schema JSON to diff and no
  instrumented migration test — now across two migrations (v1→v2, v2→v3). Adding
  `MigrationTestHelper` requires flipping `exportSchema = true` and committing schemas first.
- **Model selector Phase 1 (expanded whisper catalog).** Curated rather than all 30-plus ggml files;
  q5_1 variants (q5_1 small is ~180 MB versus 488 MB for comparable accuracy). Needs grouped UI — a
  flat radio list of 20-plus models is unusable.
- **Model selector Phase 2 (cleanup model infrastructure).** Blocked on chat templates; see below.
- **Deferred review findings.** `prewarm` has no fast path when both models are already resident, so
  every focus event allocates and takes the engine mutex. `warnedCleanupFallback` resets inside the
  `if (inserted)` branch, so a QWEN recovery whose insertion fell back to the clipboard will not
  clear the suppression flag.

---

## Discussed, not acted on

- **The dot's touch area is 28 dp, below the 48 dp guideline.** Flagged in review. Deliberate:
  the overlay window blocks touches across its whole area, transparent or not, so a 48 dp dot
  would steal taps from the app underneath -- the thing collapsing exists to stop. Tapping the
  dot only expands it, so a miss costs nothing but a second tap.
- **Dismissal assumes the app does not re-focus the same field.** It lasts until the next
  `TYPE_VIEW_FOCUSED` on an editable node, and an app that re-requests focus on its editor (on
  a message arriving, a list rebind) would bring the bubble straight back. Plausible, not
  observed -- untestable without a device. If it happens, the fix is to remember the dismissed
  node and ignore focus events for that same node until a different one is focused.

- **A `require(chunk.size % 2 == 0)` guard in `Pcm16`.** Raised twice in review: the function
  documents a whole-frame invariant it does not enforce, and violating it appends one fabricated
  `0.0f` sample to the tail. Declined because the guard would throw inside `stop()`, on the main
  thread, mid-dictation — trading a **lost dictation** for an inaudible artefact, in an app whose
  stated principle is that spoken audio is never discarded. With no instrumented tests reaching
  `AudioRecorder`, it would only ever fire in production, where it is strictly worse than what it
  guards against.
- **Moving the whole-frame truncation out of `AudioRecorder`.** `wholeFrames = accepted - (accepted
  % BYTES_PER_SAMPLE)` is the one place a pure unit's contract is enforced by untested Android glue.
  Relocating it into `RecordingBudget` would make a generic byte budget PCM-frame-aware, which is
  worse layering; a `Pcm16` helper would work if it is ever worth revisiting. Correct today: only
  the terminal, limit-filling chunk can have `accepted < read`, and every limit is an even number of
  bytes.
- **Dropping the mirrored `recordingLimit` state in `SettingsScreen`.** It drives no recomposition
  and duplicates `preferences.recordingLimit`. Kept because `whisperTier`, `cleanupTier`, and
  `cleanupMode` are all held the same way in that file; removing it would make the new section the
  only one reading preferences straight from an event handler.
- **A double-tap on confirm cancelling an in-flight dictation.** Reported in review as a live race:
  `confirmAndProcess()`'s `if (!isRecording) { stopSelf(); return }` would tear the service down
  while `serviceScope` was still transcribing, and `onDestroy()`'s `serviceScope.cancel()` would
  drop the dictation. **Investigated and found unreachable.** `RecordingPill` — the only thing that
  emits `ACTION_CONFIRM` — renders solely in `Recording` state and is replaced by a button-less
  `ProcessingPill` the moment state changes; the notification has no actions. Hitting it would need
  two taps inside roughly one frame. Left alone deliberately: it is defensive dead code, not a bug.
  Worth re-checking if a notification action or any second sender of `ACTION_CONFIRM` is ever added.
- **Qwen 3.8** (released August 2026). Two variants, neither usable: **3.8-Max** is 2.4T parameters
  and **API-only**, contradicting the whole premise; **3.8-27B** is the smallest open-weight release
  at ~28B — roughly 56× the current 0.5B, ~16 GB at Q4 against 11.4 GB of device RAM. The realistic
  swap is **Qwen3-0.6B**, which ships official GGUFs and drops into `ModelCatalog` as a
  repo/filename/size change. Two caveats: Qwen3's *thinking mode* would be actively harmful here (a
  `<think>` block would blow the 512-token cap and land inside the text `isFaithful` inspects —
  needs `/no_think` or `enable_thinking=false`, which the hand-built ChatML cannot express), and the
  style problem was a validation bug, not a capability gap. Measure the fallback rate before
  swapping models.
- **Gemini 3.5 Transcribe** (announced 2026-08-26). Cloud API only — no open weights, no on-device
  option. Notable because it is not just a Whisper competitor: it removes fillers, handles
  self-corrections and auto-formats, collapsing transcribe + cleanup into one model, and it ships
  inside Gboard on the test device. On raw quality it will beat a 0.5B doing post-hoc repair,
  because it has the audio, whereas cleanup here only ever sees Whisper's text. Nothing to adopt;
  the differentiator narrows to local-only, which is now the whole pitch rather than one of several.
  Worth watching for a small *open* audio-in/text-out model with cleanup behaviour — that would
  collapse this pipeline the same way and remove the 1.5 s cleanup stage.
- **Hardcoded ChatML blocks every other cleanup model.** `QwenCleaner` builds `<|im_start|>` markers
  by hand, so a Llama/Gemma/Phi GGUF would receive a malformed prompt, produce junk, fail the
  faithfulness check and silently drop to rules. The fix is `llama_model_chat_template()` +
  `llama_chat_apply_template()`, both present in the vendored llama.cpp but not exposed through the
  JNI layer. This is also what would let Qwen3's thinking mode be switched off properly.
- **The Windows sibling has the same cleanup-mode bug.** `src/localscribe/cleanup.py` has the same
  flat 30% `_is_faithful` threshold and the same one-line-hint-inside-a-conservative-prompt
  structure, so its informal and business modes will read alike for the same reason. None of the
  v0.2.0 work has been ported.
- **Clipboard and share are a genuine sandbox exit.** User-initiated and expected, but the clipboard
  is broadly readable. Not a defect; worth knowing when describing the app as fully local.
- **The transcript database is unencrypted SQLite.** Fine against other apps under Android's
  sandbox, readable on a rooted device. SQLCipher would close it at the cost of a dependency and key
  management.
- **The case for streaming transcription has weakened.** It was on the list when transcription was
  the bottleneck; at ~500 ms it no longer is, and whisper pads every clip to a 30-second window
  internally, so chunking would not help short dictations at all. The memory argument that also
  favoured it has since been answered directly by the recording limit, so nothing is left pointing
  this way.

---

## Concerns

- **Pre-ARMv8.2 hardware still cannot run LocalScribe** — it is now told so instead of crashing. The
  gate is a detection, not a port: dictation genuinely does not work on those devices, and the
  only fix that would change that is two library variants (see Left undone). Note also that the
  gate's own unsupported path has never executed on real hardware.
- **A debug-signed build may still be on the test device.** It cannot be updated over by v0.1.1 or
  later; it must be uninstalled first, which wipes local transcript history. One-time cost.
- **`onTrimMemory` is inert on Android 14+.** The platform stopped delivering every level this
  filter accepts; only `TRIM_MEMORY_UI_HIDDEN` still arrives and is deliberately excluded. On modern
  devices the 5-minute idle timer is the sole governor of up to ~1.5 GB of resident native memory.
- **Idle residency is a rolling window, not a cap.** Every focus event and dictation re-arms the
  timer, so continuous use keeps models resident well beyond one window.
- **Timing instrumentation ships in release builds.** One `Log.i` per stage on `LocalScribePerf`
  (`adb logcat -s LocalScribePerf`). Kept deliberately — it is what turned "the icon spins for
  minutes" into a precise diagnosis in one run. No transcript text is ever logged; only timings and
  character counts.
- **Another dictation app is active on the test device.** Wispr Flow (`com.wispr.flowapp`) has its
  own accessibility service enabled alongside LocalScribe's. Two dictation apps observing
  accessibility events can interact badly. Never ruled in or out as a factor in anything observed.
- **Some screens hide every overlay, including this one.** Screens handling sensitive input (the
  Contacts editor is a confirmed example) set `PRIVATE_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS`, hiding
  every non-system overlay app-wide. Not detectable or workaroundable, and not specific to this app.

---

## Privacy posture, as audited

Verified against the code, not assumed.

| Claim | Status |
|---|---|
| No telemetry or analytics | Zero third-party SDKs; dependencies are androidx/kotlin only. Feedback is user-initiated and carries no transcript text; the app posts nothing, it opens a prefilled URL in the browser -- so the details reach GitHub on page load, before Submit |
| Nothing dictated leaves the device | One network call site (`ModelDownloader`), one host (`huggingface.co`), model files only |
| Audio never retained | Buffered in memory, capped by the recording limit, drained on stop and cleared on cancel; never written to disk |
| Nothing sensitive logged | No transcript text anywhere; perf logs are timings and character counts |
| Inference is local | whisper.cpp and llama.cpp in-process over JNI |
| Backup | **Was leaking transcripts to Drive**; off by default and per-category since v0.1.4 |
