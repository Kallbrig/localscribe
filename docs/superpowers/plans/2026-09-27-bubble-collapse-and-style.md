# Bubble collapse, style, and drag-to-dismiss — plan

Design: [`specs/2026-09-27-bubble-collapse-and-style-design.md`](../specs/2026-09-27-bubble-collapse-and-style-design.md).
Branch `feat/bubble-collapse-and-style`. TDD for every pure unit: failing test first, then code.

## Tasks

1. **`CollapseDelay`** — enum, `DEFAULT = THREE`, ascending notches, unique ids, `millis`
   (`null` for NEVER), display names ("Immediately", "1 second", "3 seconds", "Never").
2. **`BubbleOpacity`** — floor 15, step 5, `snap()` clamps and rounds to a step, index ↔ percent.
3. **`BubbleColor`** — presets incl. white; `contentArgb` by contrast ratio. Tests: white and
   amber get dark glyphs, black and purple light; ids unique; default purple.
4. **`BubbleCollapse` reducer** — tests: wake expands and bumps generation; expiry collapses;
   stale-generation expiry ignored; expiry mid-drag defers to drag end; drag does not bump
   generation; expand behaves as wake.
5. **`DismissZone`** — inside, outside, boundary.
6. **Wake signal** — `DictationController.bubbleWake`, `onFieldFocused()`, leave-recording rule
   in `setState`. Tests: Processing→Idle, Recording→Idle, Processing→Error wake;
   Recording→Processing, Idle→Error→Idle, Idle→Hidden do not.
7. **Preferences** — `collapseDelay`, `bubbleOpacityPercent`, `bubbleColor` on `AppPreferences`.
8. **Overlay rendering** — `OverlayContent` takes a `BubbleStyle` and `collapsed`; dot; themed
   pill and spinner; drag start/end callbacks; one shared `BubbleFace` for the Settings preview.
9. **Overlay service** — collapse state + timer, window offset on collapse, dismiss-target
   window and hit test, live preference listener, wake on `bubbleWake`.
10. **Settings section** — preview, two sliders, swatches, accessibility semantics.
11. **Release** — CHANGELOG `[Unreleased]`, `versionCode 13` / `0.2.0-beta.5`, HANDOFF.md,
    full forced test run, debug build, review, merge to `master`, tag, CI publish.
