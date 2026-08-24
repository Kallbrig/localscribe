# Model Lifecycle Design

**Date:** 2026-08-24
**Status:** Approved
**Scope:** Resident model management (whisper + Qwen), LlamaBridge leak fix, cleanup-honesty surfacing.

## Problem

`DictationForegroundService.confirmAndProcess` loads both native models from disk on every
dictation: `WhisperBridge.load(...)` and `AutoCleaner(...)` (which loads the Qwen GGUF through
`LlamaBridge.load`). Every dictation therefore pays full model-load latency before inference
starts. Worse, the `LlamaBridge` created inside `AutoCleaner` is never released — only the
whisper bridge is freed in the service's `finally` block — so the Qwen native context leaks on
every dictation that has a cleanup model installed.

Separately, when cleanup falls back from the Qwen LLM to `RuleBasedCleaner` (load failure or
faithfulness-check rejection), the user is never told; the raw/rules-cleaned transcript is
inserted silently.

## Decisions (agreed in brainstorming)

1. **Residency policy: pre-warm on focus + idle unload.** Model loading starts when an editable
   field gains focus (the moment the bubble appears). Models unload after an idle timeout and on
   system memory-pressure callbacks.
2. **Low-RAM policy: whisper pre-warms everywhere; Qwen pre-warms only on devices with
   RAM ≥ `RamTier.CLEANUP_UPGRADE_MIN_GB` (6 GB).** Below that, Qwen loads lazily on the first
   actual dictation, so transcription latency is always protected.
3. **Architecture: process-level singleton.** A `ModelSession` object beside
   `DictationController`, following the same in-process shared-state pattern already used by the
   three services (accessibility, overlay, foreground), which run in one process.
4. **Cleanup honesty: minimal surfacing in this chunk.** Record which cleaner backend produced
   each transcript, badge non-LLM transcripts in history, and toast when a fallback insertion
   happens.

## Components

### `ModelSession` (new, `dictation/ModelSession.kt`)

Process-level singleton owning both native bridges behind a `kotlinx.coroutines.sync.Mutex`.

- `prewarm(context)` — idempotent; launches background loads of whisper always, and Qwen when
  total device RAM ≥ 6 GB. Safe to call repeatedly (focus events fire often). Load failures are
  logged, never thrown — pre-warming is best-effort.
- `suspend acquire(context): LoadedModels` — returns `LoadedModels(whisper: WhisperBridge,
  cleaner: AutoCleaner)` for a dictation, loading anything not yet resident (this is where Qwen
  loads lazily on low-RAM devices). Whisper load failure throws (surfaced as the existing
  `DictationUiState.Error`); cleanup-model failure degrades to the rules-only `AutoCleaner`,
  matching current behavior.
- **Idle unload** — a 5-minute timer (single named constant) starts when focus is lost or a
  dictation completes, is reset by any pre-warm/acquire activity, and never fires while a
  dictation is in flight. On expiry both bridges are released.
- `onTrimMemory(level)` — releases both bridges on critical memory-pressure levels. Registered
  through a new `LocalScribeApplication` class (the app currently has none) implementing
  `ComponentCallbacks2`.
- `invalidate()` — releases both bridges so the next acquire/prewarm loads fresh; called when
  the user changes the whisper or cleanup model tier in settings.
- Model-file resolution (`ModelManager.ensureWhisperModel` / `ensureCleanupModel` +
  `AppPreferences` tiers) moves inside `ModelSession` so callers never touch paths.

### `AutoCleaner` refactor (leak fix)

`AutoCleaner` implements `AutoCloseable`; `close()` releases its `LlamaBridge` if one was
loaded. `ModelSession` is the sole owner and always closes it on unload, invalidate, and
trim-memory. This fixes the leak structurally rather than patching the one call site.

### `Cleaner` result type (honesty surfacing)

`Cleaner.clean(...)` returns `CleanResult(text: String, backend: CleanupBackend)` instead of a
bare `String`, where `CleanupBackend` is:

- `QWEN` — LLM output accepted,
- `RULES` — rules cleaner ran because no LLM was loaded,
- `RULES_FALLBACK` — Qwen ran but its output was empty or failed `TextCleanupUtils.isFaithful`.

`Transcript` carries the backend; `TranscriptEntity` gains a `cleanupBackend` column (Room
schema v2; migration defaults existing rows to a distinct `UNKNOWN` value so old history rows
show no badge).

### Service wiring

- `DictationAccessibilityService`: editable focus gained → `ModelSession.prewarm(...)`; focus
  lost everywhere (state → Hidden) → start the idle timer.
- `DictationForegroundService.confirmAndProcess`: replace the per-dictation loads with
  `ModelSession.acquire(...)`; remove the `finally` release (report dictation-complete to
  `ModelSession` instead, which restarts the idle timer). Error behavior is otherwise unchanged.
- Settings screen: changing either model tier calls `ModelSession.invalidate()`.

### UI

- History rows show a subtle badge when `cleanupBackend` is `RULES` or `RULES_FALLBACK`
  (e.g. "basic cleanup"); `QWEN` and `UNKNOWN` rows show nothing.
- When a transcript whose backend is `RULES_FALLBACK` or `RULES` (with a cleanup model
  installed) is inserted, show a short toast: cleanup was skipped, raw/basic transcript
  inserted.

## Error handling

- Concurrent `prewarm` + `acquire`: serialized by the mutex; acquire awaits an in-flight load
  rather than double-loading.
- Unload during an active dictation: impossible by construction — the idle timer is suspended
  between acquire and dictation-complete.
- Process death: nothing to persist; models reload on next focus.

## Testing

- `ModelSession` unit tests against a fake loader interface (no JNI): prewarm idempotence and
  load counts, lazy Qwen below the RAM threshold, idle-timer unload, timer suspension during an
  in-flight dictation, invalidate-on-settings-change, trim-memory unload, whisper-failure
  propagation vs. cleanup-failure degradation.
- Updated domain tests for `CleanResult` (QwenCleaner faithful/unfaithful paths report the
  right backend).
- Room migration test for schema v1 → v2.
- Manual golden path on the emulator: after focusing a field and waiting for pre-warm, a
  dictation should start transcribing with no model-load stall; a second dictation should reuse
  resident models.

## Out of scope

CMake perf flags and quantized/distil model catalog, VAD, chunked/streaming transcription,
instrumented text-insertion tests. Each is a separate spec.
