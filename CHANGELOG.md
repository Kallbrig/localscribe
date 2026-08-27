# Changelog

All notable changes follow [Keep a Changelog](https://keepachangelog.com/) and semantic versioning.

## [0.1.4] - 2026-08-27

### Fixed

- **Transcripts were being uploaded to Google Drive.** `android:allowBackup="true"` was set with
  no backup rules, so Android Auto Backup included app-private storage by default -- and the
  Room database holding every transcript ever dictated sits in exactly the directory it sweeps.
  Nothing in the app's own code sent anything anywhere; this was an inherited platform default,
  which contradicted the app's stated promise for three releases.

### Added

- Backup is now a per-category choice in Settings, enforced at runtime by a `BackupAgent`
  rather than declared in static XML. A master switch turns it off entirely; otherwise
  settings, custom vocabulary and transcript history are chosen individually.
- **Transcript history is off by default**, so upgrading does not silently begin uploading
  dictated text, and turning it on shows an explicit warning that the text leaves the device.
- Transcripts and vocabulary share one database, so vocabulary alone is backed up via a
  vocabulary-only export generated at backup time and merged back in on first launch after a
  restore. Enabling transcripts includes vocabulary unavoidably, and the UI says so.
- Models are never backed up -- hundreds of megabytes and re-downloadable. Audio is still
  never written to disk at all.
- 9 tests covering the backup decision logic.
## [0.1.3] - 2026-08-26

### Changed

- The four cleanup styles are now a gradient in *what is allowed to change*, rather than four
  wordings of "tidy this up":

  | Style | Grammar & punctuation | Word choice |
  |---|---|---|
  | Informal | left alone | exact |
  | Casual | corrected | exact |
  | Standard | corrected | tightened |
  | Business | corrected | rewritten |

- **Informal is now near-verbatim and no longer runs the LLM at all.** Whisper emits
  prose-formatted text -- sentence case, commas, terminal punctuation -- and informal now undoes
  that instead of reinforcing it. `Hey man, what's going on?` stays `hey man what's going on`.
  A language model cannot be reliably talked out of tidying (it was returning `Hey man, what's
  up?`, swapping the words as well as the punctuation), so informal is deterministic:
  substitution is not merely rejected but impossible.
- Informal keeps apostrophes already present, the pronoun "I", acronyms, custom vocabulary, and
  names -- a word Whisper capitalised anywhere other than a sentence start. It keeps major
  sentence breaks as a bare full stop and does not end on one. Disfluencies ("um", "uh") go;
  slang ("like", "you know") stays, because that is the register.
- Casual now fixes grammar and punctuation while keeping word choice exact, and standard is the
  style that tightens wording. Their vocabulary budgets moved to match (0.15 and 0.35).

### Added

- `CleanupBackend.VERBATIM`, so informal transcripts are not labelled "basic cleanup" in
  history and do not trigger the cleanup-fallback toast. Informal not using the LLM is the
  mode working, not a shortfall.
- 17 tests covering the informal contract, including both reported cases verbatim.

## [0.1.2] - 2026-08-26

### Added

- History rows can be acted on. Tap a transcript to expand it and see the raw text alongside
  the cleaned version, with copy, share and delete. Long-press to start multi-select, then
  select all or delete in bulk. Deletions confirm first and say exactly what they will remove.
- Onboarding now includes the models. It shows the speech model as a required step with size,
  progress and retry, and the cleanup model as explicitly optional, then holds back "You're
  all set" until the speech model is actually on disk.

### Changed

- Cleanup styles now produce meaningfully different text. Informal and business were close to
  identical because the faithfulness check rejected any output introducing more than 30% new
  content words -- which is exactly what a business rewrite is -- so business silently fell
  back to the mode-blind rule cleaner. Each mode now carries its own rewriting budget, its own
  instruction, and a one-shot example of its own output. The shared prompt no longer tells the
  model to preserve wording, which was contradicting whichever style was selected.
- Faithfulness guards that stop the model answering a dictation instead of editing it now hold
  unconditionally in every mode: a dictated question stays a question, the text cannot balloon,
  and a figure that was never dictated is rejected outright. The vocabulary threshold is the
  only part that varies by mode, and it stays strict on short dictations, where the measure is
  unreliable and a model is most likely to reply rather than edit.

### Fixed

- Dictation no longer starts recording when the speech model is missing. The model was only
  touched once the pipeline ran, so a missing one surfaced after the user had already spoken
  and the recording was discarded.
- Corrected `README.md`, `docs/ARCHITECTURE.md` and a comment in `ModelSession` that all still
  described models being downloaded during a first dictation, which stopped being true when
  downloads moved to Settings.

## [0.1.1] - 2026-08-26

### Added

- Models now stay resident between dictations: whisper (and the cleanup LLM on >=6GB devices)
  pre-loads when a text field gains focus and unloads after 5 idle minutes or under memory
  pressure, so dictations no longer pay model-load latency every time.
- Models are downloaded from Settings, with per-model progress, cancel, retry and delete, and
  downloads that survive leaving the screen. The dictation path no longer downloads at all.
- History rows and a toast (shown once until AI cleanup recovers) now say when a transcript
  got basic (rules) cleanup instead of AI cleanup.

### Changed

- Native builds are compiled with `-O3` and `-march=armv8.2-a+fp16+dotprod`. The debug variant
  previously passed no `-O` flag at all, so clang defaulted to `-O0` and the whole ggml
  numerical library ran unoptimized. On a Galaxy S25 Ultra, the same phrase went from 19,363ms
  to 510ms for transcription and 74,113ms to 1,514ms for cleanup -- 93,501ms to 2,052ms total.
- Dictation is inserted at the cursor and replaces the selection, instead of replacing the
  entire field. A separating space is added only where its absence would run two words
  together -- never beside existing whitespace, an opening bracket or quote, or attaching
  punctuation.
- `minSdk` raised from 26 to 28 across all modules; dead API 26 branches removed.

### Fixed

- Release builds are signed with a real keystore supplied via `keystore.properties` or the
  `LOCALSCRIBE_*` environment variables. They were previously debug-signed, which meant
  consecutive releases carried different signatures and could not be installed over one
  another (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`, taking local transcript history with it),
  and the debug keystore's password is publicly known. Any release task now fails outright
  when no signing configuration is present, and CI verifies the published APK is not
  debug-signed before attaching it to a release.
- `nativeGenerate` never reset the llama KV cache, so once the model became resident, context
  accumulated across dictations: generation slowed each time and at around 2048 tokens decode
  failed and cleanup silently dropped to the rules fallback.
- The Qwen cleanup model's native context was loaded on every dictation and never freed,
  leaking native memory each time; the resident model session now owns and releases it.
- Native libraries are 16KB page-aligned. Android 15+ can run 16KB pages, where a 4KB-aligned
  library fails to load outright.
- `ndkVersion` is pinned to 28.2.13676358 in all modules. Nothing pinned it before, so AGP
  defaulted to NDK 27 locally while CI installed 28.2 -- two different toolchains depending
  on where the build ran.
- The CI push job triggered on `main`, but the default branch is `master`, so it never fired
  on merge.
- Overlay bubble would flicker back to hidden immediately after correctly appearing:
  `TYPE_WINDOW_CONTENT_CHANGED` accessibility events (whose `source` is frequently an
  unrelated parent container, not the focused view) were being used to clear focus tracking.
  Focus tracking now relies solely on `TYPE_VIEW_FOCUSED`.
- Overlay bubble would then disappear again ~300ms later: `TYPE_WINDOW_STATE_CHANGED` fires
  for the on-screen keyboard's own window opening, not just real app switches, and that was
  being treated as "user left the app."
- Native libraries were arm64-v8a-only, making the app unable to load on x86_64 emulators
  (`UnsatisfiedLinkError`). Added x86_64 as a second build target.

### Known limitations

- Native code targets ARMv8.2 (`+fp16+dotprod`, roughly 2018 CPUs onward). On an older arm64
  device the app crashes with SIGILL rather than running slowly, and `minSdk 28` does not
  close this -- a Pixel 2 has a 2017 CPU and runs Android 11.
- Onboarding does not yet mention models, so a first-run user reaches an error on their first
  dictation with nothing having pointed them at Settings.

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
