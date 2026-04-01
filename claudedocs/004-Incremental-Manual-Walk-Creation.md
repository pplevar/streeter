# Plan: Multi-Point Incremental Manual Walk Creation

## Context
The current manual walk creation flow is a 2-point (start → end) flow. The user wants an incremental multi-point builder: place start, keep adding points one by one (each pair routes a segment), then "Finish" to create the walk. This replaces the two-chip + Generate Route UI with a simpler centered-pin flow with Add Point / Undo / Finish buttons.

## Files to Modify
1. `app/src/main/java/com/streeter/ui/manual/ManualCreateViewModel.kt` — full rewrite
2. `app/src/main/java/com/streeter/ui/manual/ManualCreateScreen.kt` — full rewrite of UI
3. `app/src/main/java/com/streeter/ui/navigation/NavGraph.kt` — 1 line change
4. `app/src/main/res/values/strings.xml` — add new strings

---

## 1. New State (ManualCreateViewModel.kt)

Replace the enum and data class:

```kotlin
enum class ManualCreateStep {
    PLACING_FIRST_POINT,  // No points yet
    PLACING_NEXT_POINT,   // ≥1 point placed, awaiting next
    ROUTING,              // Computing segment between last two points
    FINISHING,            // Persisting to DB
    DONE                  // createdWalkId set, triggers nav
}

data class ManualCreateUiState(
    val step: ManualCreateStep = ManualCreateStep.PLACING_FIRST_POINT,
    val placedPoints: List<LatLng> = emptyList(),
    val segmentGeometries: List<String> = emptyList(),  // parallel to placedPoints pairs
    val segmentDistances: List<Double> = emptyList(),
    val segmentWayIds: List<List<Long>> = emptyList(),
    val currentPin: LatLng? = null,
    val isRouting: Boolean = false,
    val createdWalkId: Long? = null,
    val errorMessage: String? = null
) {
    val totalDistanceM: Double get() = segmentDistances.sum()
    val allWayIds: List<Long> get() = segmentWayIds.flatten()
    val hasSegments: Boolean get() = segmentGeometries.isNotEmpty()
    val hasPoints: Boolean get() = placedPoints.isNotEmpty()
    val accumulatedGeometryJson: String? get() = when {
        segmentGeometries.isEmpty() -> null
        segmentGeometries.size == 1 -> segmentGeometries[0]
        else -> mergeSegmentGeometries(segmentGeometries)  // package-level fun
    }
}
```

---

## 2. ViewModel Functions

**Keep:** `straightLineRoute()`, `clearError()`
**Remove:** `setStep()`, `generateRoute()`, `saveManualWalk()`
**Replace `onCameraMove()`:** update only when step is PLACING_FIRST_POINT or PLACING_NEXT_POINT
**Add:**

### `onConfirmPoint()`
1. Guard: return if `currentPin == null`
2. Append `currentPin` to `placedPoints`
3. If size == 1 → `step = PLACING_NEXT_POINT`, return
4. If size >= 2 → set `step = ROUTING, isRouting = true`, launch coroutine:
   - Try `routingEngine.isReady()` / `initialize()`, fallback to `straightLineRoute()`
   - On success → call `appendSegment(result)`
   - On failure → pop last point from `placedPoints`, `step = PLACING_NEXT_POINT`, `isRouting = false`, set `errorMessage`

### `appendSegment(result: RouteResult)` (private)
Appends to `segmentGeometries`, `segmentDistances`, `segmentWayIds`; sets `step = PLACING_NEXT_POINT, isRouting = false`

### `onUndo()`
Guard: step must not be ROUTING or FINISHING, `placedPoints` must be non-empty
Drop last point; if `segmentGeometries.isNotEmpty()`, also drop last segment geometry/distance/wayIds
`step = PLACING_FIRST_POINT` if no points remain, else `PLACING_NEXT_POINT`

### `onFinish()`
Guard: `hasSegments` must be true, step must not be ROUTING/FINISHING
1. `step = FINISHING`
2. In coroutine:
   - `mergedGeometry = mergeSegmentGeometries(segmentGeometries)`
   - `wayIdsJson = "[${allWayIds.joinToString(",")}]"`
   - `insertWalk(Walk(status=MANUAL_DRAFT, source=MANUAL, distanceM=totalDistanceM, ...))`
   - `insertSegment(RouteSegment(walkId, mergedGeometry, wayIdsJson, segmentOrder=0))`
   - `updateWalk(status=PENDING_MATCH)`
   - `workManager.enqueue(MapMatchingWorker.buildRequest(walkId))`
   - `step = DONE, createdWalkId = walkId`
3. On exception → `step = PLACING_NEXT_POINT`, `errorMessage`

### Package-level `mergeSegmentGeometries(segments: List<String>): String`
- Fast path if size == 1: return as-is
- Parse each GeoJSON Feature's `coordinates` array via `org.json.JSONObject`
- For i==0 include all coords; for i>0 skip first coord (junction dedup)
- Rebuild single Feature GeoJSON string

---

## 3. UI (ManualCreateScreen.kt)

**`Scaffold`** structure unchanged (TopAppBar, SnackbarHost, BottomBar, content Box)

**TopAppBar:** disable back button during FINISHING

**Content Box:**
- `MapLibreMapView(routeGeometryJson = uiState.accumulatedGeometryJson, onCameraMove = ...)` — shows growing route
- **Centered pin:** visible when `step in (PLACING_FIRST_POINT, PLACING_NEXT_POINT)`. Single color `Color(0xFF4CAF50)`
- **Instruction banner** (TopCenter Card):
  - PLACING_FIRST_POINT → "Move map to start point, then tap Add Point"
  - PLACING_NEXT_POINT → "Move map to next point, then tap Add Point"
  - ROUTING → "Computing route segment…"
  - FINISHING → "Saving walk…"
- **Loading overlay** (fullscreen Card): when `uiState.isRouting || step == FINISHING`

**`ManualCreateBottomBar`** — replace 3-chip bar with:
```
Row: [Undo (OutlinedButton)] [Add Point (Button, weight 1.5)] [Finish (FilledTonalButton)]
```
- **Undo**: enabled = `uiState.hasPoints && !uiState.isRouting && step != FINISHING`
- **Add Point**: enabled = `uiState.currentPin != null && step in (PLACING_FIRST_POINT, PLACING_NEXT_POINT)`
- **Finish**: enabled = `uiState.hasSegments && !uiState.isRouting && step != FINISHING`

Wire: `onConfirmPoint = viewModel::onConfirmPoint`, `onUndo = viewModel::onUndo`, `onFinish = viewModel::onFinish`

---

## 4. Navigation (NavGraph.kt line 78)

```kotlin
// BEFORE
navController.navigate(Screen.RouteEdit.createRoute(walkId)) {
    popUpTo(Screen.ManualCreate.route) { inclusive = true }
}
// AFTER
navController.navigate(Screen.WalkDetail.createRoute(walkId)) {
    popUpTo(Screen.ManualCreate.route) { inclusive = true }
}
```

---

## 5. strings.xml additions

```xml
<string name="label_add_point">Add Point</string>
<string name="label_finish_walk">Finish</string>
<string name="label_undo">Undo</string>
```

---

## Verification

1. `./gradlew testDebugUnitTest` — existing tests should pass
2. `./gradlew assembleDebug` — no compile errors
3. Manual test flow:
   - Home → Create Manual Walk
   - Move map → "Add Point" (start placed, no route yet)
   - Move map → "Add Point" (segment computed, blue line appears)
   - Repeat for more points
   - "Undo" removes last segment and point
   - "Finish" → navigates to WalkDetail, worker processes coverage
   - Walk shows as MANUAL with street coverage after processing
