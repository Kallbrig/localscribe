# Changelog

All notable changes follow [Keep a Changelog](https://keepachangelog.com/) and semantic versioning.

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
