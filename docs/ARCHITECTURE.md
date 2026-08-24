# Architecture

LocalScribe separates its domain pipeline from platform and engine adapters, the same split
as the Windows desktop sibling:

```text
Focus event -> overlay bubble -> AudioRecorder -> WhisperTranscriber -> AutoCleaner -> insert
 (AccessibilityService)   (tap)                        |                    |        (3-tier)
                                                  custom vocabulary     cleanup mode
```

`DictationPipeline` depends only on the `Transcriber` and `Cleaner` interfaces
(`app/src/main/java/dev/chaseallbright/localscribe/domain/`) -- the same shape as the
desktop's `domain.py`/`pipeline.py`. Swapping the speech or cleanup engine, or hosting the
pipeline behind a different platform front end, only touches an adapter, never the pipeline
itself.

## Module layout

- **`:app`** -- Compose UI (onboarding, history, vocabulary, settings), the three services
  that drive dictation, the domain layer, Room persistence, and the model manager.
- **`:whisper-jni`** -- an isolated Android library module vendoring `whisper.cpp` as a git
  submodule, built via its own CMake configure. It's a separate Gradle module rather than a
  `src/main/cpp` folder inside `:app` because both `whisper.cpp` and `llama.cpp` vendor their
  own `ggml` CMake target; add_subdirectory-ing both into one configure pass collides on that
  target name, so each gets an isolated CMake build instead. Exposes `WhisperBridge`
  (init/transcribe/free) to Kotlin over JNI.
- **`:llama-jni`** -- same pattern for `llama.cpp`, exposing `LlamaBridge`
  (load model/generate/free) for short cleanup completions.

## Data flow

1. `DictationAccessibilityService` tracks the focused editable node across every app and
   drives `DictationController`'s Hidden/Idle state.
2. Tapping the overlay bubble starts `DictationForegroundService`, a mic-type foreground
   service that captures 16kHz mono PCM into memory via `AudioRecorder` (no streaming --
   the whole clip is buffered, matching the desktop's press/release model translated to
   tap-to-start/tap-to-finish).
3. On confirm, the service ensures both models are present (downloading via
   `ModelManager`/`ModelDownloader` if this is the first use of that tier), runs
   `DictationPipeline` (`WhisperTranscriber` -> `AutoCleaner`), and persists the result to
   Room history.
4. `AutoCleaner` prefers the local Qwen2.5 GGUF model through `llama-jni`; if it fails to
   load or its output isn't faithful to the source (see `TextCleanupUtils.isFaithful`), it
   falls back to `RuleBasedCleaner`, a deterministic regex-based cleaner ported from the
   desktop's `cleanup.py`. Dictation never blocks on the LLM.
5. `DictationController` publishes the finished transcript; `DictationAccessibilityService`
   -- the only component with a live `AccessibilityNodeInfo` reference to the target field --
   inserts it via `TextInsertion`'s three-tier fallback (`ACTION_SET_TEXT` -> clipboard +
   `ACTION_PASTE` -> clipboard-only + toast).

There are no outbound inference calls. Network access occurs only when Hugging Face
downloads a model you selected during setup or in Settings.

## Why three services instead of one

`OverlayBubbleService` (the WindowManager overlay UI), `DictationForegroundService`
(recording + pipeline), and `DictationAccessibilityService` (focus tracking + insertion)
each have a distinct Android lifecycle/permission story and can't be merged: only an
`AccessibilityService` gets a live `AccessibilityNodeInfo`, only a foreground service can
run `AudioRecord` reliably while the app has no visible UI, and the overlay needs its own
`TYPE_APPLICATION_OVERLAY` window. They coordinate through `DictationController`, an
in-process `StateFlow`/`SharedFlow` holder -- simpler than Binder/Messenger IPC since all
three run in the same process.

## Extension roadmap

- **Additional engines:** any transcriber/cleaner implementing the two interfaces in
  `domain/Transcriber.kt` and `domain/Cleaner.kt` is a drop-in replacement, independently
  testable without touching `DictationPipeline`.
- **Streaming transcription:** explicitly out of scope for this build (see the design spec's
  non-goals) -- would require replacing the buffer-then-transcribe flow in
  `DictationForegroundService` with incremental `whisper_full` calls.
