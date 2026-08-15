# Selection Highlights, It Does Not Navigate

In the GPS points editor the user prunes bad points out of a Recorded Walk's GPS Trace. That work is comparative: a point is bad relative to its neighbours, so the user frames a view — a particular place at a particular zoom — and then inspects several points against it.

The editor originally treated every selection as a navigation: selecting a point animated the camera to it at zoom 17.5. **Selection now only marks a point.** The camera is left alone unless the selected point would otherwise be invisible, and then it pans — never zooms — just far enough to bring the point into the part of the map the user can actually see.

## The Rule

One rule, applied to every selection whatever caused it — a list row click, a tap on the map, a prev/next step, or the auto-advance after a deletion:

- Compute the **uncovered region**: the viewport minus the insets that screen chrome covers (the bottom sheet, the prev/delete/next pill above it, the top bar).
- If the selected point falls inside that region, **do nothing**.
- Otherwise pan by the smallest offset that puts it inside, with a small margin off the edges. **Zoom is never part of the answer.**

"Off screen" therefore means *genuinely* not visible: a point sitting behind the sheet is off screen even though it is inside the viewport, and it gets panned out from under it.

The list obeys the same idea in its own dimension: it scrolls the selected row into view only when the row is off-view, and never for a selection the user made by clicking a row in it — a row must not shift under the finger that just tapped it.

## Why

- **Stepping holds still.** Walking a run of points with prev/next no longer re-frames the map on every tap, so successive points are judged against the same scale and the same surroundings.
- **The user's zoom is theirs.** A fixed zoom-to-point discards a framing the user chose deliberately, and there is no zoom level the editor knows better than they do.
- **Nothing is selected invisibly.** The one thing an unconditional "leave the camera alone" rule would break — selecting a point from the list that is nowhere near the current view — is exactly the case the pan covers.

## Consequences

- The decision is a **pure function** (`panToReveal`), taking the point's screen position, the viewport, the insets, and a margin, and returning a pan offset or nothing. It has no Android dependency and is unit-tested directly. The composable's only job is turning the offset into a camera target via the map projection.
- **Chrome insets must be kept honest.** The pan is only as correct as the inset values fed to it; chrome that grows without its inset growing will strand selections underneath it.
- Because a map tap collapses the sheet to peek, the insets are computed for the peek layout — the state the sheet settles into on every selection.
- **Selection origin is modelled in UI state**, not in composable callbacks, so "did the list make this selection?" is decided at the ViewModel seam and is testable there.
- A degenerate uncovered region — chrome tall enough to leave nothing — aims at its midpoint rather than giving up, so a selection is never unreachable on a short screen.

## Considered Options

- **Keep centring on selection.** Rejected: it is the behaviour that made comparison work painful, and the reason this ADR exists.
- **Centre, but keep the current zoom.** Rejected as a half-measure — it still throws away the user's framing on every step, which is the actual complaint; zoom was only part of it.
- **Never move the camera at all.** Rejected: selecting a point from the list that lies outside the view would highlight something the user cannot see.
- **Fit the whole trace on selection.** Rejected: even more disruptive than centring, and useless at the scale where individual points are distinguishable.
