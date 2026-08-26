# LocalScribe Android — Handoff

_Last updated: 2026-08-25. Repo: https://github.com/Kallbrig/localscribe (public). Default branch `master`._

Verified on a Galaxy S25 Ultra (Android 16, 8 cores, 11.4 GB RAM, arm64-v8a).
Unit suite: 67 tests, all passing. Build with `./gradlew :app:assembleDebug` — `JAVA_HOME`
must point at the repo's vendored `.tools/jdk17`, since the system JRE is 32-bit Java 8 and
cannot run the build.

---

## Done

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

- **Release signing.** `release.yml` is fully wired and waits on a `v*` tag, but the release
  build is debug-signed — see Concerns. Needs a real keystore plus four GitHub secrets before
  any tag is pushed.
- **CI push trigger.** `.github/workflows/ci.yml` triggers its push job on `main`, but the
  default branch is `master`, so it never fires on merge. The `pull_request` trigger still works.
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

- **The release APK is debug-signed.** `app/build.gradle.kts` still uses
  `signingConfig = signingConfigs.getByName("debug")` for the release build type. Two
  consequences: CI runners generate a fresh debug keystore per run, so consecutive releases
  would carry different signatures and could not be installed over one another
  (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`, losing local transcript history); and the debug keystore
  password is publicly known, so a forged "update" would be accepted as legitimate.
  **Do not push a `v*` tag until this is fixed** — the release workflow would happily publish it.

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
