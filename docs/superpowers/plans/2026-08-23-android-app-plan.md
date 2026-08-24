# LocalScribe (Android) — Implementation Plan

Derived from `docs/superpowers/specs/2026-08-23-android-app-design.md`. Executed
autonomously, no approval checkpoints between phases (per explicit user instruction).

Branding locked 2026-08-23: app name is **LocalScribe** (not "Whisper Flow"), package
root `dev.chaseallbright.localscribe`. See the spec's naming note for why.

## Phase 0 — Toolchain bootstrap
- Download portable JDK 17 (Temurin), Android `cmdline-tools`, `platform-tools`,
  `platforms;android-34`, `build-tools`, NDK, CMake into a project-local `.tools/` directory
  (not a system package manager) so the host machine isn't modified.
- Accept SDK licenses non-interactively. Verify `sdkmanager`, `adb`, `cmake` all resolve.
- Generate/commit the Gradle wrapper so the repo is self-building for anyone who clones it.

## Phase 1 — Repo & project scaffold
- `settings.gradle.kts`, root `build.gradle.kts`, version catalog, `app` module with Compose
  set up, package `dev.chaseallbright.localscribe`, `AndroidManifest.xml`
  with the four permissions (`RECORD_AUDIO`, overlay, accessibility service declaration,
  `POST_NOTIFICATIONS`) and service declarations. App label "LocalScribe".
- `.gitignore`, `LICENSE` (MIT, matching desktop's license — copyright holder "LocalScribe
  contributors"), root `README.md` stub titled "LocalScribe".

## Phase 2 — Native engines
- Add `whisper.cpp` and `llama.cpp` as git submodules pinned to a known-good release tag.
- `whisper-jni` CMake module: adapt upstream's Android JNI example to expose
  init/transcribe/free calls; build for `arm64-v8a` + `armeabi-v7a` (skip x86/x86_64 unless
  needed for emulator testing, in which case add `x86_64` too for that purpose only).
- `llama-jni` CMake module: same approach from `llama.cpp`'s Android example, exposing
  model-load/generate/free for short cleanup completions.
- Smoke-test each native module in isolation (small test model) before wiring into the app.

## Phase 3 — Domain layer (Kotlin, pure/testable)
- `CleanupMode` enum, `Transcript` data class, `Transcriber`/`Cleaner` interfaces,
  `DictationPipeline` orchestrator — mirrors `domain.py`/`pipeline.py` shape.
- `WhisperTranscriber` (wraps `whisper-jni`), `QwenCleaner` (wraps `llama-jni`),
  `RuleBasedCleaner` (deterministic fallback, ported from `cleanup.py`'s non-LLM path).
- Unit tests with fake transcriber/cleaner for pipeline orchestration and fallback behavior.

## Phase 4 — Model manager
- Resumable/verified downloader (byte-range resume, retry, SHA-256 check), RAM-tiered
  default model selection at first run, progress UI, storage in `filesDir`.

## Phase 5 — Permissions, accessibility, overlay, foreground service
- Onboarding screens for the four permissions/settings, with live status and deep links.
- `DictationAccessibilityService`: focus-tracking, editable-node detection, show/hide
  overlay, and (later) text insertion.
- Overlay bubble/pill in Compose via `WindowManager` + `TYPE_APPLICATION_OVERLAY`: idle
  bubble, drag-to-reposition, expand to X/recording/checkmark pill, processing state,
  collapse.
- `DictationForegroundService`: mic-type foreground service, drives capture -> pipeline,
  required notification.
- Wire end-to-end: tap bubble -> record -> tap check -> pipeline runs -> callback to
  accessibility service.

## Phase 6 — Text insertion
- Three-tier insertion (`ACTION_SET_TEXT` -> clipboard+`ACTION_PASTE` -> clipboard-only +
  toast) implemented in the accessibility service, unit-testable where the framework allows,
  otherwise manually verified.

## Phase 7 — History & vocabulary
- Room database + DAO for transcript history; search screen.
- Vocabulary list settings screen; vocabulary threaded into Whisper prompt and cleanup calls.

## Phase 8 — Settings & polish
- Model tier picker, cleanup mode picker, permission status dashboard, app icon/branding.

## Phase 9 — Build & verify
- Assemble a release APK (debug-signed, since no release keystore was provided).
- Install on an emulator (create one via `avdmanager` if no physical device is attached) and
  exercise the golden path: focus a field, record, confirm, verify inserted text; verify the
  clipboard fallback path; verify history/vocabulary screens function.
- Fix issues found during this pass before calling the build done.

## Phase 10 — CI/release scaffolding & docs
- GitHub Actions workflow: build + assemble release APK on version tag (written, not run).
- `README.md` (install/usage matching the desktop's style), architecture doc mirroring
  `docs/ARCHITECTURE.md`, `CONTRIBUTING.md`, `CHANGELOG.md` seeded with v0.1.0.
- Final commit. Write a short handoff note (not asked as a question) covering exactly what
  the user needs to do to create the GitHub repo and cut the first release.

## Risk notes carried forward (not blockers, just realism)
- Compiling two native ML runtimes for Android from a cold toolchain in one session is the
  highest-risk part of this plan; if a specific NDK/ABI combination proves intractable, the
  fallback is to reduce ABI coverage (e.g., `arm64-v8a` only, which covers the overwhelming
  majority of real Android hardware since 2019) rather than blocking the whole build.
- If no emulator/device ends up available for Phase 9, the APK will still be produced and
  installable; verification will be as thorough as the available tooling allows and this will
  be reported plainly rather than claimed as fully tested.
