# Recording Limit Design

**Date:** 2026-08-30
**Status:** Approved
**Scope:** Bound the PCM capture buffer in `AudioRecorder`; add a user-facing recording limit
setting; cut the allocation multiplier at `stop()`.

## Problem

`AudioRecorder` accumulates 16 kHz mono PCM16 into a `ByteArrayOutputStream` with no limit and no
disk spill. Capture runs at 32,000 bytes/sec — 1.92 MB per minute — and nothing stops it.

The peak cost is not 1× the recorded bytes but roughly **5×**, because at `stop()` three copies
coexist:

- the `ByteArrayOutputStream` internal array, which doubles on growth — up to **2×**
- `buffer.toByteArray()`, a full copy — **1×**
- the `FloatArray` from `pcm16ToFloat`, at 4 bytes per sample against 2 — **2×**

| Recording | PCM bytes | Peak Java heap |
|---|---|---|
| 1 min | 1.9 MB | ~10 MB |
| 10 min | 19 MB | ~96 MB |
| 30 min | 58 MB | ~288 MB |

Android's per-app Java heap ceiling is typically 256–512 MB, so this fails somewhere around 20–40
minutes — as an `OutOfMemoryError`, which kills the process and the foreground service with it. The
user loses the dictation and receives no message.

Two secondary defects in the same buffer:

1. **`stop()` never resets.** Only `cancel()` and `start()` call `buffer.reset()`. The full PCM byte
   array therefore stays reachable through the entire transcribe→cleanup pipeline and after the
   service finishes, until the next recording begins. The privacy table in `HANDOFF.md` claims
   "`buffer.reset()` on stop and cancel"; that is true of cancel, not of stop.
2. **The realistic long recording is an unstopped one.** Not someone dictating an essay — someone
   who taps start, switches apps, and puts the phone in a pocket. That is a hot microphone in an app
   whose entire premise is privacy.

There is already an effective ceiling downstream: the cleanup model's 512-token cap covers roughly
2.5 minutes of speech. A limit does not impose a new restriction so much as make an existing one
honest and graceful.

## Decisions (agreed in brainstorming)

1. **Auto-finalize at the limit.** Capture stops and the normal transcribe→cleanup→insert path runs
   on what was captured. Nothing spoken is discarded. This follows the precedent set in onboarding,
   where `startRecording()` was changed specifically so that a dictation already spoken could never
   be thrown away.
2. **Enforce a byte budget inside `AudioRecorder`, not a timer in the service.** A timer bounds
   *time*, not memory, and is only as good as its firing. A byte budget on the write path makes
   overflow structurally impossible — the same reasoning that makes an unbalanced pin
   unrepresentable in `withModels { }`.
3. **No disk spill.** `README.md:25` promises "no audio is ever retained". A spill file survives a
   crash and is the same class of leak `LocalScribeBackupAgent` was written to close.
4. **User-configurable, defaulting to 2 minutes**, via a notched slider up to 10 minutes, with a
   confirmation dialog on any selection of 5 minutes or more.
5. **Cut the allocation multiplier from 5× to 3×** as part of this work, since it determines what a
   given limit costs and therefore whether a 10-minute ceiling is defensible.

## Components

### `RecordingLimit` (new, `audio/RecordingLimit.kt`)

Enum of the selectable notches — 1, 2, 3, 5, 10 minutes. Pure; no Android dependency.

- `minutes: Int`
- `id: String` — persistence key, following the `WhisperModelTier` / `CleanupModelTier` convention
- `bytes: Long` — `minutes * 60 * SAMPLE_RATE_HZ * 2`
- `requiresConfirmation: Boolean` — `minutes >= 5`
- Default is `TWO`.

### `RecordingBudget` (new, `audio/RecordingBudget.kt`)

The running budget the recording thread consults. Pure; no Android dependency.

- `accept(count: Int): Int` — how many of the bytes just read may be kept. Returns the full count
  below the limit, a partial count for the chunk that straddles the boundary, and 0 past it.
- `isFull: Boolean` — budget exhausted.

Separating this from `RecordingLimit` keeps the enum immutable and the mutable running state in one
small testable place.

### `AudioRecorder` (modified)

- Constructed with a `RecordingLimit`; builds a `RecordingBudget` at `start()`.
- The recording thread writes only `budget.accept(read)` bytes, and exits its loop when
  `budget.isFull`, invoking an `onLimitReached` callback.
- **Storage change:** `ByteArrayOutputStream` is replaced by a `MutableList<ByteArray>` of
  exactly-sized chunks — no doubling, no `toByteArray()` copy. At `stop()` the final `FloatArray` is
  allocated once and filled chunk by chunk, each chunk reference dropped as it drains. Peak becomes
  1× (chunks, draining) + 2× (floats) ≈ **3×**.
- **`stop()` releases the chunk list** once the `FloatArray` is built, so PCM does not stay resident
  through the pipeline. This makes the `HANDOFF.md` privacy claim true as written.

Resulting peaks:

| Limit | PCM | Peak before (5×) | Peak after (3×) |
|---|---|---|---|
| 2 min | 3.8 MB | ~19 MB | **~11 MB** |
| 5 min | 9.6 MB | ~48 MB | **~29 MB** |
| 10 min | 19 MB | ~96 MB | **~58 MB** |

### `AppPreferences` (modified)

Adds `recordingLimit`, following the existing shape exactly:

```kotlin
var recordingLimit: RecordingLimit
    get() = prefs.getString(KEY_RECORDING_LIMIT, null)
        ?.let { id -> RecordingLimit.entries.find { it.id == id } }
        ?: RecordingLimit.TWO
    set(value) = prefs.edit().putString(KEY_RECORDING_LIMIT, value.id).apply()
```

An id this build does not recognize falls back to the default, as every other setting in the file
does.

### `DictationForegroundService` (modified)

- Reads `preferences.recordingLimit` in `startRecording()` and constructs the recorder with it,
  alongside the existing pre-microphone permission and model checks.
- Passes an `onLimitReached` callback that routes to the same path as `ACTION_CONFIRM`. The callback
  arrives on the recording thread, so it posts to the main thread before touching service state.
- **The cancel/limit race resolves in favour of cancel.** If the user taps cancel at the moment the
  budget fills, the posted callback lands after `isRecording` is already false and takes the existing
  `if (!isRecording) { stopSelf(); return }` early exit. A cancelled dictation is never resurrected
  and transcribed by the limit path.
- When the limit is what stopped the recording, the notification shows a distinct string —
  "Recording limit reached — processing…" — rather than the generic "Processing…". No new UI
  surface; reuses `updateNotification`.

### `SettingsScreen` (modified)

A new `SettingsSection(title = "Recording limit")`, placed after "Cleanup style".

- A Compose `Slider` over **indices** `0..4` with `steps = 3` — the notches are unevenly spaced
  (1, 2, 3, 5, 10), so the position maps into `RecordingLimit.entries` rather than onto minutes.
- Below it, the current value in words: "Stops automatically after 2 minutes."
- The dialog fires on **`onValueChangeFinished`**, not `onValueChange`. Otherwise dragging from 1 to
  10 trips the warning as the thumb passes 5.
  - Lands below 5 → persist immediately, no dialog.
  - Lands on 5 or 10 → do not persist yet; show the dialog. Confirm persists; cancel or dismiss
    snaps the thumb back to the last saved value.
  - Re-fires on 5 → 10. Ten minutes is a larger claim than five and the copy names the actual
    number, so it is re-consented rather than inherited.

Dialog copy, modeled on `ModelRow`'s download dialog, which states its cost concretely in MB:

> **Allow recordings up to 10 minutes?**
>
> A recording this long takes much longer to process — roughly 20 seconds of transcription after you
> stop, against about 2 seconds for a typical dictation.
>
> Cleanup can also only see about 2.5 minutes of speech at once, so anything past that is
> transcribed but only lightly cleaned up.
>
> Recording still stops on its own at the limit.

The duration figures are computed from the selected notch (5 min → ~10 s, 10 min → ~20 s) using the
measured ~30× realtime transcription rate. The second paragraph is the more important of the two:
the cost past 2.5 minutes is transcript *quality*, not only time.

## Data flow

```
startRecording()
  └─ AudioRecorder(limit = preferences.recordingLimit, onLimitReached = ...)
       └─ thread: read → budget.accept(read) → chunks
            └─ budget.isFull → exit loop → onLimitReached
                 └─ (main thread) same path as ACTION_CONFIRM,
                    notification reads "Recording limit reached — processing…"

stop()
  └─ allocate FloatArray once, fill from chunks, drop each chunk
       └─ release chunk list  ← PCM no longer resident during the pipeline
```

## Testing

The project has no Robolectric; pure logic is unit-tested and Android glue is not. `AppPreferences`
has no test today and does not gain one here — adding a test dependency for one accessor is not
worth it.

- **`RecordingBudgetTest`** — a full chunk under budget is accepted whole; the chunk that straddles
  the boundary is truncated to the exact remainder; subsequent chunks return 0 and `isFull` is true;
  an exact-boundary chunk fills the budget without over-accepting; the return value is never
  negative.
- **`RecordingLimitTest`** — byte conversion is correct at each notch; `requiresConfirmation` is
  true at 5 and 10 and false at 1, 2, 3; the default is `TWO`.

## Out of scope

- **Voice activity detection.** Auto-stop on silence is the graceful answer to the unstopped
  recording, and remains on the handoff's list as separate work. A limit is the backstop, not a
  replacement for it.
- **A visible countdown in the recording pill.** Considered and set aside; the notification string
  covers the "why did it stop" question without new overlay UI.
- **Any change to the 512-token cleanup cap.** The dialog copy names that ceiling honestly; moving
  it is a different piece of work.
