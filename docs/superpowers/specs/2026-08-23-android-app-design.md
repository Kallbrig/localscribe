# Whisper Flow Android — Design Spec

Status: Approved by user 2026-08-23. Build proceeds without further check-ins.

## 1. Purpose

A standalone, open-source Android app that replicates the WhisperFlow UX: focus any text
field in any app, tap a floating draggable mic bubble, speak, tap a checkmark, and the
cleaned-up transcript is inserted directly into that field (or copied to the clipboard if
direct insertion isn't possible). Everything runs on-device: speech-to-text and text
cleanup both execute locally via native ML runtimes, with no cloud calls, no accounts, no
telemetry — same philosophy as the sibling desktop project, LocalScribe Flow, but this is a
**fully independent product**. No code, data, settings, vocabulary, or history is ever
shared or synced between the two. They do not know about each other at runtime.

Reference desktop project (read-only inspiration, not a dependency):
`C:\Users\Owner\Desktop\whisper-flow-alt` — see its `docs/ARCHITECTURE.md`, `domain.py`,
`pipeline.py`, `cleanup.py`, `models.py` for the protocol-based design this app's Kotlin
domain layer mirrors conceptually.

## 2. Target UX (from user-provided screenshots)

In any app (e.g., an SMS/RCS thread), focusing a text field shows a small circular floating
mic bubble (draggable, always-on-top). Tapping it:

1. Expands into a horizontal pill containing: cancel (X) — live recording indicator — confirm
   (checkmark).
2. Recording runs until the user taps the checkmark (or X to cancel/discard).
3. On checkmark: pill shows a brief processing state, the pipeline runs, and the resulting
   text is inserted into the focused field. The pill then collapses back to the idle bubble
   (or hides if focus has moved off any editable field).

## 3. Architecture

```
Focus event (AccessibilityService) -> floating overlay bubble/pill
    -> user taps record -> ForegroundService starts capture (16kHz mono PCM)
    -> user taps checkmark -> stop capture
    -> whisper.cpp (native, JNI) transcribes -> raw text
    -> llama.cpp (native, JNI) + Qwen2.5 GGUF cleans up -> cleaned text
       (falls back to deterministic rule-based cleanup if the LLM fails to load/run)
    -> AccessibilityNodeInfo.ACTION_SET_TEXT on focused node
       -> else: clipboard + AccessibilityNodeInfo.ACTION_PASTE
       -> else: clipboard only, toast "Copied — paste manually"
    -> transcript (raw + cleaned) saved to local Room history (independent of desktop)
```

Language/toolchain: Kotlin + Jetpack Compose for UI, Gradle Kotlin DSL, NDK/CMake for
native modules. Min SDK 26 (Android 8.0) — needed for reliable
`AccessibilityNodeInfo.ACTION_SET_TEXT` support and modern overlay window types. Target SDK
latest stable.

## 4. Components

- **`app` module** — Compose UI: onboarding/permissions screens, settings, vocabulary
  editor, history/search screen.
- **`AccessibilityService`** (`DictationAccessibilityService`) — subscribes to
  `TYPE_VIEW_FOCUSED` / `TYPE_WINDOW_CONTENT_CHANGED`; shows the overlay bubble only when the
  focused node `isEditable`; hides it when focus moves to a non-editable node or a
  system/self window. Also owns text insertion once a transcript is ready (it's the only
  component with a live `AccessibilityNodeInfo` reference to the target field).
- **Overlay** (`OverlayBubbleService` or equivalent, `WindowManager` +
  `TYPE_APPLICATION_OVERLAY`) — draggable idle bubble and the recording pill, built in
  Compose hosted in a `ComposeView` added to the window manager. Pure UI/gesture state; no
  ML logic.
- **`DictationForegroundService`** (mic-type foreground service) — owns the audio recorder
  and drives the pipeline (`whisper.cpp` -> `llama.cpp`/fallback) off the UI thread; posts
  the required persistent notification while recording/processing (Android 14+ mic
  foreground-service compliance).
- **Native layer** — two CMake-built native libraries:
  - `whisper-jni` — vendors `ggml-org/whisper.cpp` (git submodule), JNI bridge adapted from
    its official `examples/whisper.android`.
  - `llama-jni` — vendors `ggml-org/llama.cpp` (git submodule), JNI bridge adapted from its
    official `examples/llama.android`.
- **Model manager** — resumable, checksum-verified downloads from Hugging Face into
  app-private storage (`context.filesDir`), mirroring the retry/resume/verify logic in the
  desktop's `models.py`. RAM-tiered defaults, chosen at first run via
  `ActivityManager.MemoryInfo`:
  - Whisper (ggml, quantized): `tiny.en` (<3 GB RAM), `base.en` (3–6 GB, default),
    `small.en` (>6 GB, optional upgrade in settings).
  - Cleanup LLM: Qwen2.5 **0.5B** Q4_K_M GGUF as the universal default (mobile RAM is
    scarcer than desktop's own low-RAM tier); Qwen2.5 1.5B Q4_K_M offered as an opt-in
    "higher quality" setting on >=6 GB RAM devices.
- **Domain layer** (Kotlin, pure logic, unit-testable) — `Transcriber` / `Cleaner`
  interfaces and a `DictationPipeline` orchestrator, directly mirroring
  `domain.py`/`pipeline.py`'s shape. `CleanupMode` enum: Informal / Casual / Standard /
  Business — same four as desktop, independently implemented prompts/rules.
- **History & vocabulary** (Room/SQLite, Android-only schema) — transcript history
  (raw + cleaned text, timestamp, mode, duration, language) with search; custom vocabulary
  list fed into both the Whisper initial prompt and preserved through cleanup, same concept
  as desktop's feature, separate storage.
- **Permissions onboarding** — first-run flow requesting, in order: `RECORD_AUDIO`,
  "Display over other apps" (`Settings.ACTION_MANAGE_OVERLAY_PERMISSION`), Accessibility
  Service enablement (deep link to system Accessibility settings — cannot be
  programmatically granted), `POST_NOTIFICATIONS` (Android 13+). App is inert/prompts to
  finish setup until all four are granted.

## 5. Error handling / fallbacks

- Cleanup LLM fails to load or errors at runtime -> deterministic rule-based cleaner (ported
  from `cleanup.py`'s existing non-LLM fallback) runs instead; dictation never blocks on the
  LLM.
- Text insertion: `ACTION_SET_TEXT` -> clipboard + `ACTION_PASTE` -> clipboard-only + toast,
  per section 3. Never lose the transcript even in the worst case.
- Model download interrupted -> resumable on next attempt (byte-range resume + retry,
  matching desktop), corrupt/incomplete cache detected and re-downloaded automatically.
- Accessibility service disabled mid-use (user revokes permission) -> overlay hides itself
  and app surfaces a "re-enable in Settings" prompt rather than crashing.

## 6. Non-goals for this build

- No streaming/live partial transcription — record fully, then transcribe, matching the
  desktop's press/release model translated to tap-to-start/tap-checkmark-to-finish.
- No Play Store distribution — the required permission set (Accessibility Service +
  `SYSTEM_ALERT_WINDOW`) is heavily scrutinized/restricted there; this ships as a sideloaded
  release APK from GitHub Releases only.
- No sync/interop of any kind with the desktop LocalScribe Flow project.

## 7. Delivery

Local git repo only (no GitHub push performed by the agent — no `gh` auth available in this
environment). Deliverable: a fully committed repo, a GitHub Actions release workflow ready
to run on tag (unrun), and — the actual bar for "done" — a built, installed-and-verified
release APK produced in this session. Build-toolchain acquisition (JDK 17, Android
SDK/NDK/CMake, Gradle) happens via direct portable downloads into a project-local tools
directory rather than system package managers, to stay non-disruptive to the host machine.
