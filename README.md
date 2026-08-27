# LocalScribe

LocalScribe is a private, local-first voice dictation app for Android. Focus any text field
in any app, tap the floating mic bubble, speak, tap the checkmark, and cleaned-up text is
inserted directly into that field. Speech-to-text and cleanup both run on-device.

> Beta (`v0.1.0`): sideloaded release APK only -- see [Install](#install) below. Not on the
> Play Store; the permission set this app needs (Accessibility Service, display-over-other-apps)
> is heavily scrutinized there.

This is the Android sibling of LocalScribe Flow for Windows, but a **fully independent
product**: no code, settings, models, vocabulary, or history is shared or synced between
the two.

## What it does

- Local speech-to-text with `whisper.cpp` (ggml models), no cloud calls
- A small local Qwen 2.5 GGUF model through `llama.cpp` for cleanup
- Informal, casual, standard, and business cleanup modes
- Custom vocabulary supplied to Whisper's prompt and preserved through cleanup
- RAM-tiered model selection with resumable, verified downloads
- Three-tier text insertion: direct field insertion, then clipboard+paste, then
  clipboard-only as a last resort -- the transcript is never silently lost
- Deterministic offline cleanup fallback if the local LLM cannot load
- Local transcript history with search; no audio is ever retained
- No accounts, no telemetry, no network access except downloading models you chose

## Install

1. Download the APK from [Releases](../../releases).
2. Install it (you'll need to allow installs from this source).
3. Open LocalScribe and grant the four permissions it asks for, in order:
   - **Microphone** -- to capture what you dictate.
   - **Display over other apps** -- shows the floating dictation bubble.
   - **Accessibility service** -- lets LocalScribe detect focused text fields and insert
     text into them. This is the one Android permission that can't be granted from inside
     the app; the onboarding screen deep-links to the system settings page for it.
   - **Notifications** (Android 13+) -- required by Android while recording/processing.
4. Download the speech model when onboarding prompts for it (a few hundred MB, one time).
   The cleanup model is optional -- without it transcripts get basic rule-based tidying
   instead of AI cleanup.
5. Focus any text field, tap the mic bubble, dictate, tap the checkmark. Everything after
   the download is fully offline.

## Building from source

```bash
git clone --recurse-submodules <this-repo>
cd whisper-flow-alt-android
./gradlew assembleDebug
```

Requires JDK 17, the Android SDK (API 36), NDK, and CMake -- Gradle will resolve the SDK
components it needs via the Android SDK Manager as long as `local.properties` points
`sdk.dir` at an SDK install. `whisper.cpp` and `llama.cpp` are git submodules pinned to
known-good tags; `--recurse-submodules` (or `git submodule update --init --recursive`
afterward) is required before the native modules will build.

## Releases

Stable builds are tagged `vX.Y.Z` and marked Latest. Betas are tagged `vX.Y.Z-beta.N` and
published as prereleases -- Obtainium skips them unless you enable **Include prereleases**.
See [docs/RELEASING.md](docs/RELEASING.md).

## Architecture

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## License

MIT -- see [LICENSE](LICENSE).
