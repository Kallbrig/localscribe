# Pill polish, hold-to-record, safe bounds, timed dismissal, star prompt — plan

Design: [`specs/2026-09-30-pill-hold-bounds-dismiss-design.md`](../specs/2026-09-30-pill-hold-bounds-dismiss-design.md).
Branch `feat/pill-hold-bounds-dismiss`. TDD for each pure unit.

1. **`OverlayGeometry`** — anchor clamp, centred placement with edge slide, `OverlayShape.of`.
2. **`DismissDuration` + suppression rule** — notches, default, deadline, clock-skew cap.
3. **`StarPromptPolicy`** — due at 100, later → +100, take-me-there and never are final.
4. **Button colour** — `BubbleColor.buttonArgb`, glyph contrast ≥ 3:1 on every swatch.
5. **Preferences** — dismissal duration, dismissal deadline, star-prompt state.
6. **Controller** — `fieldFocused`, star-prompt request flow; accessibility service honours
   suppression, publishes focus, counts inserted dictations.
7. **Overlay rendering** — colour-applied opacity with scaled shadows, 3D buttons, opaque scale
   pulse, hold pill with equalizer, shadow padding, star card, root gesture.
8. **Overlay service** — anchor + bounds, explicit per-shape window sizing, raw-coordinate drag,
   haptics, timed dismissal, notification actions, star card window, rotation.
9. **Settings** — "Hide for" slider.
10. **Release** — CHANGELOG, `versionCode 14` / `0.2.0-beta.6`, HANDOFF, forced test run,
    debug build, review, merge, tag, CI.
