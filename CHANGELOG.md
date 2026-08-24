# Changelog

All notable changes follow [Keep a Changelog](https://keepachangelog.com/) and semantic versioning.

## [Unreleased]

### Fixed

- Overlay bubble would flicker back to hidden immediately after correctly appearing:
  `TYPE_WINDOW_CONTENT_CHANGED` accessibility events (whose `source` is frequently an
  unrelated parent container, not the focused view) were being used to clear focus
  tracking. Focus tracking now relies solely on `TYPE_VIEW_FOCUSED`.
- Overlay bubble would then disappear again ~300ms later: `TYPE_WINDOW_STATE_CHANGED`
  fires for the on-screen keyboard's own window opening, not just real app switches, and
  that was being treated as "user left the app." Now compares the actual foreground
  `TYPE_APPLICATION` window's package against the focused field's app before clearing.
- `assembleRelease` was producing an unsigned APK (no `signingConfig` on the release build
  type).
- Native libraries (whisper-jni, llama-jni) were arm64-v8a-only, matching real hardware
  but making the app unable to load on x86_64 emulators/devices with no ARM translation
  (`UnsatisfiedLinkError`). Added x86_64 as a second build target, per the plan's own
  emulator-testing allowance.

### Verified (Phase 9, Android 14 x86_64 emulator)

- Full golden path end-to-end: focus a field in a real third-party app (Messages compose,
  matching the spec's own example) -> tap bubble -> record -> confirm -> first-run model
  download (~540MB) -> whisper.cpp transcription -> cleanup -> `ACTION_SET_TEXT` insertion
  -> transcript saved to History. All four onboarding permissions, the Vocabulary
  add/list/delete flow, and the Settings model-tier/cleanup-mode pickers were also
  exercised directly on-device.

## [0.1.0] - 2026-08-24

### Added

- Android app with a floating draggable mic bubble: focus any text field in any app, tap to
  dictate, tap the checkmark to insert the cleaned-up transcript.
- Local speech-to-text via `whisper.cpp` (ggml `tiny.en`/`base.en`/`small.en`, RAM-tiered
  default) and local cleanup via `llama.cpp` running a Qwen2.5 GGUF model (0.5B default,
  1.5B opt-in), with a deterministic rule-based fallback if the LLM can't load.
- Four cleanup modes (informal, casual, standard, business), matching the desktop sibling.
- Custom vocabulary fed into transcription and preserved through cleanup.
- Three-tier text insertion (direct field insertion, clipboard+paste, clipboard-only) so a
  transcript is never silently lost.
- Resumable, verified model downloads with RAM-tiered defaults.
- Local transcript history with search; no audio is ever retained.
- Settings for model tier, cleanup mode, and a live permission-status dashboard.
- No accounts, no telemetry, no network access except downloading models you chose.
