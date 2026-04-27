# Business Logic & Domain Model

---

## Architecture Overview

Streeter follows clean architecture with three layers. Dependencies point inward only.

```
UI  →  Domain  ←  Data
```

| Layer | Package | Allowed imports |
|---|---|---|
| `domain/` | Pure Kotlin | None (no Android, no Room) |
| `data/` | Room, GraphHopper, WorkManager | `domain/` only |
| `ui/` | Jetpack Compose, Hilt ViewModels | `domain/` only |

---

## Domain Models

### `Walk`

The central aggregate. Represents one completed or in-progress walk.

```kotlin
data class Walk(
    val id: Long = 0,
    val title: String?,          // nullable; user-assigned name
    val date: Long,              // start timestamp (epoch ms)
    val durationMs: Long,
    val distanceM: Double,
    val status: WalkStatus,
    val source: WalkSource,      // RECORDED | MANUAL
    val createdAt: Long,
    val updatedAt: Long
)
```

#### `WalkStatus` lifecycle

```
RECORDING
    │ LocationService.stopWalk()
    ▼
PENDING_MATCH ──► COMPLETED   (MapMatchingWorker succeeds)
                              (also if no assets: complete without coverage)
MANUAL_DRAFT ────► COMPLETED  (user submits manual route)

Any state ───────► DELETED    (soft-delete)
```

#### `WalkSource`

| Value | Created by |
|---|---|
| `RECORDED` | `LocationService` — GPS trace |
| `MANUAL` | Route editor — user-drawn path |

---

### `GpsPoint`

One GPS observation. `isFiltered = true` means `GpsOutlierFilter` marked it as an outlier and it is excluded from map matching.

```kotlin
data class GpsPoint(
    val id: Long = 0,
    val walkId: Long,
    val lat: Double,
    val lng: Double,
    val timestamp: Long,
    val accuracyM: Float,
    val speedKmh: Float,
    val isFiltered: Boolean
)
```

---

### `Street` and `StreetSection`

Streets are derived entirely from the OSM graph during coverage computation. They are not user-created.

```kotlin
data class Street(
    val id: Long = 0,
    val osmWayId: Long,
    val name: String,
    val cityTotalLengthM: Double,  // used as denominator in coverage %
    val osmDataVersion: Long,
    val osmNameHash: String        // change detection across data refreshes
)

data class StreetSection(
    val id: Long = 0,
    val streetId: Long,
    val fromNodeOsmId: Long,
    val toNodeOsmId: Long,
    val lengthM: Double,
    val geometryJson: String,      // GeoJSON LineString
    val stableId: String,          // see Stable Section ID below
    val isOrphaned: Boolean
)
```

#### Stable Section ID

```
stableId = MD5("streetName|fromNodeOsmId|toNodeOsmId")[0..15]
```

Derived from semantic data rather than OSM internal PKs, so it survives OSM data refreshes where way IDs are reassigned. Historical coverage records remain valid after a data update.

---

### Coverage Models

```kotlin
data class WalkStreetCoverage(
    val walkId: Long,
    val streetId: Long,
    val streetName: String,
    val coveragePct: Float,        // walkedLengthM / cityTotalLengthM
    val walkedLengthM: Double
)

data class WalkSectionCoverage(
    val walkId: Long,
    val sectionStableId: String,
    val coveredPct: Float
)
```

---

### Route and Edit Models

```kotlin
data class RouteSegment(
    val id: Long = 0,
    val walkId: Long,
    val geometryJson: String,      // GeoJSON LineString (matched or manual)
    val matchedWayIds: String,     // JSON: "[id1, id2, …]"
    val segmentOrder: Int
)

data class EditOperation(
    val id: Long = 0,
    val walkId: Long,
    val operationOrder: Int,
    val anchor1Lat: Double,   val anchor1Lng: Double,
    val anchor2Lat: Double,   val anchor2Lng: Double,
    val waypointLat: Double,  val waypointLng: Double,
    val replacedGeometryJson: String,
    val newGeometryJson: String,
    val createdAt: Long
)
```

---

### Result and Utility Models

```kotlin
data class MatchResult(
    val snappedPoints: List<LatLng>,
    val matchedWayIds: List<Long>,
    val routeGeometryJson: String
)

data class RouteResult(
    val geometryJson: String,
    val distanceM: Double,
    val wayIds: List<Long>
)

sealed class UiState<out T> {
    object Loading : UiState<Nothing>()
    data class Success<T>(val data: T) : UiState<T>()
    data class Error(val message: String, val throwable: Throwable?) : UiState<Nothing>()
}
```

---

## Repository Interfaces

All interfaces live in `domain/repository/`. Implementations in `data/repository/` are bound via Hilt.

### `WalkRepository`

```kotlin
fun getAllWalks(): Flow<List<Walk>>
fun observeWalk(id: Long): Flow<Walk?>
suspend fun getWalkById(id: Long): Walk?
suspend fun insertWalk(walk: Walk): Long
suspend fun updateWalk(walk: Walk)
suspend fun deleteWalk(id: Long)          // soft-delete
suspend fun getActiveRecordingWalk(): Walk?
fun getWalkWithCoverage(walkId: Long): Flow<List<WalkStreetCoverage>>
```

### `StreetRepository`

```kotlin
suspend fun upsertStreet(street: Street): Long
suspend fun getSectionsByStreetId(streetId: Long): List<StreetSection>
suspend fun upsertSection(section: StreetSection)
suspend fun getSectionByStableId(stableId: String): StreetSection?
suspend fun insertWalkStreetCoverage(coverage: WalkStreetCoverage)
suspend fun insertWalkSectionCoverage(coverage: WalkSectionCoverage)
suspend fun deleteWalkCoverageForWalk(walkId: Long)
suspend fun getStreetCoverageForWalk(walkId: Long): List<WalkStreetCoverage>
suspend fun getStreetCountForWalk(walkId: Long): Int
fun observeCoveredStreetCount(): Flow<Int>
fun observeTotalStreetCount(): Flow<Int>
suspend fun getStreetById(streetId: Long): Street?
suspend fun getCoveredLengthForStreet(streetId: Long): Double
suspend fun getWalksForStreet(streetId: Long): List<StreetWalkEntry>
suspend fun getCoveredSectionEdgeIdsForWalk(walkId: Long, streetId: Long): List<Long>
```

### `GpsPointRepository`

```kotlin
suspend fun insertPoints(points: List<GpsPoint>)
suspend fun getPointsForWalk(walkId: Long): List<GpsPoint>
fun observePointsForWalk(walkId: Long): Flow<List<GpsPoint>>
```

### `RouteSegmentRepository`

```kotlin
suspend fun insertSegment(segment: RouteSegment): Long
suspend fun getSegmentsForWalk(walkId: Long): List<RouteSegment>
suspend fun deleteSegmentsForWalk(walkId: Long)
```

### `EditOperationRepository`

```kotlin
suspend fun insertOperation(op: EditOperation): Long
suspend fun getOperationsForWalk(walkId: Long): List<EditOperation>
suspend fun deleteLastOperation(walkId: Long)     // undo
suspend fun deleteAllOperationsForWalk(walkId: Long)
```

### `PendingMatchJobRepository`

```kotlin
suspend fun enqueue(job: PendingMatchJob): Long
suspend fun getJobForWalk(walkId: Long): PendingMatchJob?
suspend fun updateJob(job: PendingMatchJob)
suspend fun deleteJobForWalk(walkId: Long)
```

---

## `RoutingEngine` Interface

Defined in `domain/engine/`. Implemented by `GraphHopperEngine` in `data/engine/`.

```kotlin
interface RoutingEngine {
    suspend fun initialize()
    suspend fun matchTrace(points: List<GpsPoint>): Result<MatchResult>
    suspend fun route(from: LatLng, to: LatLng, via: List<LatLng> = emptyList()): Result<RouteResult>
    fun getStreetName(edgeId: Long): String?
    fun getEdgeLength(edgeId: Long): Double?
    fun getStreetTotalLength(streetName: String): Double?
    fun getEdgeGeometry(edgeId: Long): String?
    fun findNearestNamedStreet(edgeId: Long): String?
    fun getEdgeGeometriesForStreet(streetName: String): List<String>
}
```

---

## Key Business Logic Components

### `LocationService` (foreground service)

Records GPS during a walk. Manages the `RECORDING` phase.

**Walk lifecycle methods:**

| Method | Effect |
|---|---|
| `startWalk()` | Creates a `RECORDING` walk, begins location updates |
| `stopWalk()` | Flushes remaining points, transitions walk to `PENDING_MATCH`, enqueues `MapMatchingWorker` |
| `resumeWalk(walkId)` | Resumes location updates for an existing `RECORDING` walk |

**GPS point pipeline:**
1. Location update fires every 20 seconds.
2. New `GpsPoint` is created from the OS location fix.
3. `GpsOutlierFilter.shouldKeep(prev, current)` is called. Outliers are stored with `isFiltered = true` (kept for audit; excluded from matching).
4. Point is appended to `currentPoints` (StateFlow, observed by UI).
5. Point is added to `pendingPoints`. When `pendingPoints` reaches 50 or `stopWalk()` is called, the batch is inserted via `GpsPointRepository`.

**State exposed to UI:**
- `currentPoints: StateFlow<List<GpsPoint>>`
- `isRecording: StateFlow<Boolean>`

---

### `GpsOutlierFilter`

Stateless utility. Detects implausible GPS jumps.

**Algorithm:**
```
distance = haversine(prev, current)            // metres
elapsed  = (current.timestamp - prev.timestamp) / 1000.0  // seconds
speed    = (distance / elapsed) * 3.6          // km/h

keep if speed ≤ maxSpeedKmh (default 50 km/h)
discard if elapsed ≤ 0
```

The 50 km/h threshold filters multipath/signal-loss spikes while preserving legitimate fast walking (≈ 6 km/h) with a comfortable safety margin.

---

### `MapMatchingWorker` (WorkManager)

Background job. Transitions a walk from `PENDING_MATCH` to `COMPLETED`.

**Retry policy:** exponential backoff, max 3 attempts. After 3 failures the job is marked `FAILED` and the walk remains in `PENDING_MATCH`.

**Execution flow:**

```
1. Initialize RoutingEngine (if not yet ready)             [0–15% progress]
2. Load Walk + GPS points                                   [15%]

   ┌── RECORDED walk ────────────────────────────────────┐
   │  Filter points (isFiltered = false)                  │
   │  If < 2 points → complete walk without coverage      │
   │  GraphHopperEngine.matchTrace(points)                 [20–50%]
   │  Persist RouteSegment (geometry + way IDs)            │
   └─────────────────────────────────────────────────────┘

   ┌── MANUAL walk ───────────────────────────────────────┐
   │  Load pre-built RouteSegments from route editor       │
   │  Extract way IDs from segment JSON                    │
   └─────────────────────────────────────────────────────┘

3. StreetCoverageEngine.computeAndPersistCoverage()        [50–90%]
4. Walk status → COMPLETED                                 [95%]
5. Job status → DONE

On FileNotFoundException (assets missing):
  → Complete walk without coverage (no retry)

On other exceptions:
  → Retry up to 3× with exponential backoff
  → After 3 failures: job status → FAILED
```

**Job status transitions:**

```
QUEUED → IN_PROGRESS → DONE
                    └──────→ FAILED  (after max retries)
```

---

### `GraphHopperEngine` (RoutingEngine implementation)

Wraps the GraphHopper library. Requires the OSM PBF asset bundled at `app/src/main/assets/osm/city.osm.pbf`.

**Initialization (lazy, thread-safe via `Mutex`):**
1. Copy PBF from assets to `filesDir/city.osm.pbf` on first run.
2. Rebuild graph if:
   - PBF is newer than the graph cache, or
   - Graph cache is missing the Landmarks (LM) profile.
3. Build with `foot` profile + LM. LM enables 10–100× faster A* queries.
4. Configure `MapMatching`:
   - `measurementErrorSigma = 15m` (reduced from 40m default → fewer candidates per observation)
   - `maxVisitedNodes = 2000` (caps HMM search on long GPS gaps)

**`matchTrace` — GPS trace → route geometry:**
1. Deduplicate points: enforce ≥ 20m minimum distance.
   - Prevents GraphHopper `QueryGraph` overflow.
   - Reduces observation count and HMM transition work ≈ 4×.
2. Run HMM-based map matching.
3. Return `MatchResult`: snapped points, matched way IDs, GeoJSON geometry.

**`route` — point-to-point routing:**
- Single segment (≤ 1 via point): one GraphHopper call.
- Multiple via points: fan out segment pairs in parallel, then merge geometry.

**Street metadata (lazy-built indices):**

| Method | Source |
|---|---|
| `getStreetName(edgeId)` | Direct edge attribute lookup |
| `findNearestNamedStreet(edgeId)` | BFS up to 3 hops from the edge |
| `getEdgeLength(edgeId)` | Edge attribute lookup |
| `getStreetTotalLength(streetName)` | `streetLengthIndex` (Map<String, Double>) built on first call |
| `getEdgeGeometriesForStreet(streetName)` | `streetEdgeIndex` (Map<String, List<Int>>) built on first call |

---

### `StreetCoverageEngine`

Computes and persists street coverage after map matching. Called by `MapMatchingWorker`.

**Entry point:**
```kotlin
suspend fun computeAndPersistCoverage(
    walkId: Long,
    matchedWayIds: List<Long>,
    onProgress: (suspend (processed: Int, total: Int) -> Unit)? = null
)
```

**Algorithm:**

```
1. Delete existing coverage records for walkId (idempotent recalculation)
2. Deduplicate matchedWayIds

For each way ID:
    a. streetName = getStreetName(wayId)
                 ?: findNearestNamedStreet(wayId)
                 ?: "Way {wayId}"
    b. edgeLength = getEdgeLength(wayId) ?: 0.0
    c. stableId   = MD5("streetName|fromNode|toNode")[0..15]
    d. Upsert Street record (osmWayId, name, cityTotalLengthM)
    e. Upsert StreetSection record (streetId, stableId, lengthM, geometry)
    f. Insert WalkSectionCoverage (walkId, stableId, coveredPct)

Group ways by street name:
    walkedLengthM = Σ edgeLength for all ways in group
    coveragePct   = walkedLengthM / cityTotalLengthM

For each street group:
    Insert WalkStreetCoverage (walkId, streetId, coveragePct, walkedLengthM)
```

**Performance notes:**
- Reuses a single `MessageDigest` instance across all stable ID generations to avoid per-call allocation.
- Progress callback fires after each way is processed, enabling `MapMatchingWorker` to report granular progress to WorkManager.

---

## Dependency Injection

All modules are installed in `SingletonComponent` (application scope).

### `DatabaseModule`

Provides the `StreeterDatabase` singleton and all DAOs.

| Provided | Scope |
|---|---|
| `StreeterDatabase` | Singleton |
| `WalkDao`, `GpsPointDao`, `StreetDao`, `RouteSegmentDao`, `EditOperationDao`, `PendingMatchJobDao` | Singleton |

Room is built with `enableMultiInstanceInvalidation()` and `MIGRATION_1_2`.

### `RepositoryModule`

Binds each repository interface to its implementation.

| Interface | Implementation |
|---|---|
| `WalkRepository` | `WalkRepositoryImpl` |
| `GpsPointRepository` | `GpsPointRepositoryImpl` |
| `StreetRepository` | `StreetRepositoryImpl` |
| `RouteSegmentRepository` | `RouteSegmentRepositoryImpl` |
| `EditOperationRepository` | `EditOperationRepositoryImpl` |
| `PendingMatchJobRepository` | `PendingMatchJobRepositoryImpl` |

### `EngineModule`

Binds `RoutingEngine` → `GraphHopperEngine` (Singleton).

### `WorkManagerModule`

Provides `WorkManager` singleton. `HiltWorkerFactory` enables constructor injection in `MapMatchingWorker`.

---

## End-to-End Data Flow

### Recorded walk

```
User taps "Start"
    │
    ▼
LocationService.startWalk()
    Creates RECORDING walk in DB
    Starts foreground notification + location updates
    │
    ▼ (every 20 s)
GpsOutlierFilter.shouldKeep()
    │
    ├─ keep  → GpsPoint(isFiltered=false)
    └─ drop  → GpsPoint(isFiltered=true)
    │
    ▼ (batch of 50 or on stop)
GpsPointRepository.insertPoints()
    │
User taps "Stop"
    │
    ▼
LocationService.stopWalk()
    Walk status → PENDING_MATCH
    PendingMatchJob enqueued (QUEUED)
    WorkManager schedules MapMatchingWorker
    │
    ▼
MapMatchingWorker.doWork()
    Job → IN_PROGRESS
    Filter GPS points (isFiltered=false)
    GraphHopperEngine.matchTrace()
        └─ Deduplicate points (≥20m)
        └─ HMM map matching
        └─ Returns MatchResult (way IDs + geometry)
    Persist RouteSegment
    StreetCoverageEngine.computeAndPersistCoverage()
        └─ For each way: upsert Street + StreetSection
        └─ Insert WalkSectionCoverage per way
        └─ Aggregate by street name
        └─ Insert WalkStreetCoverage per street
    Walk status → COMPLETED
    Job → DONE
    │
    ▼
UI observes Walk + WalkStreetCoverage via Flow
```

### Manual walk

```
User draws route in route editor
    │
    ▼
RouteSegmentRepository.insertSegment() (one per drawn segment)
EditOperationRepository.insertOperation() (one per edit, for undo)
    │
User submits route
    │
    ▼
Walk source = MANUAL, status = PENDING_MATCH
MapMatchingWorker (same as above, but skips matchTrace — loads pre-built segments)
    │
    ▼
Coverage computed from way IDs embedded in RouteSegments
Walk status → COMPLETED
```

---

## City Coverage Statistics

Two reactive queries power the home screen coverage display:

```kotlin
observeCoveredStreetCount(): Flow<Int>   // streets with at least one covered section
observeTotalStreetCount(): Flow<Int>     // total named streets in the graph
```

Coverage percentage = `coveredCount / totalCount × 100`.
