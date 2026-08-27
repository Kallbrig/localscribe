# LocalScribe Android — Handoff

_Last updated: 2026-08-27. Repo: https://github.com/Kallbrig/localscribe (public). Default branch `master`._

**Stable: `v0.1.7`. In flight: `v0.2.0-beta.1`.** Unit suite: 135 tests, all passing.

Verified on a Galaxy S25 Ultra (Android 16, 8 cores, 11.4 GB RAM, arm64-v8a).

Build with `./gradlew :app:assembleDebug`. `JAVA_HOME` **must** point at the repo's vendored
`.tools/jdk17` — the system JRE is 32-bit Java 8 and cannot run the build. `keytool` is not on
PATH either; use `.\.tools\jdk17\bin\keytool.exe`.

---

## Release process

Two channels, decided by the tag alone. Full detail in [RELEASING.md](RELEASING.md).

| Tag | Channel | Obtainium |
|---|---|---|
| `vX.Y.Z-beta.N`, `-rc.N` | prerelease, not marked Latest | skipped unless the user opts in |
| `vX.Y.Z` | stable, marked Latest | installed |

Work accumulates as betas and ships once under a version that means something. Every release
shares certificate `73ef2d6d…`, so all of them install over one another.

CI builds and publishes both channels end to end. The gates, in order: tag channel recognised →
tag matches `versionName` → keystore verified against its SHA-256 and opened with the supplied
password (this runs *before* the ~8 minute native build, so a bad secret fails in ~50 seconds and
names which one) → tests and release build → `apksigner` confirms not debug-signed.

**The keystore exists in exactly one place.** `localscribe-release.jks` and its password live only
on the local machine, both gitignored. Losing either means never being able to update anyone who
installed v0.1.1 or later. Back both up off that machine.

---

## What changed in this session

### Release signing, and a pipeline that had never worked

Release builds were signed with the **debug keystore**, whose password is public and which CI
regenerates per run. Consecutive releases would have carried different signatures and been
uninstallable over one another (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`, taking transcript history
with them).

- Signing resolves from `keystore.properties` (gitignored; template committed as
  `keystore.properties.example`) then `LOCALSCRIBE_*` environment variables. No debug fallback.
- A `taskGraph.whenReady` guard fails any `assemble*/bundle*/package*Release` task outright when
  signing is unconfigured, so a tag cannot quietly publish an unsigned APK. Debug builds unaffected.
- v3 signing enabled — it carries a certificate lineage, the only mechanism for rotating this key
  later. At `minSdk 28` apksigner emits v3 alone and reports v2 absent; that is correct.
- **`gradlew` was committed as mode `100644`.** Every CI and release job since the repo was created
  died with "Permission denied" (exit 126) before Gradle started. It stayed invisible because the
  push trigger pointed at `main` while the branch is `master`, and no PR has ever been opened. Both
  fixed. The "67 tests passing" claim in the previous handoff had only ever been true locally.
- Getting the first release out took four distinct failures: the base64 secret corrupted by
  PowerShell's pipe (it applies console encoding and line-wrapping to strings piped into a native
  command — use `--body`, not a pipe), the `gradlew` bit, a bad secret only surfacing eight minutes
  in, and a wrong password. v0.1.1 and v0.1.2 were published from the local machine as a workaround;
  v0.1.3 onward are CI-built.

### Cleanup styles are a real gradient

Informal and business produced near-identical text. The prompt was only half the cause; the
dominant one was `isFaithful` rejecting any output introducing more than 30% new content words —
which is precisely what a business rewrite *is* — so business silently fell back to the mode-blind
`RuleBasedCleaner`.

| Style | Grammar & punctuation | Word choice | Budget | Uses LLM |
|---|---|---|---|---|
| Informal | left alone | exact | 0.05 | **no** |
| Casual | corrected | exact | 0.15 | yes |
| Standard | corrected | tightened | 0.35 | yes |
| Business | corrected | rewritten | 0.70 | yes |

- **Informal does not run the LLM.** `VerbatimFormatter` is deterministic, so substitution is
  *unrepresentable* rather than merely rejected. A 0.5B model cannot be reliably talked out of
  tidying — it returned `Hey man, what's up?` for `hey man what's going on`, swapping words as well
  as punctuation — and rejecting that afterwards spends a model load to reach the same place.
- Whisper emits prose-formatted text (sentence case, commas, terminal punctuation). Informal's job
  is undoing that. Kept on purpose: apostrophes already present (removing them degrades the
  transcript rather than declining to correct it), the pronoun "I", acronyms, custom vocabulary, and
  names — a word Whisper capitalised anywhere other than a sentence start, which then stays
  capitalised everywhere. Major sentence breaks survive as a bare full stop; the text does not end
  on one. Disfluencies go, slang stays.
- **No flat threshold can work.** A reply to "Hi, how are you?" scores 0.50 and must be rejected; a
  legitimate business rewrite scores 0.67 and must be accepted. Source *length* separates them — a
  three-word denominator is noise, and short dictations are exactly where a model is most likely to
  reply rather than edit. Relaxed budgets apply only above 8 source content words.
- Guards holding unconditionally in every mode: a dictated question stays a question, the text
  cannot grow, and a figure never dictated is rejected. Those are what stop the model answering
  rather than editing; the vocabulary budget is the only part that varies.
- The length cap was `max(len × ratio, len + 80)` with a flat slack, so at a typical 74-character
  dictation business and standard both capped at 154 and business's stricter ratio never applied.
  Slack is now per-mode. Caught in self-review, not by a test — the comment claimed a conciseness
  the code did not enforce.
- `CleanupBackend.VERBATIM` keeps informal out of the "basic cleanup" tag and the fallback toast.

### History you can act on

- Tap to expand: raw beside cleaned, with copy, share and delete. Raw is **always** shown, labelled
  "unchanged" when identical — hiding it on a match looks like a bug when the row was opened
  precisely to compare.
- Long-press starts multi-select; select-all and bulk delete via `deleteByIds`. Deletions confirm
  and state exactly what they remove.
- `HistorySelection` is a pure value class. Selection is held **by id and pruned against visible
  rows** — the list is a live Flow, so a new dictation or a changed query re-filters underneath it,
  and index-based selection would delete rows the user could not see.
- Expanding is disabled during multi-select: one gesture with two meanings is how you delete the
  wrong transcript.
- Footer durations were one unlabelled figure that was **audio length** (`samples.size / 16000`) but
  read as processing time. Stage timings are now persisted (Room v2→v3) and every figure labelled:
  `Informal · 16.6s spoken · 512ms to transcribe · 1.5s to clean up`. Rows written before this show
  no timing rather than a fabricated zero.

### Onboarding covers models

Onboarding ended at "You're all set" while no model was downloaded, so a first dictation failed
pointing at a Settings screen nothing had mentioned — after the user had already spoken.

- `ModelSetupRow` shows the speech model as required (size, progress, retry) and the cleanup model
  as explicitly optional, naming rules cleanup as the fallback.
- "You're all set" is gated on the speech model being on disk, not just permissions.
- `startRecording()` refuses to open the microphone without a speech model, so a missing one can no
  longer discard a dictation already spoken.
- Selecting an undownloaded model used to switch to it silently. It now asks, reports the size, and
  on confirm selects *and* downloads — in that order, so finishing leaves the user on the model they
  asked for.

### Backup was leaking transcripts

`android:allowBackup="true"` was set with no rules, so Android Auto Backup included app-private
storage by default — and the Room database holding every transcript sits in exactly the directory it
sweeps. **Nothing in the app's own code sent anything anywhere**; this was an inherited platform
default that contradicted the app's stated promise for three releases.

- `LocalScribeBackupAgent` overrides `onFullBackup` and hands over files individually, never calling
  `super` (the default sweep is the bug). Android's XML backup rules are static and cannot express a
  user setting, which is the entire reason the agent exists.
- **Every category defaults off**, master switch included. Existing choices survive: each flag reads
  with the default as fallback, so anyone who configured backup keeps what they picked.
- Onboarding presents the choice alongside permissions and models, via a component shared with
  Settings so the two cannot drift. It deliberately does not gate "You're all set" — off is the safe
  state, and blocking setup on it would train people to click through the one screen that matters.
- Transcripts and vocabulary share one database file and cannot be separated by path. Vocabulary
  alone travels as an export generated at backup time, merged back on first launch after a restore
  (merged, not replaced, so a restore does not discard words added before the backup arrived).
  Enabling transcripts pulls vocabulary along unavoidably; the UI says so.
- The WAL is checkpointed before the database is handed over, or recent dictations live only in the
  `-wal` and are missing from the restored copy.
- Models are never backed up. XML exclusions are kept as a fallback if the agent is ever removed.

### Export and import

A fully local device move, no cloud involved. Export writes one JSON file through the system file
picker, so no storage permission is needed and the user chooses where it lands.

- **JSON, not CSV.** A transcript is arbitrary dictated text containing commas, quotes and
  newlines — all CSV escaping hazards. An export that round-trips only until someone dictates a
  comma is worse than none. Tests cover quotes, embedded newlines, tabs, unicode and emoji.
- Import **merges** and is idempotent. Transcripts are identified by timestamp plus cleaned text
  because ids are per-device autoincrement values and mean nothing across a transfer; two dictations
  sharing an instant with different text both survive.
- The envelope is versioned: a newer file is refused with an explanation rather than mis-parsed, and
  a row naming a mode or backend this build does not know is **kept** — the text is the valuable
  part and an enum growing should not cost the user transcripts.
- `org.json` is stubbed to throw in `android.jar`, so a real implementation is a test-only
  dependency; runtime uses the platform copy.

### Earlier work, retained

- **Resident models.** `ModelSessionEngine` / `ModelSession`: pre-warm on focus, unload after 5 idle
  minutes. `withModels { }` is the only dictation path, so an unbalanced pin is unrepresentable.
- **Native performance.** The debug variant compiled ggml with no `-O` flag, so clang defaulted to
  `-O0` and the whole numerical library ran unoptimised. With `-O3` and
  `-march=armv8.2-a+fp16+dotprod`: **93,501 ms → 2,052 ms** end to end (transcribe 19,363 → 510 ms,
  cleanup 74,113 → 1,514 ms).
- **llama KV cache.** `nativeGenerate` never reset it, so once the model became resident, context
  accumulated across dictations and at ~2048 tokens decode failed into the rules fallback.
- **Text insertion.** `ACTION_SET_TEXT` replaces a node's entire contents, so dictation was always
  replacing, not inserting. Now splices at the cursor, replaces selections, restores the caret, and
  adds a separating space only where its absence would run two words together.
- **Model downloads moved into Settings**, out of the dictation path.
- **Build correctness.** `minSdk` 26→28; native libraries 16 KB page-aligned; `ndkVersion` pinned.

---

## Left undone

Highest value first.

- **The LLM-backed styles are unverified on-device.** Informal is deterministic and covered by
  exact-output tests. Casual, standard and business have thresholds validated against hand-written
  examples, not real Qwen output. The measurement already exists: transcripts record `backend`, so
  query the `RULES_FALLBACK` rate per mode. If business still falls back far more than informal, the
  budgets are still too tight. That is a query against existing data, not a guess.
- **Casual, standard and business are identical without a cleanup model.** Informal has its own
  deterministic path now, but the other three fall to the same rules branch, which varies only by
  filler stripping and a trailing full stop. Fix with per-mode deterministic transforms, or tell the
  user in Settings that three of the four styles need the model.
- **PCM buffer cap.** `AudioRecorder` buffers unbounded 16 kHz PCM in memory with no limit or disk
  spill — a quiet OOM risk on a long dictation, worse now that up to ~1.5 GB of models can be
  resident.
- **No instrumented tests on the riskiest code.** The three accessibility insertion tiers, focus
  tracking, and the JNI boundary have none. Nor do the new Compose surfaces: history interactions,
  delete confirmations, clipboard/share intents, the backup agent's `onFullBackup`. The pure logic
  under each (`TextSplice`, `HistorySelection`, `BackupPlan`, `TranscriptArchive`) is tested; the
  wiring is not.
- **Voice activity detection.** Auto-stop on silence, and trim leading/trailing silence before
  inference. whisper.cpp ships an energy-based VAD reachable through the existing JNI bridge.
- **Room migration tests.** `exportSchema = false`, so there is no schema JSON to diff and no
  instrumented migration test — now across two migrations (v1→v2, v2→v3). Adding
  `MigrationTestHelper` requires flipping `exportSchema = true` and committing schemas first.
- **Model selector Phase 1 (expanded whisper catalog).** Curated rather than all 30-plus ggml files;
  q5_1 variants (q5_1 small is ~180 MB versus 488 MB for comparable accuracy). Needs grouped UI — a
  flat radio list of 20-plus models is unusable.
- **Model selector Phase 2 (cleanup model infrastructure).** Blocked on chat templates; see below.
- **Deferred review findings.** `prewarm` has no fast path when both models are already resident, so
  every focus event allocates and takes the engine mutex. `warnedCleanupFallback` resets inside the
  `if (inserted)` branch, so a QWEN recovery whose insertion fell back to the clipboard will not
  clear the suppression flag.

---

## Discussed, not acted on

- **Qwen 3.8** (released August 2026). Two variants, neither usable: **3.8-Max** is 2.4T parameters
  and **API-only**, contradicting the whole premise; **3.8-27B** is the smallest open-weight release
  at ~28B — roughly 56× the current 0.5B, ~16 GB at Q4 against 11.4 GB of device RAM. The realistic
  swap is **Qwen3-0.6B**, which ships official GGUFs and drops into `ModelCatalog` as a
  repo/filename/size change. Two caveats: Qwen3's *thinking mode* would be actively harmful here (a
  `<think>` block would blow the 512-token cap and land inside the text `isFaithful` inspects —
  needs `/no_think` or `enable_thinking=false`, which the hand-built ChatML cannot express), and the
  style problem was a validation bug, not a capability gap. Measure the fallback rate before
  swapping models.
- **Gemini 3.5 Transcribe** (announced 2026-08-26). Cloud API only — no open weights, no on-device
  option. Notable because it is not just a Whisper competitor: it removes fillers, handles
  self-corrections and auto-formats, collapsing transcribe + cleanup into one model, and it ships
  inside Gboard on the test device. On raw quality it will beat a 0.5B doing post-hoc repair,
  because it has the audio, whereas cleanup here only ever sees Whisper's text. Nothing to adopt;
  the differentiator narrows to local-only, which is now the whole pitch rather than one of several.
  Worth watching for a small *open* audio-in/text-out model with cleanup behaviour — that would
  collapse this pipeline the same way and remove the 1.5 s cleanup stage.
- **Hardcoded ChatML blocks every other cleanup model.** `QwenCleaner` builds `<|im_start|>` markers
  by hand, so a Llama/Gemma/Phi GGUF would receive a malformed prompt, produce junk, fail the
  faithfulness check and silently drop to rules. The fix is `llama_model_chat_template()` +
  `llama_chat_apply_template()`, both present in the vendored llama.cpp but not exposed through the
  JNI layer. This is also what would let Qwen3's thinking mode be switched off properly.
- **The Windows sibling has the same cleanup-mode bug.** `src/localscribe/cleanup.py` has the same
  flat 30% `_is_faithful` threshold and the same one-line-hint-inside-a-conservative-prompt
  structure, so its informal and business modes will read alike for the same reason. None of the
  v0.2.0 work has been ported.
- **Clipboard and share are a genuine sandbox exit.** User-initiated and expected, but the clipboard
  is broadly readable. Not a defect; worth knowing when describing the app as fully local.
- **The transcript database is unencrypted SQLite.** Fine against other apps under Android's
  sandbox, readable on a rooted device. SQLCipher would close it at the cost of a dependency and key
  management.
- **The case for streaming transcription has weakened.** It was on the list when transcription was
  the bottleneck; at ~500 ms it no longer is, and whisper pads every clip to a 30-second window
  internally, so chunking would not help short dictations at all. The memory argument for a buffer
  cap still stands on its own.

---

## Concerns

- **ARMv8.2 requirement versus `minSdk 28`.** Native modules are compiled for
  `armv8.2-a+fp16+dotprod` (roughly 2018 CPUs onward). On an older arm64 device the app does not run
  slowly — it **crashes with SIGILL**. `minSdk 28` does not close this: a Pixel 2 has a 2017 CPU and
  runs Android 11, so it would install and then crash. Fixes are a runtime CPU feature check that
  fails gracefully (XS–S), or whisper.cpp's approach of two library variants chosen at load time
  (M). Unaddressed.
- **A debug-signed build may still be on the test device.** It cannot be updated over by v0.1.1 or
  later; it must be uninstalled first, which wipes local transcript history. One-time cost.
- **`onTrimMemory` is inert on Android 14+.** The platform stopped delivering every level this
  filter accepts; only `TRIM_MEMORY_UI_HIDDEN` still arrives and is deliberately excluded. On modern
  devices the 5-minute idle timer is the sole governor of up to ~1.5 GB of resident native memory.
- **Idle residency is a rolling window, not a cap.** Every focus event and dictation re-arms the
  timer, so continuous use keeps models resident well beyond one window.
- **Timing instrumentation ships in release builds.** One `Log.i` per stage on `LocalScribePerf`
  (`adb logcat -s LocalScribePerf`). Kept deliberately — it is what turned "the icon spins for
  minutes" into a precise diagnosis in one run. No transcript text is ever logged; only timings and
  character counts.
- **Another dictation app is active on the test device.** Wispr Flow (`com.wispr.flowapp`) has its
  own accessibility service enabled alongside LocalScribe's. Two dictation apps observing
  accessibility events can interact badly. Never ruled in or out as a factor in anything observed.
- **Some screens hide every overlay, including this one.** Screens handling sensitive input (the
  Contacts editor is a confirmed example) set `PRIVATE_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS`, hiding
  every non-system overlay app-wide. Not detectable or workaroundable, and not specific to this app.

---

## Privacy posture, as audited

Verified against the code, not assumed.

| Claim | Status |
|---|---|
| No telemetry or analytics | Zero third-party SDKs; dependencies are androidx/kotlin only |
| Nothing dictated leaves the device | One network call site (`ModelDownloader`), one host (`huggingface.co`), model files only |
| Audio never retained | Buffered in memory, `buffer.reset()` on stop and cancel; never written to disk |
| Nothing sensitive logged | No transcript text anywhere; perf logs are timings and character counts |
| Inference is local | whisper.cpp and llama.cpp in-process over JNI |
| Backup | **Was leaking transcripts to Drive**; off by default and per-category since v0.1.4 |
