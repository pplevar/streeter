# Streeter — Refactoring Proposals

Audit date: 2026-05-16
Branch: `feature/014-Route-Recalculation-Optimization`
Scope: `app/src/main/java/com/streeter/**` (≈100 Kotlin files, ≈6.8k LOC)

---

## Executive Summary

Streeter is a well-structured, idiomatic Kotlin/Compose/Hilt app. Clean-architecture boundaries are mostly respected, the engine layer is genuinely abstracted behind `RoutingEngine`, and the recent commits show measured, focused performance work (LM landmarks, batched DB writes, engine pre-warming). There is no spaghetti — proposals below are about reducing duplication, fixing two real layering leaks, and consolidating side-effects rather than rescuing tangled code.

**Highest-impact items (in order):**

1. **Eliminate duplicated GeoJSON parse/build/merge logic** scattered across 6 files (Critical, M). The current code reinvents `LineString` ↔ `List<LatLng>` conversion at least 5 times using raw `org.json`. This is the single biggest source of cross-cutting fragility — a bug in the splice path will silently produce different output from the merge path.
2. **Centralise routing-engine readiness** — 6 different call sites repeat the `if (!isReady()) initialize()` dance with subtly different error handling (Important, S/M). Introduce an `ensureReady()` semantic on the interface (or a thin `EnsureReadyRoutingEngine` decorator) so the whole app routes through one path.
3. **Centralise "kick off map matching" workflow** — 6 sites build and enqueue `MapMatchingWorker` requests, several with different `ExistingWorkPolicy` semantics. Two sites (`RouteEditViewModel.save`, `ManualCreateViewModel.onFinish`) call `workManager.enqueue(...)` without `enqueueUniqueWork`, which can race against the existing `match_$walkId` unique chain. Wrap in a `MapMatchingScheduler` use-case (Important, M).
4. **Fix domain-layer leak: `WalkRepository.upsertFromRemote(dto: WalkSyncDto)`** is the only domain interface that imports a `data.remote.dto` class — a clear DIP violation that will block ever swapping the remote layer (Important, S).
5. **Split `MapMatchingWorker.doWork`** (≈140-line function with 3 nested try blocks, mixed responsibilities, and inline JSON helpers) into orchestrator + per-source strategies (Important, M).

**Areas that are clean and need no work:**

- `domain/model` package — small, focused, pure Kotlin.
- DAO and Mapper layers — appropriate boilerplate, not over-engineered.
- The `RoutingEngine` interface itself — well-documented, narrow, properly abstracted.
- `GpsOutlierFilter` — pure object with an existing unit test.
- DI modules — appropriately granular, no obvious over-binding.

---

## Cross-cutting

### 1. Consolidate GeoJSON encode/decode into a single utility

**Location**:
- `app/src/main/java/com/streeter/data/engine/GraphHopperEngine.kt:288-310, 407-415` (parseCoordinates, buildMatchedLineString, buildRouteLineString)
- `app/src/main/java/com/streeter/ui/map/MapLibreMapView.kt:247-250, 274-353` (buildLineStringGeoJson, fitBoundsToGeometryJson, fitBoundsToJson)
- `app/src/main/java/com/streeter/ui/edit/RouteEditViewModel.kt:211-260` (parseCoordinates, buildLineString, spliceGeometry)
- `app/src/main/java/com/streeter/ui/manual/ManualCreateViewModel.kt:283-336` (mergeSegmentGeometries — declared as `companion object` member AND as top-level `internal fun`)
- `app/src/main/java/com/streeter/ui/history/HistoryViewModel.kt:120-145` (parseLatLngsFromGeoJson)
- `app/src/main/java/com/streeter/work/MapMatchingWorker.kt:225-248` (geometryDistanceM walks JSON to compute meters)

**Smell/Issue**: Severe code duplication of GeoJSON parsing/building; primitive obsession (`String` everywhere instead of a typed `RouteGeometry` value object).
**Severity**: 🔴 Critical
**Effort**: M (3-4h)

#### Current State

There are at least five independent re-implementations of "parse a GeoJSON Feature LineString into `List<LatLng>`" and at least four of "build a LineString Feature from coordinates". Each uses raw `org.json.JSONObject`/`JSONArray` and string-concatenates output. `ManualCreateViewModel` uniquely declares `mergeSegmentGeometries` *twice* (lines 307 and 334) — a top-level `internal fun` that delegates to the companion-object copy. `HistoryViewModel` is the only site that handles `MultiLineString`; the others would silently return empty.

Every site re-implements the same string template:

```kotlin
"""{"type":"Feature","geometry":{"type":"LineString","coordinates":[$coordStr]},"properties":{}}"""
```

#### Proposed Refactoring

Introduce a single value object + utility, somewhere neutral (suggest `domain/model/RouteGeometry.kt` since both UI and data already need it):

```kotlin
@JvmInline value class RouteGeometry(val featureJson: String) {
    fun coordinates(): List<LatLng> = GeoJson.parseLineString(featureJson)
    companion object {
        fun ofLineString(coords: List<LatLng>): RouteGeometry = ...
        fun merge(parts: List<RouteGeometry>): RouteGeometry = ...
        fun splice(original: RouteGeometry, replacement: RouteGeometry,
                   anchor1: LatLng, anchor2: LatLng): RouteGeometry = ...
    }
}

object GeoJson {
    fun parseLineString(json: String): List<LatLng> = ...
    fun parseAnyToLatLngs(json: String): List<LatLng> = ... // handles MultiLineString + FeatureCollection
    fun lineStringFeature(coords: List<LatLng>): String = ...
    fun distanceMeters(coords: List<LatLng>): Double = ...
}
```

Then `RouteSegment.geometryJson: String` becomes `RouteSegment.geometry: RouteGeometry`, `RouteResult.geometryJson` becomes `RouteResult.geometry`, and the worker no longer parses JSON to compute distance — it asks the geometry value.

#### Rationale

- **Correctness**: Today, a tweak to the splice algorithm in `RouteEditViewModel` could silently disagree with the merge in `ManualCreateViewModel` for the same coordinate sequence, because they're independent code paths. One implementation = one truth.
- **Testability**: Pure functions on `RouteGeometry` are trivially JVM-unit-testable; today the splice logic is buried inside a `ViewModel` with a `SavedStateHandle` dependency.
- **Performance**: `org.json` is heavyweight — every coordinate allocates a `JSONArray`. A focused parser using `kotlinx.serialization` or even a simple regex/state-machine would help on long routes (some walks are 1000+ coords).
- **Type safety**: Six functions today take `geometryJson: String?` and return `String?`. A `RouteGeometry` value class makes intent explicit at compile time.

#### Risks & Migration

- Output JSON must be byte-identical until DB-backed strings are migrated, otherwise existing rows in `route_segments.geometryJson` won't round-trip. Add a unit test that asserts old → parse → re-emit produces identical bytes.
- Migration can be incremental: introduce `GeoJson` first and rewrite each call site one at a time. The value-class wrap is a follow-up step.
- The `MultiLineString` branch in `HistoryViewModel` exists because some legacy DB rows may be that type — preserve that branch in the new utility.

---

### 2. Introduce `RoutingEngineGateway` to consolidate readiness/init

**Location**:
- `app/src/main/java/com/streeter/work/MapMatchingWorker.kt:71-75`
- `app/src/main/java/com/streeter/service/LocationService.kt:146-155`
- `app/src/main/java/com/streeter/ui/home/HomeViewModel.kt:50-60`
- `app/src/main/java/com/streeter/ui/edit/RouteEditViewModel.kt:127-141`
- `app/src/main/java/com/streeter/ui/detail/WalkDetailViewModel.kt:81-92`
- `app/src/main/java/com/streeter/ui/manual/ManualCreateViewModel.kt:106-118`
- `app/src/main/java/com/streeter/ui/settings/SettingsViewModel.kt:55-71`

**Smell/Issue**: Shotgun-surgery duplication of an idiom; mixed error-handling policies; potential init-storm (multiple coroutines may call `initialize()` concurrently — `GraphHopperEngine.initialize` does hold a `Mutex`, but every caller still races to take it).
**Severity**: 🟡 Important
**Effort**: S (1h)

#### Current State

Every consumer hand-rolls:

```kotlin
if (!routingEngine.isReady()) {
    try { routingEngine.initialize() } catch (e: Exception) { /* per-site fallback */ }
}
```

with three different error-handling patterns:
- `MapMatchingWorker`: rethrows, lets the outer catch handle retries.
- `RouteEditViewModel`: shows "Routing unavailable" snackbar.
- `ManualCreateViewModel`: silently falls back to straight-line route.
- `LocationService`/`HomeViewModel`/`WalkDetailViewModel`: log-and-swallow as pre-warm.

Two distinct concerns are entangled here: (a) eager pre-warm (fire-and-forget) vs. (b) blocking ensure-ready (must succeed or fail with a typed error).

#### Proposed Refactoring

Add two methods to `RoutingEngine` (or a thin decorator):

```kotlin
suspend fun ensureReady(): Result<Unit>  // idempotent, blocking, returns typed failure
fun preWarm(scope: CoroutineScope)        // fire-and-forget; logs only
```

Default implementation lives once in `GraphHopperEngine`. All seven call sites collapse to either `ensureReady().onFailure { ... }` or `preWarm(viewModelScope)`.

#### Rationale

- Single error-handling contract; impossible to forget the `try`.
- Makes the difference between "I need to route now" and "warm the engine for later" explicit at the call site.
- Eliminates the init-storm risk: `preWarm` can no-op if a warm is already in flight (separate `Job` reference inside the engine).

#### Risks & Migration

- Low. The new methods are additive; existing methods keep working until the call sites are migrated.
- Watch the cancellation semantics on `preWarm` — must not cancel an in-flight init when a viewmodel's scope ends.

---

### 3. Centralise `MapMatchingWorker` enqueue (and fix the non-unique enqueues)

**Location**:
- `app/src/main/java/com/streeter/service/LocationService.kt:245-249` (uses `enqueueUniqueWork` with KEEP)
- `app/src/main/java/com/streeter/data/repository/RemoteSyncRepositoryImpl.kt:88-92` (REPLACE)
- `app/src/main/java/com/streeter/ui/detail/WalkDetailViewModel.kt:107-111, 183-187` (REPLACE)
- `app/src/main/java/com/streeter/ui/edit/RouteEditViewModel.kt:343` (`workManager.enqueue` — **not unique**)
- `app/src/main/java/com/streeter/ui/manual/ManualCreateViewModel.kt:253` (`workManager.enqueue` — **not unique**)

**Smell/Issue**: Inconsistent unique-work semantics; potential duplicate workers; raw string `"match_$walkId"` repeated.
**Severity**: 🟡 Important (latent bug risk)
**Effort**: S (1h)

#### Current State

Five sites enqueue `MapMatchingWorker`. The unique-work key `"match_$walkId"` is hard-coded at three sites; the other two skip uniqueness entirely. If the user finishes a manual walk while a previous worker for the same walkId is still queued (e.g., from a re-edit), nothing prevents two workers running concurrently against the same DB rows.

In addition, the "transition walk to PENDING_MATCH + enqueue worker" pattern appears 5 times with subtle differences (some bump `updatedAt`, some don't; some clear sync state, some don't).

#### Proposed Refactoring

```kotlin
@Singleton
class MapMatchingScheduler @Inject constructor(
    private val workManager: WorkManager,
    private val walkRepository: WalkRepository,
    private val pendingMatchJobRepository: PendingMatchJobRepository,
) {
    /** Mark walk PENDING_MATCH (idempotent) and enqueue the worker as unique work. */
    suspend fun schedule(walkId: Long, replaceExisting: Boolean = false) {
        // single source of truth for status transition + uniqueWorkName + policy
    }
    suspend fun cancel(walkId: Long) { ... }
    fun observeProgress(walkId: Long): Flow<MatchingProgress> = ...
}
```

Move `KEY_PROGRESS`/`KEY_STEP` constants and the `match_$walkId` naming convention onto this class.

#### Rationale

- Eliminates two latent concurrency bugs (`enqueue` without uniqueness in edit + manual paths).
- Shrinks `WalkDetailViewModel` and several others by 5–10 lines each.
- One place to add e.g. analytics, metrics, or a circuit breaker for repeated failures.

#### Risks & Migration

- Behaviour change: today, `RouteEditViewModel.save()` after a previous failed run would create a second worker. Switching to `enqueueUniqueWork(REPLACE)` will cancel the old one — that's the desired behaviour, but worth a note.
- Pair with proposal #2 (the scheduler can also `routingEngine.preWarm` before enqueueing).

---

### 4. Unify `SharedPreferences` usage behind a typed `AppPreferences`

**Location**:
- `app/src/main/java/com/streeter/data/repository/RemoteSyncRepositoryImpl.kt:101-110` (`sync_prefs`, `client_id`, `last_pull_sync_at`)
- `app/src/main/java/com/streeter/work/PullSyncWorker.kt:29-30` (`sync_prefs`, `last_pull_sync_at`)
- `app/src/main/java/com/streeter/ui/settings/SettingsViewModel.kt:33-50` (`streeter_settings`, `KEY_GPS_INTERVAL`, `KEY_MAX_SPEED`)

**Smell/Issue**: Magic strings repeated across files; settings written from one file and read from another with no shared schema; testability requires `Robolectric` or stubbing `Context`.
**Severity**: 🟢 Recommended
**Effort**: S (1h)

#### Current State

`"sync_prefs"` and the key `"last_pull_sync_at"` are duplicated raw between `RemoteSyncRepositoryImpl` and `PullSyncWorker`. Settings keys are typed as `companion object const val` in `SettingsViewModel` but the GPS/speed values are also read from `LocationService` (line 74-75) where the defaults `50f` / `20` are hard-coded — meaning a user changing them in Settings has no effect on the running service. (See #11 for that deeper bug.)

#### Proposed Refactoring

```kotlin
@Singleton
class AppPreferences @Inject constructor(@ApplicationContext ctx: Context) {
    private val syncPrefs = ctx.getSharedPreferences("sync_prefs", MODE_PRIVATE)
    private val settingsPrefs = ctx.getSharedPreferences("streeter_settings", MODE_PRIVATE)

    var clientId: String by syncPrefs.string("client_id") { UUID.randomUUID().toString() }
    var lastPullSyncAt: Long by syncPrefs.long("last_pull_sync_at", 0L)
    var gpsIntervalSeconds: Int by settingsPrefs.int("gps_interval_seconds", 20)
    var maxSpeedKmh: Int by settingsPrefs.int("max_speed_kmh", 50)
}
```

Inject everywhere instead of pulling out raw `SharedPreferences`.

#### Rationale

- Eliminates string-typo surface and centralises defaults.
- Makes the "settings actually apply to the running service" bug (#11) trivial to fix — `LocationService` reads from the same source of truth.
- Easy to fake in tests.

#### Risks & Migration

- None significant. Same underlying `SharedPreferences` files; same keys.

---

### 5. Move `formatDistance` / `formatDuration` to a shared `ui/format` package

**Location**: Five duplicates of `formatDistance(meters: Double)` and `formatDuration(ms: Long)`:
- `app/src/main/java/com/streeter/ui/detail/WalkDetailScreen.kt:644-656`
- `app/src/main/java/com/streeter/ui/history/HistoryScreen.kt:485-497` (slight wording difference: "min" vs "m")
- `app/src/main/java/com/streeter/ui/streetdetail/StreetDetailScreen.kt:346-352`
- `app/src/main/java/com/streeter/ui/manual/ManualCreateScreen.kt:435` (only distance, slightly different)
- `app/src/main/java/com/streeter/ui/recording/RecordingScreen.kt:302-316` (split into value/unit)

**Smell/Issue**: Trivial DRY violation, but it has already drifted (`HistoryScreen` says "min" instead of "m", others say "h" + "m").
**Severity**: 🟢 Recommended
**Effort**: S (<1h)

#### Current State

Each screen implements its own copy. `ManualCreateScreen` uses `meters.toInt()` while every other screen uses `meters.roundToInt()` — a real, observable inconsistency.

#### Proposed Refactoring

Single `ui/format/Formatters.kt`:

```kotlin
object DistanceFormatter {
    fun format(meters: Double): String = ...        // "1.2 km" / "750 m"
    fun valueAndUnit(meters: Double): Pair<String, String> = ...  // for the styled split
}
object DurationFormatter {
    fun short(ms: Long): String = ...               // "12m" / "1h 03m"
    fun stopwatch(ms: Long): String = ...           // "00:12:34"
}
```

#### Rationale

- Consistency across screens.
- One unit-test target for locale-correct formatting (note: `"%.1f".format(...)` uses the JVM default locale — already a minor i18n bug worth fixing here).

#### Risks & Migration

- None.

---

## Domain Layer

### 6. Remove `WalkSyncDto` from the domain repository interface

**Location**: `app/src/main/java/com/streeter/domain/repository/WalkRepository.kt:3, 36`

**Smell/Issue**: 🔴 Architecture violation — domain depends on `data.remote.dto`. DIP breach.
**Severity**: 🟡 Important
**Effort**: S (<1h)

#### Current State

```kotlin
// in domain/repository/WalkRepository.kt
import com.streeter.data.remote.dto.WalkSyncDto  // <-- domain importing from data
...
suspend fun upsertFromRemote(dto: WalkSyncDto)
```

This is the single point where the domain layer reaches into `data/`. Everywhere else the boundary is respected. If the remote layer ever changes (new DTO version, GraphQL, etc.), the domain interface — and therefore every test that mocks it — has to change too.

#### Proposed Refactoring

Define a `RemoteWalkSnapshot` (or reuse `Walk` plus a few extra fields like `serverUpdatedAt`) in `domain/model`. Keep DTO ↔ snapshot conversion in the data layer (`RemoteSyncRepositoryImpl` already does the network call; it can map before handing to the repo).

```kotlin
// domain
data class RemoteWalkSnapshot(
    val serverWalkId: Long,
    val title: String?,
    val durationMs: Long,
    val distanceM: Double,
    val status: WalkStatus,
    val updatedAt: Long,
    val serverUpdatedAt: Long,
    val gpsTraceUpdatedAt: Long?,
)
suspend fun upsertFromRemote(snapshot: RemoteWalkSnapshot)
```

#### Rationale

- Makes the architecture honest. Today, "we have a domain layer" is a half-truth at this seam.
- Decouples future server schema changes from domain tests.

#### Risks & Migration

- Trivial. One mapper added to `RemoteSyncRepositoryImpl`; one method signature changes.

---

### 7. Replace `Result<RouteResult>` with a sealed result type

**Location**:
- `app/src/main/java/com/streeter/domain/engine/RoutingEngine.kt:24, 30-34`
- All consumers of `matchTrace` / `route`

**Smell/Issue**: `Result<T>` losing failure context; consumers convert to user-visible strings inconsistently.
**Severity**: 🟢 Recommended
**Effort**: M (2h)

#### Current State

The interface returns `Result<MatchResult>` / `Result<RouteResult>`. Failure cases include "engine not ready", "out of bounds", "trace too short", "graphhopper had errors", "deduplicated to 1 point", "OOM during HMM transition" — all funnelled through a generic `Throwable`. Each viewmodel pattern-matches on the message string ("Routing failed. Try a different waypoint." etc.), which is brittle.

#### Proposed Refactoring

```kotlin
sealed class RoutingError {
    object EngineNotReady : RoutingError()
    object OutOfBounds : RoutingError()
    data class TooFewPoints(val count: Int) : RoutingError()
    data class EngineFailure(val cause: Throwable) : RoutingError()
}
sealed class MatchOutcome {
    data class Success(val result: MatchResult) : MatchOutcome()
    data class Failure(val error: RoutingError) : MatchOutcome()
}
```

#### Rationale

- Exhaustive `when` at the call site; UI strings centralised; can add e.g. analytics on specific error categories.
- Particularly valuable for `MapMatchingWorker` which currently catches `Exception` broadly and decides between retry/success based on the throwable type — easy to miss a case.

#### Risks & Migration

- Medium-touch (every caller updates).
- `RoutingEngine` interface is binary-incompatible with old callers — but this is a single-app codebase so that's not a concern.

---

### 8. Drop the unused `lastPullSyncAt` walk column or actually use it

**Location**:
- `app/src/main/java/com/streeter/data/local/entity/WalkEntity.kt:19`
- `app/src/main/java/com/streeter/data/local/dao/WalkDao.kt:70-77`
- `app/src/main/java/com/streeter/data/local/StreeterDatabase.kt:54-59` (Migration 3→4)
- `app/src/main/java/com/streeter/data/repository/RemoteSyncRepositoryImpl.kt:101-103`

**Smell/Issue**: Dead state. The column exists, the DAO has read/write methods, the migration runs — but no code path ever **writes** a non-null value. The pull-sync timestamp is persisted only to `SharedPreferences` (`sync_prefs/last_pull_sync_at`).
**Severity**: 🟢 Recommended
**Effort**: S (<1h)

#### Current State

`WalkRepository.getLastPullSyncAt()` and `updateLastPullSyncAt()` are exposed in the domain interface but never called from any feature code — only the DAO method `getLastPullSyncAt(): Long?` exists. `RemoteSyncRepositoryImpl.pullWalks()` writes the timestamp to `SharedPreferences` instead.

#### Proposed Refactoring

Pick one:
- **(A) Drop the column** — write a no-op migration that leaves it for now (Room won't drop columns easily); remove the DAO methods and the domain methods. Simpler.
- **(B) Use it** — replace the `SharedPreferences` write with a single-row "sync state" table (or just a constant walk row holding sync metadata). More principled but more churn.

Recommend **A**.

#### Rationale

- Less surface area = less to mock, fewer "what does this do?" questions.
- Removes a column-as-graveyard pattern.

#### Risks & Migration

- None — there is provably no behavioural dependency.

---

## Data Layer

### 9. Split `MapMatchingWorker.doWork()` into orchestrator + strategies

**Location**: `app/src/main/java/com/streeter/work/MapMatchingWorker.kt:58-203`

**Smell/Issue**: Long Method (140 lines), mixed responsibilities (status transitions, engine init, GPS load, map matching, segment loading, coverage, sync trigger, error categorisation), nested control flow with `return@withContext` deep inside.
**Severity**: 🟡 Important
**Effort**: M (2-3h)

#### Current State

`doWork()` is ≈140 lines and does at least 8 distinct things. It also embeds two helper methods (`geometryDistanceM`, `parseWayIds`) that exist only because there's no shared GeoJSON utility (see #1).

The branching by `WalkSource.RECORDED` vs. manual is essentially the Strategy pattern done inline.

#### Proposed Refactoring

```kotlin
class MapMatchingWorker(...) : CoroutineWorker(...) {
    override suspend fun doWork(): Result {
        return MapMatchingPipeline(walkRepo, jobRepo, ...)
            .run(walkId, ::setProgress)
            .toWorkResult(runAttemptCount)
    }
}

internal class MapMatchingPipeline(...) {
    suspend fun run(walkId, onProgress: ProgressCallback): PipelineResult { ... }
    private val strategies = mapOf(
        WalkSource.RECORDED to RecordedTraceStrategy(...),
        WalkSource.MANUAL to ManualSegmentStrategy(...),
    )
}
```

Each strategy returns `(matchedDistanceM, wayIds)` or fails; the pipeline owns transitions, the worker owns retry policy.

#### Rationale

- The pipeline becomes JVM-unit-testable without WorkManager.
- Adding a third source (e.g., GPX import) becomes a new strategy, not a fourth nested branch.
- Retry logic (`if (retries >= 3) failure() else retry()`) currently lives at the bottom — a future change could miss it.

#### Risks & Migration

- Behaviour-preserving refactor. Test coverage: add a fake `RoutingEngine` that returns canned results and exercise both source paths.
- Care with `setProgress` lifetime — must remain within the worker's coroutine context.

---

### 10. Extract a `WalkLifecycle` use-case to consolidate status transitions

**Location**: 9 sites that set `WalkStatus.PENDING_MATCH` (see grep below):
- `app/src/main/java/com/streeter/ui/home/HomeViewModel.kt:44`
- `app/src/main/java/com/streeter/ui/edit/RouteEditViewModel.kt:336-342`
- `app/src/main/java/com/streeter/ui/detail/WalkDetailViewModel.kt:182`
- `app/src/main/java/com/streeter/ui/manual/ManualCreateViewModel.kt:245-251`
- `app/src/main/java/com/streeter/service/LocationService.kt:226-235`
- `app/src/main/java/com/streeter/data/repository/RemoteSyncRepositoryImpl.kt:87`
- Plus completion transitions inside `MapMatchingWorker.completeWalk()` (lines 205-223).

**Smell/Issue**: Feature envy — multiple UI/service classes manipulate Walk state machine directly; status transitions are not validated (nothing prevents `COMPLETED → RECORDING`).
**Severity**: 🟡 Important
**Effort**: M (2h)

#### Current State

Setting `walk.copy(status = PENDING_MATCH, updatedAt = now)` is repeated, sometimes with `lastResumedAt = null`, sometimes without; sometimes `updatedAt` is bumped, sometimes not. There is no domain object encoding which transitions are valid.

#### Proposed Refactoring

```kotlin
@Singleton
class WalkLifecycle @Inject constructor(
    private val walkRepository: WalkRepository,
    private val scheduler: MapMatchingScheduler,  // see #3
) {
    suspend fun beginRecording(): Walk
    suspend fun pause(walkId: Long)
    suspend fun resume(walkId: Long)
    suspend fun stopForMatching(walkId: Long)        // RECORDING → PENDING_MATCH + enqueue
    suspend fun submitManual(walk: Walk): Long       // → PENDING_MATCH + enqueue
    suspend fun completeFromMatching(walkId: Long, distanceM: Double?)
    suspend fun requestRecalculation(walkId: Long)   // COMPLETED → PENDING_MATCH + enqueue
    suspend fun softDelete(walkId: Long)
}
```

Add a `WalkStatus.canTransitionTo()` validator.

#### Rationale

- One place to evolve the state machine (e.g. add a `MATCH_FAILED` status later).
- Prevents the bug class "I forgot to bump `updatedAt`, so pull-sync misses my change".
- Big test win.

#### Risks & Migration

- Significant call-site churn but each change is mechanical.

---

### 11. Make `LocationService` actually honour user settings

**Location**: `app/src/main/java/com/streeter/service/LocationService.kt:74-75, 260-268`

**Smell/Issue**: Bug masquerading as a refactor — the user's GPS interval / max-speed settings (`SettingsViewModel.KEY_GPS_INTERVAL`, `KEY_MAX_SPEED`) are never read by the service. The values default to `20s` / `50 km/h` and never change.
**Severity**: 🟡 Important
**Effort**: S (<1h)

#### Current State

```kotlin
// LocationService.kt:74-75
private var maxSpeedKmh: Float = 50f
private var sampleIntervalSeconds: Int = 20
```

These two fields are declared `var` but no code ever assigns to them. `SettingsViewModel` writes the user's preference; `LocationService` ignores it.

#### Proposed Refactoring

After completing #4 (`AppPreferences`), inject it and read in `startWalk()` / `resumeWalk()`:

```kotlin
@Inject lateinit var prefs: AppPreferences
private fun startLocationUpdates() {
    val interval = prefs.gpsIntervalSeconds
    val maxSpeed = prefs.maxSpeedKmh.toFloat()
    // ...
}
```

#### Rationale

- Settings UI currently lies to the user. This is a correctness bug, not just a design issue.
- Once `AppPreferences` exists (#4), this is a 5-line change.

#### Risks & Migration

- Need to handle the case where settings change during a recording — simplest is to read once at start; resume re-reads.

---

### 12. Use `withTransaction` for `replacePointsFromRemote` and similar paired ops

**Location**:
- `app/src/main/java/com/streeter/data/repository/GpsPointRepositoryImpl.kt:29-35` (delete + insert, no tx)
- `app/src/main/java/com/streeter/data/repository/StreetRepositoryImpl.kt:75-78` (deleteWalkStreets + deleteWalkSections, no tx)

**Smell/Issue**: Composite operations not atomic; partial failures leave inconsistent state.
**Severity**: 🟡 Important
**Effort**: S (<1h)

#### Current State

```kotlin
override suspend fun replacePointsFromRemote(walkId: Long, points: List<GpsPoint>) {
    dao.deleteByWalkId(walkId)
    dao.insertAll(points.map { it.toEntity() })
}
```

If the process is killed between these two calls (e.g. user kills the app during pull-sync), the walk is left with zero GPS points. The recent commit `00f1768` already added `TransactionRunner` and used it for coverage writes — apply the same here.

#### Proposed Refactoring

Inject `TransactionRunner` (or the `StreeterDatabase` directly) into both repository impls, wrap each composite op:

```kotlin
override suspend fun replacePointsFromRemote(walkId: Long, points: List<GpsPoint>) {
    transactionRunner.run {
        dao.deleteByWalkId(walkId)
        dao.insertAll(points.map { it.toEntity() })
    }
}
```

#### Rationale

- Correctness — completes the work started in commit 00f1768.
- Cheaper too: one fsync per replace instead of two.

#### Risks & Migration

- None.

---

### 13. Remove the dual `mergeSegmentGeometries` declaration

**Location**: `app/src/main/java/com/streeter/ui/manual/ManualCreateViewModel.kt:307` (companion object) and `:334` (top-level `internal fun`).

**Smell/Issue**: Identical function declared twice, the top-level one delegates to the companion one. Reads as a leftover from a refactor.
**Severity**: 🟢 Recommended
**Effort**: S (<5min, deferred — covered by #1)

#### Current State

```kotlin
companion object {
    fun mergeSegmentGeometries(geometries: List<String>): String { ... real impl ... }
}
}  // end of class

internal fun mergeSegmentGeometries(geometries: List<String>): String =
    ManualCreateViewModel.mergeSegmentGeometries(geometries)
```

The top-level function exists because the data-class `UiState.accumulatedGeometryJson` (line 50) needs to call it from outside the class scope. Once #1 lands and `RouteGeometry.merge()` exists, both go away.

#### Proposed Refactoring

Subsumed by #1.

---

## UI Layer

### 14. Split large screens into per-section composables in their own files

**Location**:
- `app/src/main/java/com/streeter/ui/detail/WalkDetailScreen.kt` (656 lines)
- `app/src/main/java/com/streeter/ui/history/HistoryScreen.kt` (497 lines)
- `app/src/main/java/com/streeter/ui/manual/ManualCreateScreen.kt` (435 lines)
- `app/src/main/java/com/streeter/ui/edit/RouteEditScreen.kt` (310 lines)

**Smell/Issue**: Large File / God Composable; mixing route-level orchestration with deeply nested presentation; private helper composables make individual previewing impossible without enabling the whole screen's DI.
**Severity**: 🟢 Recommended
**Effort**: M-L (depends on appetite)

#### Current State

`WalkDetailScreen.kt` contains 9 composables, all `private`, plus tier color tokens (lines 43-56) that arguably belong with `theme/Color.kt`. The screen file mixes:
- the route-level `Scaffold` + state management
- presentation primitives (`StreetRow`, `BigStatCard`, `TierPill`, `MatchingProgressBanner`)
- color tokens (six private `Color` constants per tier × theme)

#### Proposed Refactoring

Per screen, split:
```
ui/detail/
   WalkDetailScreen.kt         // route-level only
   components/
       WalkHeroHeader.kt
       WalkMetricRow.kt
       MatchingProgressBanner.kt
       StreetCoverageList.kt
       TierPill.kt
       BigStatCard.kt
   tokens/
       CoverageTierColors.kt   // tier color tokens
```

#### Rationale

- Each component file is independently previewable (`@Preview`).
- Big-screen file becomes a navigation-level concern only.
- Tier colors become reusable in e.g. `StreetDetailScreen` if needed.

#### Risks & Migration

- Pure organisational. No behavioural impact.
- Lower priority than #1-3 unless team velocity is bottlenecked by these files.

---

### 15. Extract initial-location lookup from screens into a reusable composable / use-case

**Location**:
- `app/src/main/java/com/streeter/ui/manual/ManualCreateScreen.kt:43-73` (last-known-location lookup with permission check)
- `app/src/main/java/com/streeter/ui/recording/RecordingScreen.kt:43-61` (same idea, simpler)

**Smell/Issue**: Duplicated permission/location lookup directly in `@Composable`; defaults Moscow as fallback (Streeter is Moscow-targeted but the fallback should probably be clarified or abstracted).
**Severity**: 🟢 Recommended
**Effort**: S (1h)

#### Current State

Both screens call `LocationManager.getLastKnownLocation` from inside `remember { ... }` with a permission check, falling back to Moscow `(55.7558, 37.6173)`. `ManualCreateScreen` is more sophisticated (tries `"fused"`, GPS, NETWORK; rejects stale > 1s); `RecordingScreen` is the older simpler version.

#### Proposed Refactoring

```kotlin
@Composable
fun rememberInitialMapLocation(fallback: LatLng = MoscowFallback): LatLng = ...
```

Or better: inject a `LastKnownLocationProvider` into the relevant viewmodels and emit it via state. Composables stay declarative.

#### Rationale

- Removes Android API access from `@Composable` bodies (testability + Compose preview safety).
- Single fallback definition.
- The 1-second freshness rule from the manual screen would then apply to recording too, which is the better behaviour.

#### Risks & Migration

- Low.

---

### 16. Pull `recordingTimer` out of `RecordingViewModel.init`

**Location**: `app/src/main/java/com/streeter/ui/recording/RecordingViewModel.kt:113-122`

**Smell/Issue**: `while(true) { delay(1000L) }` started in `init` — runs for the entire ViewModel lifetime even when the user is on a different screen, even when not recording.
**Severity**: 🟢 Recommended
**Effort**: S (<1h)

#### Current State

```kotlin
init {
    ...
    viewModelScope.launch {
        while (true) {
            delay(1000L)
            val segStart = segmentStartMs.value
            if (segStart > 0L) {
                _elapsedMs.value = accumulatedMs.value + (System.currentTimeMillis() - segStart)
            }
        }
    }
}
```

Always-running timer. Only does work when `segStart > 0L`, but the loop wakes every second forever.

#### Proposed Refactoring

Replace with a derived `Flow` that ticks only while recording:

```kotlin
val elapsedMs: StateFlow<Long> = combine(
    isWalkStarted, isPaused, accumulatedMs, segmentStartMs
) { started, paused, accumulated, segStart -> Triple(started && !paused, accumulated, segStart) }
    .flatMapLatest { (running, acc, start) ->
        if (!running) flowOf(acc)
        else flow {
            while (true) {
                emit(acc + (System.currentTimeMillis() - start))
                delay(1000L)
            }
        }
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)
```

#### Rationale

- Battery-friendlier (no wakeup when paused or screen offer).
- Idiomatic Flow composition.
- The `.stateIn(WhileSubscribed)` pattern naturally suspends the tick when no UI is collecting.

#### Risks & Migration

- Low. UI consumes the same `StateFlow<Long>`.

---

### 17. Make `RecordingViewModel` ↔ `LocationService` binding less stringly typed

**Location**: `app/src/main/java/com/streeter/ui/recording/RecordingViewModel.kt:63-87, 197-210`

**Smell/Issue**: ViewModel directly holds an Android `ServiceConnection` and binds to the Service. This couples the VM to the Android Service lifecycle and `Context`, making it untestable without Robolectric. Also creates the classic Service-binding leak risk if `onCleared` doesn't fire.
**Severity**: 🟢 Recommended
**Effort**: M (2-3h)

#### Current State

The VM owns an `@ApplicationContext` plus a `ServiceConnection`, calls `bindService`/`unbindService` directly. Because `LocationService.currentPoints` and `isPaused` are `StateFlow`s reachable only via the binder, the VM has to bind to forward them.

#### Proposed Refactoring

Three options, ascending in scope:
- **(a) Cheapest**: Move the binder into a `LocationServiceController` injected as a Hilt-singleton; the VM consumes flows from the controller. The controller binds once on first access.
- **(b) Medium**: Promote service state to a Hilt-singleton holder (`RecordingStateHolder`) that `LocationService` writes to; the VM only reads. `LocationService` becomes a "writer", VM becomes a "reader" — no binding needed.
- **(c) Best**: Use `androidx.lifecycle.ServiceLifecycleDispatcher` plus a bound-service-helper Compose API; wire via the Activity, not the VM.

Recommend (b) — cleanest separation and most testable.

#### Rationale

- ViewModel becomes a normal JVM-testable class.
- No more risk of leaked `ServiceConnection` references during configuration changes.
- The current code does call `unbind()` in `onCleared`, which is fine; but the structural coupling is the deeper smell.

#### Risks & Migration

- Touches both the service and the VM. Medium-risk; needs manual testing of: pause-during-process-death, resume after kill, normal stop.

---

### 18. Replace string-based `editMode` flag with a sealed-class `EditState`

**Location**: `app/src/main/java/com/streeter/ui/edit/RouteEditViewModel.kt:22-30, 32-45`

**Smell/Issue**: Primitive obsession — `EditMode` is an enum but the meaningful state ALSO lives in `anchor1/anchor2/waypoint/previewGeometryJson` (all nullable). E.g. "I'm in `PREVIEW` mode" implies all four are non-null but nothing enforces it.
**Severity**: 🟢 Recommended
**Effort**: S-M (1-2h)

#### Current State

```kotlin
enum class EditMode { IDLE, SELECT_ANCHOR_1, SELECT_ANCHOR_2, SELECT_WAYPOINT, RECALCULATING, PREVIEW, SAVING }
data class RouteEditUiState(
    val anchor1: LatLng? = null,
    val anchor2: LatLng? = null,
    val waypoint: LatLng? = null,
    val previewGeometryJson: String? = null,
    val editMode: EditMode = EditMode.IDLE,
    ...
)
```

`confirmPreview()` (line 164-205) starts with five `?: return` guards — a hint that the type isn't expressing the invariant.

#### Proposed Refactoring

```kotlin
sealed class EditState {
    object Idle : EditState()
    object PickingAnchor1 : EditState()
    data class PickingAnchor2(val anchor1: LatLng) : EditState()
    data class PickingWaypoint(val anchor1: LatLng, val anchor2: LatLng) : EditState()
    data class Recalculating(val anchor1: LatLng, val anchor2: LatLng, val waypoint: LatLng) : EditState()
    data class Preview(val anchor1: LatLng, val anchor2: LatLng, val waypoint: LatLng,
                       val previewGeometry: RouteGeometry) : EditState()
    object Saving : EditState()
}
```

`confirmPreview()` becomes:

```kotlin
val state = _editState.value as? EditState.Preview ?: return
// ...use state.anchor1, state.previewGeometry directly, no nulls
```

#### Rationale

- Eliminates the "5 nullable fields that must be non-null in PREVIEW" implicit contract.
- Compiler-enforced state machine.
- Pairs naturally with #1's `RouteGeometry`.

#### Risks & Migration

- Medium UI churn (the screen reads each individual nullable today).

---

## Service & Worker Layer

### 19. Extract notification-building from `LocationService`

**Location**: `app/src/main/java/com/streeter/service/LocationService.kt:340-373`

**Smell/Issue**: `LocationService` is doing too many things — location tracking, GPS filtering, batching/flushing, walk persistence, walk-status transitions, notification building, wake-lock management. Mild SRP violation.
**Severity**: 🟢 Recommended
**Effort**: S-M (1-2h)

#### Current State

`LocationService` is 380 lines. The notification creation and channel setup (lines 340-373) is purely incidental to the recording job.

#### Proposed Refactoring

Extract a `RecordingNotificationFactory` (small Hilt class) that owns the channel ID, R.string lookups, and `buildNotification(paused: Boolean)`. Service calls into it.

Likewise, the GPS-point batching state (`lastKeptPoint`, `pendingPoints`, `FLUSH_BATCH_SIZE`) is a candidate to extract into a `GpsBatchBuffer` class — would make it unit-testable without standing up a `LifecycleService`.

#### Rationale

- Service shrinks; each extracted class is independently testable.
- The notification factory in particular is reused if (when) a paused-walk reminder ever lands.

#### Risks & Migration

- Low. Mechanical extractions.

---

### 20. Fix wake-lock leak risk in `LocationService.flushPoints`

**Location**: `app/src/main/java/com/streeter/service/LocationService.kt:311-333`

**Smell/Issue**: `acquireWakeLock` overwrites the field without releasing the previous one — if two flushes interleave (possible because `flushPoints` is called from `lifecycleScope.launch`), the previous wake lock is dropped without `release()`.
**Severity**: 🟡 Important
**Effort**: S (<1h)

#### Current State

```kotlin
private fun acquireWakeLock() {
    val pm = getSystemService(POWER_SERVICE) as PowerManager
    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "streeter:flush")
        .apply { acquire(5_000L) }   // <-- if a previous lock exists in `wakeLock`, we leak it
}
```

`flushPoints` is called from at least two places (`handleNewPoint` when batch-full, plus pause/stop flows) — they could overlap.

#### Proposed Refactoring

Use a single wake-lock instance, ref-counted, or use `Mutex` to serialise flushes. Simpler:

```kotlin
private val flushMutex = Mutex()
private suspend fun flushPoints() = flushMutex.withLock {
    if (pendingPoints.isEmpty()) return@withLock
    val toFlush = pendingPoints.toList()
    pendingPoints.clear()
    val pm = getSystemService(POWER_SERVICE) as PowerManager
    val wl = pm.newWakeLock(PARTIAL_WAKE_LOCK, "streeter:flush").apply { acquire(5_000L) }
    try {
        gpsPointRepository.insertPoints(toFlush)
    } finally {
        if (wl.isHeld) wl.release()
    }
}
```

#### Rationale

- Plugs a real leak (PowerManager warns about leaked wake locks; user-visible battery drain in extremis).
- Mutex also prevents the dual-flush race that could insert the same point twice if `pendingPoints.toList()` happened twice before `clear()`.

#### Risks & Migration

- Low. Localised.

---

### 21. Remove `Volatile var isRunning` from `LocationService` companion

**Location**: `app/src/main/java/com/streeter/service/LocationService.kt:48`

**Smell/Issue**: Mutable global state on the companion — anti-pattern. Not actually read anywhere in the codebase (the field is set but I couldn't find a reader).
**Severity**: 🟢 Recommended
**Effort**: S (<5min)

#### Current State

`@Volatile var isRunning = false` set in `onCreate`/`onDestroy`. Not consumed.

#### Proposed Refactoring

Delete it. If a consumer is added later, expose the recording state via the `WalkRepository.getActiveRecordingWalk()` flow which already exists.

#### Risks & Migration

- None.

---

## Summary Table

| #  | Theme        | Severity | Effort | Title                                                  |
|----|--------------|----------|--------|--------------------------------------------------------|
| 1  | Cross-cutting| 🔴       | M      | Consolidate GeoJSON encode/decode                      |
| 2  | Cross-cutting| 🟡       | S      | RoutingEngineGateway / ensureReady()                   |
| 3  | Cross-cutting| 🟡       | S      | MapMatchingScheduler                                   |
| 4  | Cross-cutting| 🟢       | S      | Typed AppPreferences                                   |
| 5  | Cross-cutting| 🟢       | S      | Shared formatters                                      |
| 6  | Domain       | 🟡       | S      | Remove WalkSyncDto from domain                         |
| 7  | Domain       | 🟢       | M      | Sealed RoutingError                                    |
| 8  | Domain       | 🟢       | S      | Drop unused `lastPullSyncAt` column                    |
| 9  | Data         | 🟡       | M      | Split MapMatchingWorker.doWork                         |
| 10 | Data         | 🟡       | M      | WalkLifecycle use-case                                 |
| 11 | Data/UI      | 🟡       | S      | LocationService should honour user settings (bug)      |
| 12 | Data         | 🟡       | S      | withTransaction on paired ops                          |
| 13 | UI           | 🟢       | S      | Dedup mergeSegmentGeometries (subsumed by #1)          |
| 14 | UI           | 🟢       | M-L    | Split large screen files                               |
| 15 | UI           | 🟢       | S      | Reusable initial-location lookup                       |
| 16 | UI           | 🟢       | S      | Tick-only-when-recording timer                         |
| 17 | UI/Service   | 🟢       | M      | Decouple RecordingViewModel from LocationService bind  |
| 18 | UI           | 🟢       | S-M    | Sealed EditState in RouteEditViewModel                 |
| 19 | Service      | 🟢       | S-M    | Extract NotificationFactory + GpsBatchBuffer           |
| 20 | Service      | 🟡       | S      | Fix wake-lock leak in flushPoints                      |
| 21 | Service      | 🟢       | S      | Remove unused `LocationService.isRunning`              |

**Recommended sequencing** for a focused refactor sprint:

1. #1 (GeoJson utility) — unlocks #13 and clarifies #18.
2. #2 (RoutingEngineGateway) and #3 (MapMatchingScheduler) — together they eliminate ≈40% of repeated code in viewmodels and the worker.
3. #6 (domain leak), #11 (settings bug), #12 (transaction), #20 (wake-lock leak) — quick wins, real correctness improvements.
4. #9 (worker split) and #10 (WalkLifecycle) — bigger but pay back the most in long-term maintainability.
5. The UI re-organisation items (#14, #18) when the team has bandwidth; they don't fix bugs, only velocity.
