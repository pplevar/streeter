# Edge-to-Edge System-Bar Inset Policy

The point editor's bottom-anchored controls have now been mispositioned twice by two different hand-rolled approaches to anchoring against the bottom of the screen. First a control pill offset by a hardcoded `96dp + 16dp` from the raw screen bottom, while the sheet beside it was inset by the navigation bar — the pill sank into the sheet by exactly `navBarInset − 16dp`. The fix for that reused the Scaffold's whole `PaddingValues` on the sheet, which carries the *top* inset too (status bar plus app bar, ~96dp), pushing the sheet's top edge down and leaving live map content — including the MapLibre attribution logo — showing through the strip behind the navigation bar.

Both are the same mistake: positioning something relative to the bottom of the screen without asking the system where the bottom actually is. `minSdk` is 35, so there is no OS version to branch on; the variable that actually changes is navigation mode (three-button reserves ~48dp, gesture ~16–24dp).

## Policy

1. **Bottom-anchored surfaces paint under the system bars.** A sheet, bar, or panel pinned to the bottom sizes its *painted* area as content height plus the navigation-bar inset, so the system bar is drawn over the surface's own background. The background is never inset.
2. **Scroll containers take the inset as `contentPadding`, never as layout padding.** Rows then scroll under the translucent bar in the conventional edge-to-edge way, while the last row can still be scrolled fully clear of it. Layout padding on the content column instead would leave a dead band of surface that never shows content.
3. **Never hardcode a system-bar dimension**, and **never reuse a Scaffold's `PaddingValues` wholesale on a bottom-anchored child** — it carries the top inset as well. Read the inset from `WindowInsets.navigationBars` at composition time.
4. **Siblings anchor to each other, not to the screen.** A control that should sit a fixed distance above a sheet is laid out as a `Column` sibling of it, so the spacing survives drag and snap without duplicating the sheet's height as a constant.
5. **Map camera padding counts the occluded region below the map's usable area** — the sheet's *peek* content height plus the navigation-bar inset. Selecting a point snaps the sheet to peek, so peek is the height the marker must clear. The control pill's height is deliberately excluded: measured on device the marker settles well above the pill, and including it pushes the marker noticeably high in the common case.

## Considered Options

- **Branch on `Build.VERSION.SDK_INT`.** Rejected: `minSdk` is 35, so there is no OS version to branch on. The variable that actually changes is navigation mode.
- **Re-anchor the control pill independently to the navigation bar.** Rejected: it reintroduces the hardcoded peek-height constant that caused the first defect, and the pill drifts as soon as the sheet is dragged off peek. Rule 4 above — sibling layout — keeps the two in step with no shared constant.
- **Move the pill inside the sheet's peek area.** Rejected as a redesign of the peek state, beyond what the defect required.
- **Apply the inset as layout padding on the sheet's content column** rather than as list `contentPadding`. Rejected: it leaves a band of dead surface, 48dp on three-button devices, that never shows content.
- **Add the pill's height to the camera's bottom padding.** Rejected on measurement, as noted in rule 5.

## Consequences

- `EditPointsScreen` reads one value, `WindowInsets.navigationBars`, and hands it to `EditorChrome` (`sheetMetrics`, `editorMapInsets`), which derives the sheet's heights and the camera's insets from it; the composable applies what it gets back. Its Scaffold content `PaddingValues` parameter is deliberately unused.
- The sheet's peek (96dp) and expanded (55% of screen height, clamped 280–480dp) values describe **content** height and are unchanged by navigation mode. The paint grows; the content does not, so peek shows the same rows in both modes.
- `RecordingScreen` and `ManualCreateScreen` already apply `navigationBarsPadding()` to their bottom-anchored controls and are believed correct, but have not been measured against this policy. Auditing them is separate work.
- The *arithmetic* — which insets are assembled, and how chrome dp becomes pixels — lives outside `@Composable` in `EditorChrome.kt` and is covered by JVM tests (`EditorChromeTest`). What those tests cannot check is the final placement on a real screen: the repo has no `androidTest` source set or Compose UI test dependency, and the expected pixel values vary by device and navigation mode. Verification of placement is a screenshot plus a colour-band pixel scan locating element bounds, cross-checked against `uiautomator` node bounds — the method used to diagnose the second defect, and the prior art for future inset work.
