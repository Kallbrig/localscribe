# Pill polish, hold-to-record, safe bounds, timed dismissal, star prompt — design

_Agreed in chat on 2026-09-30 before any code. Ships as `v0.2.0-beta.6`. Follows
[`2026-09-27-bubble-collapse-and-style-design.md`](2026-09-27-bubble-collapse-and-style-design.md),
which it partly supersedes (the window-offset mechanism)._

## What was asked, and what was decided

| Item | Decision |
|---|---|
| Pill buttons | Visibly distinct from the pill: a tonal shift from the pill colour, a light-to-dark gradient, and a distinct drop shadow, so they read as raised. |
| Red recording indicator | Always fully opaque, whatever the opacity setting. The pulse becomes a **scale** pulse, since an alpha pulse would itself make it translucent. |
| Pill off the screen edge | Every overlay shape is centred on the bubble's centre and then clamped into a **safe area**, 16 dp in from each side, below the status bar (notification pull-down) and above the navigation bar. After a dictation the bubble is back where it was. |
| Dragging | The bubble can only be dragged within the same safe area. |
| Square shadow | Cause: shadows are drawn outside a shape's bounds, but the window was sized exactly to the content and the opacity was applied as an offscreen layer the same size — both clip the shadow to a rectangle. Fix: windows carry shadow padding, and opacity is applied through colours (including the shadow's) rather than a layer. |
| Hold to record | A touch that stays still past **250 ms** starts recording; releasing transcribes immediately — no ✓. Movement past touch slop first makes it a drag; a quick release is a tap. While held, the pill shows **no buttons**, only an equalizer animation filling the pill; moving the finger does not drag; there is **no cancel**. |
| Dismiss haptics | A tick when the bubble first moves over the ✕, and a confirm on the drop. Uses the view's haptic feedback, so the system "touch feedback" setting is respected. |
| Dismissal duration | Setting: **Until next field** (default), 1, 5, 15, 30 minutes, 1 hour. A timed dismissal suppresses the bubble for every field until it expires, then waits for the next field focus — it never pops back into a field already in use. Stored as a wall-clock deadline so it survives the process dying; a deadline further out than the longest option (clock moved back) is treated as expired. |
| Notification | Two actions on the existing overlay notification: **Hide bubble** (the same as a drop on the ✕, using the configured duration, once — it does not change the setting) and **Show bubble** (clears any dismissal and shows the bubble if a field is focused). |
| Star prompt | After every 100 **completed dictations whose text was inserted** (taps, cancels, failures, clipboard fallbacks do not count; the count starts at 0 on upgrade), a card drawn over the current app asks for a GitHub star: **Take me there**, **Remind me later**, **Don't remind me**. The first and last end it for good. Later, or tapping outside the card, waits another 100. Take me there opens the repo in the browser; LocalScribe itself still makes no network call. |

## Design

### Geometry (pure, `ui/overlay/OverlayGeometry`)

- `Bounds(left, top, right, bottom)` in screen pixels — the safe area.
- `clampAnchor(x, y, bubbleSizePx, bounds)` keeps the bubble fully inside.
- `placeCentered(anchorX, anchorY, width, height, bounds)` returns a top-left for any shape,
  centred on the anchor then clamped, so a pill near an edge slides inward.
- `OverlayShape.of(state, showingDot, holding)` → `NONE`, `DOT`, `BUBBLE`, `PILL`, `HOLD_PILL`,
  `PROCESSING`, with fixed dp sizes. The service sizes the window explicitly per shape (content
  plus shadow padding) instead of `WRAP_CONTENT`, so a resize never moves the shape.

The anchor is the only stored position. Dragging moves it (clamped); dismissal restores it.
Drag deltas come from **raw screen coordinates** captured by the window's root view, rather than
Compose's window-relative positions, which shift under the finger as the window moves.

### Gesture

One `awaitEachGesture` on the overlay's root, stable across state changes — the idle bubble is
replaced by the pill mid-hold, and a handler attached to the bubble itself would be disposed and
never see the release. Classified at the first of: release (tap), movement beyond touch slop
(drag), or 250 ms elapsed (hold). Only gestures that begin on the idle bubble or dot are handled;
the tap-mode pill's buttons keep their own handlers.

Hold sends `ACTION_START` and, on release, `ACTION_CONFIRM`. Intents to one service are delivered
in order, so a release that beats the recorder still confirms after it starts; a refused start
leaves `isRecording` false and the confirm is a no-op.

### Dismissal (`dictation/BubbleDismissal`, pure core `DismissDuration`)

`dismiss()` sets `Hidden` and, for a timed choice, a deadline. `DictationAccessibilityService`
checks `isSuppressed(now)` before showing the bubble on focus. The accessibility service also
publishes whether a field is focused, so **Show bubble** can bring it back without a new focus
event.

### Star prompt (`feedback/StarPrompt`, pure `StarPromptPolicy`)

Counted in the accessibility service when `TextInsertion.insert` returns true. When due, the
controller emits a request; the overlay service shows the card in its own overlay window
(`FLAG_NOT_FOCUSABLE | FLAG_WATCH_OUTSIDE_TOUCH`), a moment after the text lands.

## Not verified

No device is attached; the gesture, windows, haptics, notification actions and the card are
compile-checked only. The pure units are unit-tested. Bounds come from platform insets
(`WindowMetrics` on API 30+, resource dimensions below) and are unverified on a real screen.
