# LocalScribe Android — Handoff

_Last updated: 2026-08-26. Repo: https://github.com/Kallbrig/localscribe (public). Default branch `master`._

**Stable: v0.1.7.** **In flight: v0.2.0-beta.1.** Releases now have two channels decided by the
tag alone -- `vX.Y.Z-beta.N` publishes as a GitHub prerelease that Obtainium skips, `vX.Y.Z` is
stable and marked Latest. Work accumulates as betas and ships once under a version that means
something. See [RELEASING.md](RELEASING.md).

CI builds and publishes both channels end to end. Every release shares certificate
`73ef2d6d…`, so all of them install over one another. Unit suite: 135 tests, all passing.

Build with `./gradlew :app:assembleDebug` — `JAVA_HOME`
must point at the repo's vendored `.tools/jdk17`, since the system JRE is 32-bit Java 8 and
cannot run the build.

---

## Done

### Settings, backup and transfer (v0.1.4 - v0.1.7)

- **Transcripts were being uploaded to Google Drive.** `allowBackup="true"` with no rules means
  Android Auto Backup sweeps app-private storage, and the Room database sits in exactly that
  directory. Nothing in the app sent anything anywhere -- an inherited platform default that
  contradicted the stated promise for three releases.
- `LocalScribeBackupAgent` overrides `onFullBackup` and hands over files individually, never
  calling `super` (the default sweep is the bug). XML rules are static and cannot express a
  user setting, which is why the agent exists. Every category defaults **off**, and onboarding
  presents the choice rather than leaving it in Settings.
- Transcripts and vocabulary share one database file, so vocabulary alone travels as an export
  generated at backup time and merged back on first launch after a restore. Enabling
  transcripts pulls vocabulary along unavoidably; the UI says so.
- Export/import to a JSON file via the system file picker, for a device move with no cloud
  involved. JSON not CSV because dictated text contains commas, quotes and newlines. Import
  merges and is idempotent -- transcripts are identified by timestamp plus cleaned text, since
  ids are per-device autoincrement values.
- History rows: expand for raw vs cleaned, copy, share, delete; long-press for multi-select
  with bulk delete. Selection is by id and pruned against visible rows, because the list is a
  live Flow.
- Selecting an undownloaded model used to switch to it silently, leaving dictation pointed at
  something that could not load. It now asks, then selects *and* downloads.
- History durations were one unlabelled figure that was **audio length** but read as processing
  time. Stage timings are now persisted (Room v2 to v3) and every figure is labelled.

### Informal is near-verbatim, and the styles are a real gradient (v0.1.3)

Reported: "hey man what's going on" on informal came back as "Hey man, what's up?" -- capital,
comma inserted, words swapped. Informal is for texting.

The styles are now a gradient in *what may change*, not four wordings of "tidy this up":

| Style | Grammar & punctuation | Word choice |
|---|---|---|
| Informal | left alone | exact |
| Casual | corrected | exact |
| Standard | corrected | tightened |
| Business | corrected | rewritten |

- **Informal no longer runs the LLM.** `VerbatimFormatter` is deterministic, so substitution is
  unrepresentable rather than merely rejected. A 0.5B model cannot be reliably talked out of
  tidying, and rejecting its tidying afterwards spends a model load to reach the same place.
- Whisper emits prose-formatted text (sentence case, commas, terminal punctuation). Informal's
  job is undoing that. `Hey man, what's going on?` -> `hey man what's going on`.
- Kept on purpose: apostrophes already present (removing them degrades the transcript rather
  than declining to correct it), the pronoun "I", acronyms, custom vocabulary, and names -- a
  word Whisper capitalised anywhere other than a sentence start, which then stays capitalised
  everywhere including at a sentence start. Major sentence breaks survive as a bare full stop;
  the text does not end on one. Disfluencies go, slang stays.
- `CleanupBackend.VERBATIM` keeps informal out of the "basic cleanup" tag and the fallback
  toast. Informal not using the LLM is the mode working, not a shortfall.
- 17 tests in `VerbatimFormatterTest`, including both reported cases verbatim.

### Cleanup styles that actually differ (v0.1.2, budgets since revised in v0.1.3)

Informal and business produced near-identical text. The prompt was only half of it; the
dominant cause was `isFaithful` rejecting any output introducing more than 30% new content
words -- precisely what a business rewrite is -- so business silently fell back to the
mode-blind `RuleBasedCleaner`.

- `FaithfulnessPolicy` per mode: its own vocabulary budget, length ratio and length slack.
  Standard keeps the original numbers exactly, so its behaviour is unchanged.
- Each mode carries its own `instruction` and a one-shot `exampleOutput` for a shared example
  dictation. On a 0.5B model the example moves the output more than the adjective does.
- The shared preamble no longer says "preserve the wording" -- it forbids only replying and
  inventing. It was previously arguing against whichever style the user had selected.
- Guards are now unconditional in every mode rather than emergent from one threshold: a
  dictated question stays a question, the text cannot grow, and a figure never dictated is
  rejected (`numbers(source).containsAll(numbers(edited))`).
- **The vocabulary budget stays strict below 8 source content words.** No flat threshold works:
  the reply "I'm fine, how about you?" to "Hi, how are you?" scores 0.50 and must be rejected,
  while a real business rewrite scores 0.67 and must be accepted. Source length is what
  separates them -- a three-word denominator is noise, and short dictations are exactly where
  a model is most likely to reply rather than edit.
- 13 tests in `CleanupModeDifferentiationTest`.

### History you can act on (v0.1.2)

- Tap to expand a row: raw text beside cleaned, with copy, share and delete.
- Long-press starts multi-select; select-all and bulk delete via `deleteByIds`. Deletions
  confirm and state exactly what they remove.
- `HistorySelection` is a pure value class (9 tests). Selection is held **by id and pruned
  against the visible rows** -- the list is a live Flow, so a new dictation or a changed
  search query re-filters underneath it and index-based selection would delete rows the user
  could not see.
- Expanding is disabled during multi-select: one gesture with two meanings is how you delete
  the wrong transcript.

### Onboarding covers models (v0.1.2)

- `ModelSetupRow` shows the speech model as required (size, progress, retry) and the cleanup
  model as explicitly optional, with rules cleanup named as the fallback.
- "You're all set" is gated on the speech model being on disk, not just on permissions.
- `startRecording()` refuses to open the microphone without a speech model. It was only
  touched once the pipeline ran, so a missing one surfaced *after* the user had spoken and the
  recording was discarded.
- Fixed the three places that still documented downloads happening during a first dictation:
  `README.md`, `docs/ARCHITECTURE.md`, and a comment in `ModelSession`.

### Release signing and the release pipeline

Release builds were signed with the debug keystore, whose password is public and which CI
regenerates per run — consecutive releases would have carried different signatures and been
uninstallable over one another (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`, taking transcript
history with them).

- Signing resolves from `keystore.properties` (gitignored, template committed as
  `keystore.properties.example`) then the `LOCALSCRIBE_*` environment variables. No debug
  fallback exists.
- A `taskGraph.whenReady` guard fails any `assemble*/bundle*/package*Release` task outright
  when signing is unconfigured, so a tag cannot quietly publish an unsigned APK. Debug builds
  are unaffected.
- v3 signing enabled — it carries a certificate lineage, the only mechanism for rotating this
  key later. At `minSdk 28` apksigner emits v3 alone and reports v2 absent; that is correct.
- `release.yml` decodes a base64 keystore secret, verifies it against a known SHA-256 and opens
  it with the supplied password/alias *before* the ~8 minute native build, then verifies the
  built APK is not debug-signed before attaching it.
- **`gradlew` was committed as mode `100644`.** Every CI and release job since the repo was
  created died with "Permission denied" (exit 126) before Gradle started. It stayed invisible
  because the push trigger pointed at `main` while the branch is `master`, and no PR has ever
  been opened. Both fixed. The "67 tests passing" claim had only ever been true locally.

### Resident model lifecycle

Models were previously loaded from disk on every single dictation, and the Qwen cleanup
model's native context was never freed — a leak on each one.

- `ModelSessionEngine` — a generic, JNI-free state machine covering pre-warm, pin/unpin, idle
  unload, invalidation and memory pressure. 20 unit tests, no device required.
- `ModelSession` — process-level facade binding the engine to the real loaders. Exposes
  `withModels { }` as the only dictation path, so an unbalanced pin is unrepresentable.
- Whisper pre-warms when a text field gains focus; the cleanup model also pre-warms on devices
  with at least 6 GB RAM. Both unload after 5 idle minutes.
- Services, settings and the Application class wired through the facade. The per-dictation
  loads and the native leak are gone, with a single load site and a single release site.

### Native performance — the big one

The debug variant compiled ggml with **no `-O` flag at all**, and clang defaults to `-O0`, so
the entire numerical library ran unoptimized. Release builds were getting `-O2` the whole time,
so only the sideloaded debug APK was affected — which is exactly what was being tested.

- Forced `-O3` for the debug build type in both native modules.
- Added `-march=armv8.2-a+fp16+dotprod` for arm64-v8a, matching what whisper.cpp's own Android
  example ships.

Measured, same phrase, same device:

| stage | before | after |
|---|---|---|
| transcribe (tiny.en) | 19,363 ms | 510 ms |
| cleanup (Qwen 0.5B) | 74,113 ms | 1,514 ms |
| **total** | **93,501 ms** | **2,052 ms** |

### llama KV cache

`nativeGenerate` never reset the KV cache, and `llama_decode` continues from whatever is
already there. Harmless when each dictation built a fresh context; a real bug once the model
became resident — context accumulated across dictations, generation slowed each time, and at
around 2048 tokens decode would fail and cleanup would silently drop to the rules fallback.
Fixed with `llama_memory_clear`. Verified: five consecutive dictations held cleanup flat at
1414–1527 ms with `backend=QWEN` throughout.

### Text insertion

`ACTION_SET_TEXT` replaces a node's entire contents, so dictation was never inserting — it was
always replacing, which only looked correct in empty fields.

- Splices at the cursor, replaces a selection, restores the caret after the inserted text.
- Adds a separating space only where its absence would run two words together — never beside
  existing whitespace, an opening bracket or quote, or attaching punctuation.
- Guards the case where an empty field's `text` is actually its placeholder hint.
- 14 unit tests; the splice logic is pure Kotlin and needs no device.

### Cleanup honesty surfacing

Transcripts record which cleaner produced them (`QWEN` / `RULES` / `RULES_FALLBACK`), persisted
via a Room v1 to v2 migration. History rows show a "basic cleanup" tag, and a toast fires when
AI cleanup was expected but did not happen — gated on the text actually being inserted, and
suppressed until cleanup recovers so it cannot nag on every dictation.

### Model downloads moved into Settings

Downloading happened inside the dictation path with no progress reported, which is what made a
first run present as a spinner hanging for minutes.

- `ModelDownloadManager` — process-level, exposes per-model state; downloads survive leaving
  the settings screen.
- Settings rows show download / progress with cancel / delete / retry per model.
- The dictation path no longer downloads at all: a missing speech model fails immediately with
  a message pointing at Settings; a missing cleanup model degrades to rules cleanup.
- Deleting a model also invalidates the resident session and clears any partial `.part` file.
- Removed `ModelManager.ensureWhisperModel`/`ensureCleanupModel` — a second path that could
  silently download from anywhere is how the original bug happened.

### Build and toolchain correctness

- `minSdk` raised 26 to 28 in all three modules; dead API 26 branches removed.
- Native libraries 16 KB page-aligned (`0x4000`). Android 15+ can run 16 KB pages, where a
  4 KB-aligned library fails to load outright.
- `ndkVersion` pinned to 28.2.13676358 in all modules. Previously nothing pinned it, so AGP
  defaulted to NDK 27 locally while both CI workflows install 28.2 — two different toolchains
  depending on where the build ran.

---

## To do

**Follow-ups from the v0.1.2 work**

- **The LLM-backed styles are unverified on-device.** Informal is deterministic and covered by
  exact-output tests. Casual, standard and business are not: their thresholds are validated by unit
  tests against hand-written examples, not against real Qwen 0.5B output. The thing to check
  is the `RULES_FALLBACK` rate per mode: transcripts record `backend`, so if business still
  falls back far more often than informal, the budget is still too tight. That query is the
  measurement, not a guess.
- **Casual, standard and business are still identical without a cleanup model.** Informal now
  has its own deterministic path, but the other three fall to the same rules branch, which
  varies only by filler stripping and a trailing full stop. On a rules-only install those
  three read alike. Fixing it means per-mode deterministic transforms, or telling the user in
  Settings that three of the four styles need the cleanup model.
- **No instrumented test covers the new history interactions.** `HistorySelection` is pure and
  tested; the Compose wiring around it, the delete confirmations and the clipboard/share
  intents are not. Same gap the seam-tests item below describes.

**Not started, from the original priority list**

- **Voice activity detection.** Auto-stop on silence, and trim leading/trailing silence before
  inference. whisper.cpp ships an energy-based VAD reachable through the existing JNI bridge.
- **PCM buffer cap.** `AudioRecorder` buffers unbounded 16 kHz PCM in memory with no limit or
  disk spill — a quiet OOM risk on a long dictation. This is the part of "chunked transcription"
  that still clearly matters; see Concerns for why the streaming half matters less now.
- **Seam tests.** The riskiest code has none: the three accessibility insertion tiers, focus
  tracking, and the JNI boundary. Instrumented tests for `TextInsertion` against a test
  activity, plus a JNI smoke test in CI.

**Model selector — Phase 0 done, Phases 1 and 2 deliberately deferred (sized L combined)**

- **Phase 1 (M): expanded whisper catalog.** Curated rather than all 30-plus ggml files —
  tiny/base/small in English and multilingual, q5_1 quantized variants (q5_1 small is about
  180 MB versus 488 MB for comparable accuracy), large-v3-turbo as opt-in. Needs grouped UI; a
  flat radio list of 20-plus models is unusable. Make the size field optional —
  `ModelDownloader` already falls back to a HEAD request, so entries stop needing hardcoded
  byte counts.
- **Phase 2 (M): cleanup model infrastructure.** The blocker is chat templates. `QwenCleaner`
  hardcodes ChatML, so a Llama/Gemma/Phi model would receive a malformed prompt, produce junk,
  fail the faithfulness check and silently fall back to rules. The fix is to stop hand-building
  the prompt and use the template embedded in each GGUF via `llama_model_chat_template()` and
  `llama_chat_apply_template()` — both confirmed present in the vendored llama.cpp. That means
  new JNI surface and reworking `QwenCleaner` into a general `LlmCleaner` taking messages.

**Smaller**

- **Deferred review findings**, all judged non-blocking at the time: `prewarm` has no fast path
  when both models are already resident, so every focus event still allocates and takes the
  engine mutex; and the `warnedCleanupFallback` reset sits inside the `if (inserted)` branch, so
  a QWEN recovery whose insertion fell back to the clipboard will not clear the suppression flag.

---

## Concerns

- **ARMv8.2 requirement versus `minSdk 28`.** The native modules are compiled for
  `armv8.2-a+fp16+dotprod` (Cortex-A75/A55 and later, roughly 2018 onward). On an older arm64
  CPU the app does not run slowly — it crashes with SIGILL. Raising `minSdk` makes the manifest
  more honest but **does not actually close this**: a Pixel 2 has a 2017 CPU without those
  instructions and runs Android 11, so it would install and then crash. The real fixes are a
  runtime CPU feature check that fails gracefully (XS–S), or whisper.cpp's approach of building
  two library variants and choosing at load time (M). Currently unaddressed.

- **The keystore exists in exactly one place.** `localscribe-release.jks` and its password live
  only on the local machine, both gitignored. Losing either means never being able to update
  anyone who installed v0.1.1 or later. Back both up off that machine.

- **A debug-signed build may still be on the test device.** It cannot be updated over by v0.1.1;
  it must be uninstalled first, which wipes local transcript history. One-time cost.

- **No Room migration test.** `exportSchema = false`, so there is no schema JSON to diff and no
  instrumented migration test. The v1 to v2 migration is covered only by mapping unit tests and
  a manual check. Adding `MigrationTestHelper` requires flipping `exportSchema = true` and
  committing the schemas first. Fresh-install and upgraded schemas were verified equivalent by
  inspecting generated DDL — `@ColumnInfo(defaultValue = "UNKNOWN")` matches the migration's
  `DEFAULT 'UNKNOWN'` — but nothing enforces that going forward.

- **`onTrimMemory` is inert on Android 14+.** The platform stopped delivering every trim level
  this filter accepts; only `TRIM_MEMORY_UI_HIDDEN` still arrives, and that is deliberately
  excluded because it fires constantly during normal use. On modern devices the 5-minute idle
  timer is the sole governor of up to roughly 1.5 GB of resident native memory. An accepted
  trade-off, documented in the code, but worth revisiting if memory pressure is ever observed.

- **Idle residency is a rolling window, not a cap.** Every focus event and every dictation
  re-arms the timer, so continuous use keeps models resident well beyond one 5-minute window.
  Intended, but it means the memory ceiling is time-unbounded during active use.

- **Timing instrumentation is still in the shipped code.** One `Log.i` per stage on the
  `LocalScribePerf` tag (`adb logcat -s LocalScribePerf`). Kept deliberately: it is what turned
  "the icon spins for minutes" into a precise diagnosis in a single run. The cost is negligible,
  but it is developer-facing output in a release build. Remove if that matters.

- **Another dictation app is active on the test device.** Wispr Flow (`com.wispr.flowapp`) has
  its own accessibility service enabled alongside LocalScribe's, plus a call recorder. Two
  dictation apps observing accessibility events can interact badly. This was never ruled in or
  out as a factor in any observed behaviour — worth remembering if something inexplicable shows
  up in focus tracking or insertion.

- **The case for streaming transcription has weakened.** It was on the list because
  transcription was the bottleneck; at around 500 ms per clip it no longer is, and cleanup is
  now the larger share at about 1.5 s. Note also that whisper pads every clip to a 30-second
  window internally, so a 2-second phrase costs the same as a 30-second one — chunking would not
  help short dictations at all. The memory argument for a buffer cap still stands on its own.

- **Selecting a model and downloading it are separate actions.** Picking a tier in Settings does
  not fetch it; you tap Download. This is deliberate, to avoid surprise multi-hundred-megabyte
  fetches, but it is a UX papercut, and combined with the onboarding gap above it is the most
  likely thing to confuse a new user.
