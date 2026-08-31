# CPU Support Gate Design

**Date:** 2026-08-31
**Status:** Approved
**Scope:** Detect the ARMv8.2 CPU features the native modules are compiled for, before any native
library is loaded, and refuse the native paths with an explanation instead of crashing with SIGILL.

## Problem

Both native modules compile their whole numerical stack for ARMv8.2:

```cmake
if (ANDROID_ABI STREQUAL "arm64-v8a")
    add_compile_options(-march=armv8.2-a+fp16+dotprod)
endif()
```

`+fp16` (FEAT_FP16) is what makes the f16 ggml models fast; `+dotprod` (FEAT_DotProd) is what makes
the q4_k_m cleanup model's matmuls affordable. Both are ARMv8.2 features, available on roughly
2018-and-later cores. On an older arm64 CPU the app does not run slowly — it executes an
instruction the silicon does not implement and the kernel kills the process with **SIGILL**.

`minSdk 28` does not close this. It gates on the OS version, and CPU generation is independent of
it: a Pixel 2 has a 2017 Snapdragon 835 (ARMv8.0) and runs Android 11. It passes the `minSdk` check,
installs, and crashes. Android offers no manifest mechanism to gate installation on CPU features —
there is no `<uses-feature>` for an ARM extension — and distribution is via GitHub/Obtainium rather
than Play, so ABI-targeted delivery is not available either. **Runtime is the only place this can be
caught.**

The crash is silent and total: no message, no log the user will ever see, and it repeats on every
launch that reaches inference.

### Where the crash actually happens

Not precisely characterized, and deliberately not relied upon. The certain execution of ARMv8.2
instructions is inside transcription and generation. But `-march` applies to the entire translation
unit set, including any static initializers, and `dlopen` runs `.init_array` — so the fault may
occur at `System.loadLibrary` time rather than at first inference.

`System.loadLibrary` sits in the `companion object` initializer of `WhisperBridge` and `LlamaBridge`,
so it fires the moment either class is first used. **The check must therefore run before those
classes are touched at all**, not inside them. That single constraint drives the whole design.

## Decisions (agreed in brainstorming)

1. **Runtime feature check that fails gracefully**, not dual library variants. The check makes an
   unsupported device fail *honestly*; it does not make it work. Dual variants (whisper.cpp's own
   approach) would actually run on 2015–2017 hardware, but they roughly double an already ~8-minute
   native build and the APK's native payload, and — decisive here — the baseline path cannot be
   verified without a pre-2018 device. Shipping unverified native code unattended is a worse trade
   than shipping an honest refusal. Dual variants stay recorded as the real fix.
2. **Block the native paths only.** History, Settings, Vocabulary and Export touch no native code;
   blocking them buys nothing and would remove the user's data escape hatch.
3. **Parse `/proc/cpuinfo`.** Pure Kotlin, so the parser is a genuinely testable unit in the same
   shape as `RecordingBudget`, `TextSplice`, `HistorySelection` and `BackupPlan`. The alternative —
   a small baseline-compiled JNI library calling `getauxval(AT_HWCAP)` — reads authoritative kernel
   data rather than text, but costs a third Gradle module and is untestable off-device, which is
   precisely the wrong trade for work that ships without device access.
4. **Fail open.** If the CPU cannot be shown to *lack* the features, it is treated as supported. A
   false "unsupported" disables dictation on hardware that runs it perfectly — strictly worse than
   the crash being fixed. The gate may only fire on positive evidence of absence.

## Design

### `CpuSupport` — the pure unit

`platform/CpuSupport.kt`. No Android imports.

```kotlin
sealed interface CpuSupport {
    data object Supported : CpuSupport
    data class Unsupported(val missingFeatures: List<String>) : CpuSupport
}

fun evaluate(primaryAbi: String, cpuinfo: String?): CpuSupport
```

Evaluated in order:

| # | Condition | Result | Why |
|---|---|---|---|
| 1 | `primaryAbi` is not `arm64-v8a` | `Supported` | `-march` is applied only under `ANDROID_ABI STREQUAL "arm64-v8a"`. The x86_64 library is baseline and always safe. **Without this rule the x86_64 emulator is declared unsupported**, because its `/proc/cpuinfo` has `flags`, not `Features`, and none of the ARM tokens. |
| 2 | `cpuinfo` is null or blank | `Supported` | Fail open: unreadable `/proc` is not evidence of absence. |
| 3 | No line whose key is `Features` | `Supported` | Fail open: cannot prove absence. |
| 4 | Required tokens present on every `Features` line | `Supported` | |
| 5 | Otherwise | `Unsupported(missing)` | The only branch that disables anything. |

**Required tokens:** `asimdhp` and `asimddp`.

These map one-to-one onto the two extensions named in the compile flag: `asimdhp` is the kernel's
hwcap string for Advanced SIMD half-precision (`+fp16`), `asimddp` for dot product (`+dotprod`).

`armv8.2-a` also implies FEAT_LSE (`atomics`, an ARMv8.1 feature), and the compiler may emit LSE
atomics. It is deliberately **not** checked. Every core with `asimddp` necessarily has LSE — dot
product is an ARMv8.2 extension and LSE is mandatory from ARMv8.1 — so checking it adds no detection
power while adding false-negative surface on any kernel that under-reports it. That is the wrong
direction given decision 4.

**Intersection, not union, across cores.** `/proc/cpuinfo` prints one block per core, so a
`Features` line appears several times. A feature is required on *every* line: threads are scheduled
onto any core, so a feature missing from one core is missing for a thread that lands there. In
practice arm64 kernels present uniform hwcaps and the lines are identical; the intersection is the
correct reading either way and costs nothing.

**Whole-token comparison, not substring.** `"asimd" in line` would match `asimdrdm`; the feature list
genuinely contains overlapping prefixes (`asimd`, `asimdhp`, `asimdrdm`, `asimddp`). Split on
whitespace and compare tokens for equality.

### `DeviceCpu` — the thin glue

`platform/DeviceCpu.kt`. Everything untestable, kept to a few lines: reads
`Build.SUPPORTED_ABIS.firstOrNull()` and `/proc/cpuinfo`, hands both to `evaluate`, caches the result
for the process lifetime (the CPU does not change), and logs the verdict once. Any read failure
yields `null`, which rule 2 turns into `Supported`.

It takes **no `Context`**: `Build.SUPPORTED_ABIS` is a static field and `/proc/cpuinfo` is an
ordinary file read. So the verdict is reachable from anywhere — services, composables, plain
objects — with no plumbing, which is what keeps the gates below to one line each.

### Gates

Gates 1–3 sit in front of code that reaches `WhisperBridge` or `LlamaBridge`. Gate 4 is different in
kind and is treated differently.

1. **`DictationForegroundService.startRecording()`** — the load-bearing one, and the only path to a
   dictation. Refuses *before the microphone opens*, ahead of the existing permission and model
   checks, via `DictationUiState.Error(...)`. This is the same reasoning that already put the
   missing-model check there: a failure discovered after the user has spoken costs them the
   dictation.
2. **`ModelSession.prewarm()`** — returns early. Focus events must not trigger `WhisperBridge.load`,
   and prewarm is reached from accessibility events rather than from the service.
3. **`ModelSession.withModels()`** — throws before `engineFor`. Defense in depth: its only caller
   today is behind gate 1, but it is the documented dictation entry point and a future second
   caller must not be able to reach JNI through it.
4. **Model downloads** — disabled **in the UI only**, not in `ModelDownloadManager`. Downloading a
   model on an unsupported device is *pointless*, not dangerous: the download path never touches
   native code, and the dangerous operation — the load — is already covered by gates 1–3. So this
   is a matter of not offering the user a 150 MB–1.1 GB fetch that can never execute, which is a
   presentation concern. A guard inside the manager would have no user-visible voice (it could only
   set a `Failed` state that reads like a network error) and would duplicate a decision the notice
   above it already explains.

**Layering note and residual risk.** The check lives in the app module, not in `whisper-jni` /
`llama-jni`. Putting it inside the bridges would be airtight — that is exactly where
`System.loadLibrary` sits — but the two bridges are independent Gradle modules that do not depend on
app code, so it would mean either duplicating the logic or creating a shared module for one
function. Neither is worth it here. The consequence, recorded rather than hidden: **a future caller
that invokes `WhisperBridge.load` or `LlamaBridge.load` directly, rather than through
`ModelSession`, bypasses the gate.** `ModelSession` is already documented as the only dictation path
for the same reason.

### UI

A single `UnsupportedDeviceNotice` composable in `ui/common/`, used by both surfaces so they cannot
drift — the same reasoning that made `BackupChoicesSection` shared between onboarding and Settings.

It names what is wrong in plain language (this device's processor lacks features the speech engine
was built for), states the consequence (dictation cannot run; history, vocabulary and export still
work), and lists the missing feature tokens for anyone diagnosing it. Shown at the top of
`OnboardingScreen` and `SettingsScreen`. Model download controls are disabled while it is showing.

On a supported device — every device that runs the app today — nothing renders and nothing changes.

## Testing

Unit tests target `evaluate`, which is where all the behaviour is:

- A real ARMv8.2 `Features` line (Snapdragon 8-class) → `Supported`.
- A real ARMv8.0 `Features` line (Snapdragon 835 class, the Pixel 2 case) → `Unsupported`, listing
  both missing tokens.
- `fp16` present, `dotprod` absent → `Unsupported`, listing only `asimddp`.
- x86_64 emulator: `flags`-style cpuinfo with a non-arm64 ABI → `Supported` (rule 1).
- arm64 ABI with null, blank, and `Features`-less cpuinfo → `Supported` (rules 2 and 3).
- Multi-core input where one core's `Features` line lacks a token → `Unsupported` (intersection).
- Substring hazards: a line containing `asimdrdm` but not `asimddp` → `Unsupported`.

### What cannot be verified

Stated plainly because it will not be verified later either: **there is no pre-2018 arm64 device
available, and no device is attached at all.** The unsupported path is exercised by unit tests
against real captured `Features` strings and by nothing else. What protects working devices is the
fail-open design — every uncertain input returns `Supported`, so the worst outcome of a parsing
mistake is today's behaviour, not a bricked install.

## Out of scope

- Dual library variants. Remains the only fix that would make old hardware *work*; stays in
  Left Undone with its sizing and its verification blocker recorded.
- Moving the check into the JNI modules, or a shared module to hold it.
- `getauxval`-based detection.
