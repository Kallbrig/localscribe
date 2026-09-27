# Bubble collapse, style, and drag-to-dismiss — design

_Agreed in chat on 2026-09-27 before any code. Ships as `v0.2.0-beta.5`._

## Problem

The mic bubble is a 56 dp opaque purple circle that sits over whatever app has a text field
focused, for as long as that field is focused. It covers content (in Messages it sat on the
conversation header) and there is no way to get it out of the way short of leaving the field.
Wispr Flow, running on the same device, shrinks its icon to a small dot a few seconds after it
appears — the reference behaviour.

## Decisions, as agreed

| Question | Decision |
|---|---|
| Tap on the collapsed dot | **Expands** the bubble. Does not start recording — the dot is a small target and a mis-tap must not open the microphone. |
| What restarts the collapse timer | **A text field gaining focus** (including moving between fields while the bubble is already shown) and **a dictation finishing** (inserted, cancelled, or failed). Dragging does **not**. Expanding the dot does (otherwise it would re-collapse at once). |
| Timer running out mid-drag | Collapse is deferred to the release, never under the finger. |
| Collapse delay notches | 0, 1, 2, 3, 5, 10, 15, 30, 60 seconds, then **Never**. Default 3 s. Discrete notches like the recording-limit slider. |
| Where it collapses | In place, about the bubble's centre. The dot stays draggable. |
| Opacity | **One** setting, 15–100 % in 5 % steps, default 100 %. The 15 % floor exists because an invisible bubble is indistinguishable from a broken accessibility service (the same reasoning that kept the bubble on unsupported devices). |
| Colour | Preset swatches: purple (current, default), blue, teal, green, amber, red, gray, black, **white**. Glyph colour is derived from the swatch's luminance so a white bubble gets a dark mic. |
| What colour and opacity cover | **Everything**: idle bubble, dot, recording pill and processing indicator. The pulsing recording dot stays red — it is the live-microphone signal. |
| Drag-to-dismiss | An ✕ target appears at bottom centre once a drag starts, highlights when the bubble is over it; releasing there hides the bubble until **another text field gains focus** (or the same one is re-entered — a tap on an already-focused field produces no event). |
| Initial position | Unchanged: opens at a fixed position; no anchoring to the field. |

## Design

### Pure units (unit-tested)

- `ui/overlay/CollapseDelay` — enum of the notches, `millis: Long?` (`null` = never), stable ids
  for persistence, display names.
- `ui/overlay/BubbleColor` — enum of presets with ARGB, and `contentArgb` chosen by WCAG contrast
  ratio against black-ish and white glyphs (whichever is higher).
- `ui/overlay/BubbleOpacity` — `MIN_PERCENT = 15`, `STEP = 5`, `snap()`, slider index mapping.
- `ui/overlay/BubbleCollapse` — a reducer over `Wake`, `Expand`, `TimerExpired(generation)`,
  `DragStart`, `DragEnd`. Holds `collapsed`, `dragging`, `expiredDuringDrag`, and a
  **generation** bumped by every wake/expand. A timer posts the generation it was started for, so
  an expiry from a timer that has since been restarted is dropped — the same shape as the
  recording limit's generation counter.
- `ui/overlay/DismissZone.isOver(...)` — circle hit test in screen pixels.

### The wake signal

`DictationUiState` cannot carry it: moving between two fields leaves the state `Idle → Idle`,
and `StateFlow` does not re-emit an equal value. `DictationController` gains a `bubbleWake:
StateFlow<Int>` counter, bumped by:

- `onFieldFocused()`, called by `DictationAccessibilityService` on every `TYPE_VIEW_FOCUSED` for
  an editable node;
- `setState()` itself, whenever the state leaves `{Recording, Processing}` for anything else. One
  rule in one place covers insert, cancel and failure, rather than three call sites that can
  drift. A refusal (`Idle → Error → Idle`) never passed through recording and does not wake.

### Overlay service

`OverlayBubbleService` owns the collapse state (`MutableStateFlow<BubbleCollapse>`), runs the
timer in a coroutine keyed on generation and delay, and reacts to `collapsed` changing by
shifting the window by half the size difference so the dot lands on the bubble's centre (the
window is `WRAP_CONTENT`, so a transparent 56 dp window around a small dot would swallow touches
meant for the app underneath).

A second overlay window hosts the dismiss target: `FLAG_NOT_TOUCHABLE`, bottom centre, added on
drag start and removed on drag end. Hit testing uses `getLocationOnScreen` on both views rather
than computing display geometry, so insets and cutouts cannot skew it.

Style reaches the overlay through an `OnSharedPreferenceChangeListener`, so Settings changes
apply live.

### Settings

A **Mic bubble** section: a live preview (bubble and dot, in the chosen style), the collapse
slider, the opacity slider, and the swatches. Sliders carry `contentDescription` and
`stateDescription` like the recording-limit slider; swatches are `selectable` with
`Role.RadioButton` and the colour's name.

## Not done, deliberately

- No anchoring to the focused field.
- No free colour picker — presets keep the glyph legible.
- Dismissal is not persisted; it lasts until the next focus.

## Verification limits

Windows, gestures and animation have no instrumented tests in this repo (a gap already listed in
HANDOFF.md). The pure units above are unit-tested; the overlay wiring is verified on-device only
if a device is attached, and the handoff says which.
