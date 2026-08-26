# LocalScribe Android — Handoff

_Last updated: 2026-08-26. Repo: https://github.com/Kallbrig/localscribe (public). Default branch `master`._

**v0.1.1 is released**: https://github.com/Kallbrig/localscribe/releases/tag/v0.1.1 — the first
release this repo has ever published. Signed with the real keystore, so it and everything after
it can be installed over one another. Obtainium can track the repo directly.

Verified on a Galaxy S25 Ultra (Android 16, 8 cores, 11.4 GB RAM, arm64-v8a).
Unit suite: 67 tests, all passing. Build with `./gradlew :app:assembleDebug` — `JAVA_HOME`
must point at the repo's vendored `.tools/jdk17`, since the system JRE is 32-bit Java 8 and
cannot run the build.

---

## Done

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

**Requested next, not started**

- **Cleanup modes are indistinguishable — informal and business produce near-identical text.**
  Diagnosed but unfixed. Two mechanisms, and the second is the dominant one:

  1. `QwenCleaner` splices `mode.promptHint` (one sentence) into ~120 words of fixed system
     prompt that instructs the model to *preserve* everything: "never add reactions, facts,
     opinions", "preserve the speaker's perspective, intent, names, places, and claims". That
     directly contradicts BUSINESS's "rewrite as polished, concise professional
     communication", and the conservative half wins on both volume and forcefulness.
  2. `TextCleanupUtils.isFaithful` rejects output where >30% of content words are new — which
     is precisely what a business rewrite is. Worked example: source `um so I was thinking like
     maybe we could uh push the deadline back a week you know`; a correct business output `I
     propose we extend the deadline by one week.` introduces `{propose, extend, one}` of 6
     content words = 50%, rejected, silently falling back to `RuleBasedCleaner`, which is
     mode-blind apart from filler stripping and a trailing period. Note the denominator is the
     *edited* word count, so concision raises the ratio: "polished, concise" is penalized
     twice over. The check mathematically forbids the transformation the mode exists to do.

  Fixing the prompt alone makes the fallback fire *more*. The fix is per-mode faithfulness
  thresholds (or bypassing the vocabulary check for BUSINESS/CASUAL while keeping the
  question-preservation and length guards), plus per-mode prompts not fighting a conservative
  preamble.

  **Verifiable before changing anything:** transcripts have recorded `backend` since `c892e6d`.
  If this analysis is right, BUSINESS dictations show `RULES_FALLBACK` far more often than
  INFORMAL ones. That is a query against existing data, not a guess.

- **History interaction.** Requested: select, delete, and otherwise act on individual
  transcripts. Today `HistoryScreen` is read-only with search plus a global clear;
  `TranscriptDao` has no per-row delete.

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

- **CI cannot build a release — `RELEASE_KEYSTORE_PASSWORD` is wrong.** v0.1.1 was built and
  published from the local machine as a workaround. The other three secrets are correct
  (`RELEASE_KEYSTORE_BASE64` was re-uploaded from bash after PowerShell's pipe corrupted it;
  PowerShell applies console encoding and line-wrapping to strings piped into a native command,
  so use `--body`, not a pipe). Fix by running, from the repo root:
  `gh secret set RELEASE_KEYSTORE_PASSWORD --body ((Get-Content keystore.properties | Where-Object {$_ -like 'storePassword=*'}) -replace '^storePassword=','')`
  and the same for `keyPassword` into `RELEASE_KEY_PASSWORD`. Until then a `v*` tag fails at the
  keystore gate in about 50 seconds, naming which secret is at fault.
- **Onboarding does not mention models.** It ends with "You're all set" while no model is
  downloaded. Now that downloads live in Settings, a first-run user gets a "not downloaded"
  error on their first dictation with nothing having pointed them at Settings.
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
