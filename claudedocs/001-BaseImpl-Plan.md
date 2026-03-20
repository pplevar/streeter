# Streeter — Android App Implementation Plan
**Source**: `docs/General-Requirements.md` (v1.1, 2026-03-20)
**Plan Version**: 1.1
**Status**: Draft — ready for `/sc:implement`

---

## Executive Summary

Build a street-level walk tracking Android app (Phase 1) that records GPS traces, map-matches them to OSM street data, computes per-street coverage, and allows post-walk route editing. All tooling must be zero-cost / open-source.

**Chosen Tech Stack**
| Concern | Choice | Rationale |
|---------|--------|-----------|
| Language | Kotlin (100%) | Modern Android standard, coroutines for async |
| UI | Jetpack Compose | Declarative, consistent with Material 3 |
| Map rendering | MapLibre GL Android | OSM-native, open-source, hardware-accelerated |
| Map tiles | Bundled city-level PMTiles file served via embedded HTTP (see tile strategy note) | Zero recurring cost, offline support, no APK size limit workaround needed |
| Routing & map-matching | GraphHopper on-device (embedded JVM lib + city OSM PBF) | Privacy-safe (no GPS data leaves device), offline capable, pedestrian profile; see OQ-1 |
| Local persistence | Room + SQLite (geometry stored as GeoJSON text — SpatiaLite not used; see note) | Durable, transactional, query-friendly; Room-native without custom SQLite builds |
| Background location | FusedLocationProviderClient + foreground Service | Battery-optimized, Android policy-compliant |
| DI | Hilt | Standard Jetpack DI |
| Architecture | MVVM + Repository + UseCases | Clean architecture, testable |
| Min SDK | API 35 | Per requirements |

**SpatiaLite note**: The original plan listed SpatiaLite but Room has no native SpatiaLite support — it would require a custom SQLite build with the SpatiaLite extension, adding significant build complexity and an unsupported dependency chain. All geometry is stored as GeoJSON text strings instead, consistent with MapLibre's input format.

**GraphHopper on-device vs remote**: Remote GraphHopper was the v1.0 default, but NFR-10 requires that no data be transmitted to external servers without explicit user consent. Sending GPS traces to a remote routing server violates this requirement directly. On-device GraphHopper (embedded as a JVM library, city-scoped OSM PBF ~20–60 MB) is the correct choice. The PBF file and map tiles are bundled assets; their combined size (~60–120 MB) fits comfortably within Android's 150 MB expansion file guidance and does not require Play Asset Delivery for sideloaded APKs. See Risk R-08 for the on-device initialization time concern.

---

## Phase Overview

```
Phase 1 → Project Foundation & Architecture
Phase 2 → Location & GPS Recording Service
Phase 3 → Map Integration & Visualization (raw GPS overlay only)
Phase 4 → Map-Matching & Street Coverage Engine
Phase 5 → Route Editing Interface
Phase 6 → Manual Walk Creation
Phase 7 → Walk Storage, History & Management
Phase 8 → Polish, Non-Functional Requirements & QA
```

---

## Phase 1 — Project Foundation & Architecture

**Goal**: Runnable skeleton with correct module structure, DI, navigation, and data layer contracts.

### Tasks

#### 1.1 Project Setup
- Create Android project: `minSdk 35`, Kotlin, Jetpack Compose, Material 3
- Configure `build.gradle.kts` with version catalog (`libs.versions.toml`)
- Add dependencies: Hilt, Room, MapLibre GL Android, Kotlin Coroutines, Kotlinx Serialization, Timber
- Configure Timber: `DEBUG` builds log all levels; `RELEASE` builds log `WARN` and above only; no PII (coordinates) in release logs

#### 1.2 Module Architecture
```
app/
├── data/
│   ├── local/         # Room DB, DAOs, entities
│   ├── repository/    # Repository implementations
│   └── model/         # Data models (Walk, Street, GpsPoint, RouteSegment)
├── domain/
│   ├── model/         # Domain models
│   ├── repository/    # Repository interfaces
│   └── usecase/       # Business logic use cases
├── ui/
│   ├── recording/     # Walk recording screen
│   ├── map/           # Map view screen
│   ├── history/       # Walk history screen
│   ├── edit/          # Route editing screen
│   └── common/        # Shared Compose components
└── service/
    └── LocationService.kt
```

#### 1.3 Database Schema (Room)

```sql
walks (
  id              INTEGER PRIMARY KEY,
  title           TEXT,
  date            INTEGER,          -- epoch ms
  duration_ms     INTEGER,
  distance_m      REAL,
  status          TEXT,             -- RECORDING | PENDING_MATCH | COMPLETED | MANUAL_DRAFT | DELETED
  source          TEXT,             -- RECORDED | MANUAL
  created_at      INTEGER,
  updated_at      INTEGER
)

gps_points (
  id              INTEGER PRIMARY KEY,
  walk_id         INTEGER REFERENCES walks(id) ON DELETE CASCADE,
  lat             REAL,
  lng             REAL,
  timestamp       INTEGER,          -- epoch ms
  accuracy_m      REAL,
  speed_kmh       REAL,
  is_filtered     INTEGER           -- 0/1 boolean; filtered points retained for audit
)

streets (
  id              INTEGER PRIMARY KEY,
  osm_way_id      INTEGER UNIQUE,
  name            TEXT,
  city_total_length_m REAL,
  osm_data_version    INTEGER,      -- epoch ms of last OSM fetch/refresh
  osm_name_hash       TEXT          -- hash(name) at last fetch; detect renames
)

street_sections (
  id              INTEGER PRIMARY KEY,
  street_id       INTEGER REFERENCES streets(id),
  from_node_osm_id INTEGER,
  to_node_osm_id   INTEGER,
  length_m        REAL,
  geometry_json   TEXT,             -- GeoJSON LineString
  stable_id       TEXT UNIQUE,      -- hash(name + from_node_id + to_node_id); survives OSM refreshes
  is_orphaned     INTEGER DEFAULT 0 -- 1 if street was renamed/deleted in OSM; historical walks retain the row
)

walk_streets (
  id              INTEGER PRIMARY KEY,
  walk_id         INTEGER REFERENCES walks(id) ON DELETE CASCADE,
  street_id       INTEGER REFERENCES streets(id),
  coverage_pct    REAL
)

walk_sections (
  id              INTEGER PRIMARY KEY,
  walk_id         INTEGER REFERENCES walks(id) ON DELETE CASCADE,
  section_stable_id TEXT,           -- references street_sections.stable_id (not FK; orphan-safe)
  covered_pct     REAL
)

route_segments (
  id              INTEGER PRIMARY KEY,
  walk_id         INTEGER REFERENCES walks(id) ON DELETE CASCADE,
  geometry_json   TEXT,             -- GeoJSON LineString of the matched/edited route
  matched_way_ids TEXT,             -- JSON array of OSM way IDs
  segment_order   INTEGER           -- ordering when multiple segments exist post-editing
)

edit_operations (
  id              INTEGER PRIMARY KEY,
  walk_id         INTEGER REFERENCES walks(id) ON DELETE CASCADE,
  operation_order INTEGER,
  anchor1_lat     REAL,
  anchor1_lng     REAL,
  anchor2_lat     REAL,
  anchor2_lng     REAL,
  waypoint_lat    REAL,
  waypoint_lng    REAL,
  replaced_geometry_json TEXT,      -- original segment geometry for undo
  new_geometry_json      TEXT,      -- replacement segment geometry
  created_at      INTEGER
)

pending_match_jobs (
  id              INTEGER PRIMARY KEY,
  walk_id         INTEGER REFERENCES walks(id) ON DELETE CASCADE,
  queued_at       INTEGER,
  status          TEXT,             -- QUEUED | IN_PROGRESS | DONE | FAILED
  retry_count     INTEGER DEFAULT 0,
  last_error      TEXT
)
```

**Schema design notes**:
- `walk_sections` references `section_stable_id` (text) rather than a foreign key to `street_sections.id` so that OSM-refreshed rows that change the integer PK do not orphan historical coverage records.
- `streets.osm_name_hash` enables detection of street renames across OSM refreshes, triggering the orphan-marking flow rather than silent data loss.
- `is_filtered = 1` GPS points are retained in DB (not deleted) so that the filter threshold can be adjusted post-walk without re-recording.
- `edit_operations` stores both the replaced and new geometry to support undo without holding geometry snapshots in memory.

#### 1.4 Navigation Graph
```
Home
├── Recording (start walk)
├── History
│   └── WalkDetail
│       ├── RouteEdit (deep link: streeter://walk/{id}/edit)
│       └── (back to History)
└── ManualCreate
    └── RouteEdit (same screen, MANUAL_DRAFT context)
```

**Back-stack note**: The `RouteEdit` screen has unsaved state. Android's predictive back gesture (API 33+) must be intercepted via `OnBackPressedCallback` to show an "Unsaved changes — discard?" confirmation dialog before popping the back stack. This applies on Android 16 (minSdk 35).

**Deep links**: `streeter://walk/{id}` opens WalkDetail; `streeter://walk/{id}/edit` opens RouteEdit directly. Intent filters declared in `AndroidManifest.xml`.

#### 1.5 Hilt Module Wiring
Provide: `WalkRepository`, `StreetRepository`, `LocationService`, `GraphHopperEngine`, `StreetCoverageEngine`, `TileServerManager`

#### 1.6 Error Handling Strategy
- All use cases return `Result<T>` (Kotlin stdlib) or a sealed `UiState<T>` at the ViewModel boundary.
- Network/IO failures in map-matching are caught at the repository layer and surfaced as `MatchingError` subtypes: `Offline`, `ProcessingFailed`, `InvalidTrace`.
- The UI layer never catches exceptions directly; it observes `UiState.Error` and renders a non-blocking `Snackbar` for recoverable errors and a full-screen error state for unrecoverable ones.
- All exceptions are logged with Timber at `ERROR` level with context (walk ID, operation name) but without raw coordinates in release builds.

**Checkpoint 1**: App launches to Home screen. No crash. DI resolves. DB migrates from empty schema.

---

## Phase 2 — Location & GPS Recording Service

**Goal**: Background GPS recording with outlier filtering. Satisfies FR-01–FR-04, NFR-01, NFR-03, NFR-06, NFR-10, NFR-11.

### Tasks

#### 2.1 Foreground Location Service (`LocationService`)
- `startForeground()` with persistent notification (walk in progress)
- Bind to `FusedLocationProviderClient` with `LocationRequest`:
  - `PRIORITY_HIGH_ACCURACY`
  - interval: configurable (default 20 s, range 5–60 s; RFP suggests 15–30 s as the practical window)
  - `fastestInterval`: half of interval
- Emit `GpsPoint` via `StateFlow` / `SharedFlow` to ViewModel
- Handle `ACTION_START_WALK`, `ACTION_STOP_WALK` intents
- On stop: transition walk status `RECORDING → PENDING_MATCH`

#### 2.2 Outlier Filter
```kotlin
object GpsOutlierFilter {
    fun filter(prev: GpsPoint, current: GpsPoint, maxSpeedKmh: Float = 50f): Boolean {
        val distM = haversineMeters(prev, current)
        val elapsedS = (current.timestamp - prev.timestamp) / 1000.0
        val speedKmh = (distM / elapsedS) * 3.6
        return speedKmh <= maxSpeedKmh   // true = keep
    }
}
```
- Configurable threshold via `SharedPreferences` (default 50 km/h, FR-04)
- Filtered points are written to DB with `is_filtered = 1` (retained for audit; not used in map-matching)

#### 2.3 Session Persistence (NFR-06)
- Flush `GpsPoint` records to Room every 30 s (or 50-point batch, whichever comes first)
- `Walk.status` values: `RECORDING | PENDING_MATCH | COMPLETED | MANUAL_DRAFT | DELETED`
- On app kill/crash: service recovers walk in `RECORDING` state on next launch; status check at app start triggers recovery flow
- Recovery flow: present "Resume walk?" dialog if a `RECORDING`-status walk exists

#### 2.4 Permissions Flow
- Runtime permission request: `ACCESS_FINE_LOCATION` → `ACCESS_BACKGROUND_LOCATION` (Android 11+ two-step)
- `PermissionRationaleDialog` composable for user education
- Graceful degradation when denied (NFR-11): disable record button, show explanatory inline message
- Re-prompt only when user explicitly taps "Start Walk" — no aggressive re-prompting

#### 2.5 Battery Optimizations (NFR-01)
- `PowerManager.WakeLock` (partial) acquired only during DB flush; released immediately after
- Request exemption from battery optimization via Settings deep-link (optional, user-initiated, shown once after first walk)
- Doze-mode safe: FLP handles Doze internally; no custom alarm-based polling

**Checkpoint 2**: Start walk → GPS points appear in DB with `is_filtered` flag → stop walk → status transitions to `PENDING_MATCH` → session survives app restart.

---

## Phase 3 — Map Integration & Visualization

**Goal**: MapLibre map rendered with raw GPS route overlay and live tracking during recording. Street highlights are added in Phase 4 once coverage data exists. Satisfies FR-20, FR-23 (FR-21, FR-22 completed in Phase 4).

**Dependency clarification**: Phase 3 delivers the map shell and raw route rendering. Street highlight layers (FR-21, FR-22) require coverage data from Phase 4 and are implemented there. This corrects the v1.0 plan which placed highlight layers in Phase 3 before the coverage engine existed.

### Tasks

#### 3.1 MapLibre Setup
- Add `org.maplibre.gl:android-sdk` dependency
- Configure style JSON pointing to local PMTiles tile server
- `MapLibreMapView` composable wrapper (interop with Compose via `AndroidView`)

#### 3.2 Tile Strategy

**Development**: Use public `demotiles.maplibre.org` style (requires connectivity; acceptable for dev).

**Production (chosen approach)**: Bundle a city-level PMTiles file in `assets/`. Serve it via an embedded HTTP server (e.g., NanoHTTPD or OkHttp MockWebServer in release) on localhost. MapLibre's style JSON points to `http://127.0.0.1:{port}/tiles/{z}/{x}/{y}.pbf`.

Rationale for PMTiles over mbtiles:
- PMTiles supports HTTP range requests, compatible with MapLibre's tile fetching without a full tile server.
- A single-city PMTiles file is typically 30–80 MB; acceptable for asset bundling.
- Offline by design (NFR-04); no network dependency for map display.

The tile server `TileServerManager` is a Hilt-provided singleton started in `Application.onCreate()` and stopped in `Application.onTerminate()`. Port is selected dynamically to avoid conflicts.

**Note on OQ-2**: The specific city's PMTiles file must be selected before the production build. Architecture is city-agnostic; the file path is a build-time constant.

#### 3.3 Raw GPS Route Overlay Layer
- Convert `List<GpsPoint>` (unfiltered = false only) → GeoJSON `LineString`
- Add `GeoJsonSource` + `LineLayer` with color `#3B82F6`, width 4 dp
- Live update during active recording via `StateFlow` observation
- Filtered points (`is_filtered = 1`) shown as semi-transparent red dots on the overlay for transparency

#### 3.4 Camera Controls
- Auto-fit bounds to route on walk review open
- Track user location during active recording (follow mode toggle)
- Follow mode disengages on manual pan; re-engage button in UI

#### 3.5 Accessibility (a11y)
- Map `AndroidView` wrapper provides a content description: "Map showing walk route"
- All FABs and toolbar buttons have `contentDescription` set
- Tap-to-view street detail (FR-22, Phase 4) additionally surfaces data in a non-map list below the map for screen reader users
- Minimum touch target size 48 × 48 dp for all interactive elements

**Checkpoint 3**: Open completed walk → raw GPS route drawn on map → live location tracked during recording → map gestures (pan, zoom, tap) work.

---

## Phase 4 — Map-Matching & Street Coverage Engine

**Goal**: Match GPS trace to OSM streets on-device; compute section-level and street-level coverage. Satisfies FR-05–FR-06, FR-21, FR-22, Section 6.2.

### Tasks

#### 4.1 GraphHopper On-Device Integration

GraphHopper runs as an embedded JVM library. No GPS data leaves the device (NFR-10 compliant).

**Setup**:
- Add GraphHopper core dependency (walking profile only; exclude unused profiles to reduce JAR size)
- Bundle city OSM PBF in `assets/` (or download on first launch if APK size is a concern — see R-08)
- Initialize `GraphHopper` instance in a background coroutine on first use; cache the initialized instance
- Expose behind `RoutingEngine` interface for testability and future swap

**Initialization time concern**: GraphHopper graph preparation from PBF on first launch takes 10–60 seconds depending on city size and device speed. Mitigations:
- Pre-process the graph at build time using GraphHopper's offline graph format (`.osm-gh` directory) and bundle it rather than the raw PBF
- Show a one-time "Preparing map data…" progress screen on first launch
- Subsequent launches load the pre-built graph in < 2 s

**`RoutingEngine` interface**:
```kotlin
interface RoutingEngine {
    suspend fun matchTrace(points: List<GpsPoint>): MatchResult
    suspend fun route(from: LatLng, to: LatLng, via: List<LatLng> = emptyList()): RouteResult
}
```

#### 4.2 Map Matching Pipeline
```
GpsPoints (is_filtered = 0 only)
  → serialize to GPX / point list
  → GraphHopper MapMatching.doWork()
  → MatchResult (snapped points, way IDs, edge IDs)
  → persist RouteSegment with matched_way_ids
  → transition walk status: PENDING_MATCH → COMPLETED
```

Error handling: if matching fails (too few points, disconnected trace), store `PENDING_MATCH` with error in `pending_match_jobs`. Surface "Route could not be matched — tap to retry" in walk detail.

#### 4.3 Street Identification (Section 6.2)
```
For each OSM way_id in matched result:
  1. Query GraphHopper's graph storage for way properties (name tag, node list)
  2. Extract name tag → Street entity (upsert by osm_way_id)
  3. Identify intersection nodes: nodes shared by ≥ 2 named ways
  4. Split way geometry at intersection nodes → StreetSection entities
  5. Assign stable_id: SHA-256(street_name + from_node_id + to_node_id) truncated to 16 hex chars
  6. Detect renames: compare streets.osm_name_hash; if changed, mark existing sections is_orphaned = 1,
     insert new sections with new stable_ids, preserve walk_sections rows via old stable_id
```

**Unnamed ways**: OSM ways without a `name` tag are skipped for coverage purposes (footpaths, alleys). They appear in the route geometry but not in the street list. This is a deliberate design choice; revisit in Phase 2 of the product if desired.

#### 4.4 Coverage Computation
```kotlin
// Section-level coverage
fun sectionCoverage(section: StreetSection, matchedEdgeIds: Set<Long>): Float {
    val coveredLength = section.edges
        .filter { it.osmEdgeId in matchedEdgeIds }
        .sumOf { it.lengthM }
    return (coveredLength / section.lengthM).toFloat().coerceIn(0f, 1f)
}

// Street-level rollup (length-weighted)
fun streetCoverage(street: Street, sections: List<StreetSection>, coverages: Map<String, Float>): Float {
    val totalLen = sections.sumOf { it.lengthM }
    if (totalLen == 0.0) return 0f
    val coveredLen = sections.sumOf { it.lengthM * (coverages[it.stableId] ?: 0f) }
    return (coveredLen / totalLen).toFloat()
}
```

Note: `streetCoverage` uses `section.stableId` (String) as the map key, not the integer PK, so that coverage rollup works correctly after OSM refreshes that reassign PKs.

#### 4.5 Street Highlight Layer (FR-21, FR-22)
- For each covered `StreetSection` → GeoJSON `Feature` with `coverage_pct` property
- `LineLayer` color expression: `interpolate [linear] coverage 0 #FEF3C7 1 #10B981`
- Layer visibility: shown at zoom ≥ 14 (streets legible); fade via opacity expression at zoom 13–14
- Tap interaction: `MapLibreMap.addOnMapClickListener` → query features → show `BottomSheet` composable: street name, street-level coverage %, list of sections with per-section coverage

#### 4.6 OSM Data Freshness (Section 6.2)
- `streets.osm_data_version` stores epoch ms of last graph build/refresh
- On app update (version bump) or manual "Refresh map data" in settings: rebuild GraphHopper graph from updated PBF, re-run street identification, mark renamed/deleted sections as orphaned
- Historical `walk_sections` rows reference `section_stable_id` (text) and are never deleted — coverage history is preserved even if the underlying OSM geometry changes

#### 4.7 Processing Trigger
- Auto-trigger after walk status transitions to `PENDING_MATCH`
- Runs in `WorkManager` `OneTimeWorkRequest` (survives process death)
- Show progress indicator in WalkDetail and History list while status is `PENDING_MATCH`
- `pending_match_jobs` table tracks retry state; max 3 retries with exponential backoff

**Checkpoint 4**: Complete walk → map-matching runs on-device → streets table populated → coverage percentages computed → street highlights visible on map → tap shows coverage sheet.

---

## Phase 5 — Route Editing Interface

**Goal**: Anchor-point + waypoint editing with segment recalculation. Satisfies FR-07–FR-11.

### Tasks

#### 5.1 Edit Mode State Machine
```
IDLE
  → SELECT_ANCHOR_1       (user taps route to place first anchor)
  → SELECT_ANCHOR_2       (user taps route to place second anchor)
  → SELECT_WAYPOINT       (user taps map to place correction waypoint)
  → RECALCULATING         (GraphHopper routing in progress)
      → PREVIEW           (new segment shown; user confirms or discards)
          → COMMITTED     (edit applied to in-memory route; another correction can begin)
          → IDLE          (user discarded this correction)
      → FAILED            (routing error; show error snackbar; revert to SELECT_WAYPOINT)
  SAVING                  (user taps Save; coverage recalculation in progress)
  → COMPLETED             (route + coverage persisted; navigate back)
```

**Unsaved changes guard**: Transitioning away from the edit screen (back gesture, system back, navigation) while edits are uncommitted triggers a confirmation dialog: "Discard unsaved edits?" This uses `OnBackPressedCallback` with `OnBackPressedDispatcher` to intercept predictive back on API 33+ (mandatory on minSdk 35).

#### 5.2 Anchor & Waypoint Selection UI
- Tap on route line → snap to nearest GPS/matched point → place `AnchorMarker`
- Require 2 anchors before enabling waypoint selection
- Waypoint: free tap anywhere on map (not constrained to route)
- Visual: anchor = filled circle `#EF4444`, waypoint = diamond `#8B5CF6`

#### 5.3 Segment Recalculation
```
GraphHopper RoutingEngine.route(
  from = anchor1,
  via  = [waypoint],
  to   = anchor2
)
→ NewSegment (GeoJSON LineString)
→ Splice into existing route: route[0..anchor1] + newSegment + route[anchor2..end]
```

#### 5.4 Multiple Corrections (FR-09)
- Each correction is an independent `EditOperation` applied sequentially to the in-memory route
- Committed operations are written to `edit_operations` table immediately (for crash recovery)
- Show correction count badge in toolbar

#### 5.5 Undo / Discard (FR-11)
- Undo: reads the most recent `edit_operations` row, restores `replaced_geometry_json` to the in-memory route, deletes the row
- "Discard all": deletes all uncommitted `edit_operations` rows for this session, reverts to persisted `route_segments`
- "Save": writes new `route_segments`, triggers coverage recalculation via WorkManager (same pipeline as Phase 4), transitions status back to `PENDING_MATCH` briefly, then `COMPLETED`

**Undo implementation note**: The v1.0 plan used an in-memory `ArrayDeque<RouteSnapshot>` (storing full geometry copies). Replaced with DB-backed `edit_operations` rows storing only the replaced segment geometry (not the full route). This is more memory-efficient for long routes and survives process death during an editing session.

#### 5.6 Coverage Recalculation After Edit
- Reuse Phase 4 `StreetCoverageEngine` via WorkManager on the new spliced route geometry
- Update `walk_streets` and `walk_sections` tables
- `walk_sections` rows use `section_stable_id`, so recalculation correctly updates coverage for sections that now appear or disappear from the edited route

**Checkpoint 5**: Open walk in edit mode → select 2 anchors → select waypoint → segment reroutes → PREVIEW shown → confirm → undo restores → save updates coverage. Back gesture from unsaved state shows confirmation dialog.

---

## Phase 6 — Manual Walk Creation

**Goal**: User specifies start + end → app generates shortest walking route. Satisfies FR-12–FR-15.

### Tasks

#### 6.1 Manual Create Screen
- Two tap modes: `SET_START` | `SET_END` (toggle button in toolbar)
- Place start (green pin) and end (red pin) markers on map
- "Generate Route" FAB (enabled when both pins placed)

#### 6.2 Route Generation
```
GraphHopper RoutingEngine.route(from = start, to = end)
→ RouteGeometry (GeoJSON LineString)
→ Create Walk entity: status = MANUAL_DRAFT, source = MANUAL
→ Persist route_segments
→ Run coverage pipeline (WorkManager, same as Phase 4)
→ Status transitions: MANUAL_DRAFT → PENDING_MATCH → COMPLETED
```

#### 6.3 Editing Integration (FR-14)
- Generated route is immediately editable via Phase 5 interface
- No difference in edit logic between recorded and manual walks; `RouteEditViewModel` is walk-source-agnostic

#### 6.4 Save Manual Walk (FR-15)
- `walks.source = MANUAL` flag
- Appears in history list with "Manual" badge
- Distance computed from route geometry (not GPS odometer)

**Checkpoint 6**: Set start + end on map → route generated on-device → coverage computed → route editable → saved to history with MANUAL badge.

---

## Phase 7 — Walk Storage, History & Management

**Goal**: Browse, view, and delete saved walks. Satisfies FR-16–FR-19.

### Tasks

#### 7.1 History List Screen
- `LazyColumn` of `WalkCard` composables
- Card shows: date, duration, distance, street count, source badge (RECORDED / MANUAL), status badge (PENDING_MATCH if processing)
- Sort options: newest first (default), longest, most streets
- Empty state illustration

#### 7.2 Walk Detail Screen
- Header: metadata summary
- Map preview (non-interactive thumbnail via MapLibre snapshot API)
- Streets covered list: grouped by coverage tier (100%, 50–99%, < 50%)
- Actions: Edit Route, Delete
- If status is `PENDING_MATCH`: show inline "Processing route…" with cancel option (cancels WorkManager job, reverts to raw GPS display)

#### 7.3 Delete Walk (FR-19)
- `AlertDialog` confirmation
- Cascade delete handled by Room FK `ON DELETE CASCADE`: `gps_points`, `route_segments`, `walk_streets`, `walk_sections`, `edit_operations`, `pending_match_jobs`
- Undo snackbar (5 s window): soft-delete pattern using `status = DELETED`; permanent delete only after snackbar dismissal
- If walk is currently `RECORDING`: delete is blocked with explanatory message (stop the walk first)

#### 7.4 Storage Durability (NFR-07)
- Room WAL mode enabled
- DB backup via Android Auto Backup (manifest config, exclude `gps_points` from cloud backup due to size — walks metadata and coverage data only)
- "Export all data" in settings: writes JSON dump of all walks + streets to `Downloads/` via `MediaStore`

**Checkpoint 7**: History lists all walks with correct status badges. Tapping opens detail. Pending-match walks show processing state. Delete works with confirmation and undo.

---

## Phase 8 — Polish, NFR Coverage & QA

**Goal**: All non-functional requirements satisfied; QA report generated. Satisfies NFR-01 through NFR-11.

### Tasks

#### 8.1 Battery Audit (NFR-01, NFR-02)
- Profile with Android Studio Energy Profiler
- Verify background service uses < 3% battery/hour at 20 s interval
- Ensure map rendering releases GPU resources when screen off (pause MapLibre in `onPause`)
- GraphHopper engine: confirm no background threads running between operations

#### 8.2 Offline Mode (NFR-03, NFR-05)
- GPS recording works with airplane mode: dedicated test case
- Map-matching runs on-device: offline by design; no queue needed for basic matching
- If GraphHopper graph is not yet initialized (first launch, still loading): show "Map data loading…" and queue the match job until ready
- `pending_match_jobs` handles the "engine not ready" case via retry logic

#### 8.3 Performance Validation (NFR-08, NFR-09)
- Overlay render benchmark: 50-street walk renders ≤ 2 s (automated JUnit test with MapLibre render callback)
- Route recalculation benchmark: 10 km route recalculates ≤ 5 s on a mid-range device (Pixel 6 equivalent)
- GraphHopper graph load time: ≤ 3 s for pre-built graph on cold start (after first-launch prep)

#### 8.4 Settings Screen
- GPS sample interval (5 s – 60 s slider)
- Max speed threshold for outlier filter (20–100 km/h)
- "Refresh map data" (re-processes OSM graph; shows progress)
- Export all data (JSON to Downloads)
- Clear all data option (with confirmation)
- Tile source URL: removed from user-facing settings (internal constant; not a user concern)

#### 8.5 Permissions & Privacy (NFR-10, NFR-11)
- Privacy disclosure screen on first launch: states what data is collected, that nothing leaves the device, and what the background location is used for
- No telemetry, no analytics, no network calls except: (a) local tile server on loopback, (b) on-device GraphHopper (no network)
- "Export all data" is the only mechanism for data leaving the device; it is user-initiated and explicit
- `android:usesCleartextTraffic="false"` in manifest — loopback tile server is exempt via network security config

#### 8.6 Accessibility (a11y) Audit
- Run TalkBack walkthrough of all primary flows: start walk, stop walk, view history, open walk detail, edit route
- Verify all interactive elements have `contentDescription` or `semantics` block
- Check color contrast ratios: route overlay colors (`#3B82F6`, `#10B981`) meet WCAG AA against map background
- Street coverage list provides text alternatives to the map highlight (screen reader users can read coverage without map interaction)

#### 8.7 Open Question Resolution (Section 11.2)
- **i18n**: Until resolved, implement English-only with `strings.xml` fully externalized
  - All user-facing strings in `res/values/strings.xml` (no hardcoded strings)
  - Architecture ready for `values-XX/` locale folders — adding a locale requires only translation files
  - RTL layout support: use `start`/`end` instead of `left`/`right` throughout; test with RTL pseudo-locale

#### 8.8 QA Test Suite (D-07)
- Unit tests: `GpsOutlierFilter`, `StreetCoverageEngine`, `EditOperation` splicing, coverage rollup, section stable ID generation
- Integration tests: Room DAO round-trips, repository contracts, WorkManager job execution
- UI tests (Compose Test): recording flow, edit flow, history navigation, back-gesture unsaved-changes dialog
- Target: ≥ 80% line coverage on domain and data layers
- Performance regression tests: overlay render time, route recalculation time

#### 8.9 Build & Documentation (D-05, D-06)
- `README.md` with build instructions (JDK 17, Gradle wrapper, PMTiles file placement, GraphHopper pre-built graph setup)
- `USER_GUIDE.md` covering all FR features
- ProGuard rules for MapLibre, GraphHopper, Room

**Checkpoint 8**: All FR/NFR pass manual verification. QA report generated. APK builds from clean checkout. TalkBack walkthrough passes primary flows.

---

## Dependency Map

```
Phase 1 (Foundation)
  └─► Phase 2 (GPS Service)
        ├─► Phase 3 (Map Shell + Raw Route Overlay)  ──────┐
        └─► Phase 4 (Map-Matching & Coverage)              │
              ├─► [completes Phase 3 street highlights]  ◄─┘
              ├─► Phase 5 (Route Editing)
              └─► Phase 6 (Manual Walk Creation)
Phase 7 (History) depends on: Phase 2 + Phase 4 (walk data exists)
Phase 8 (Polish) depends on: ALL phases complete
```

**Parallelization opportunities**:
- Phase 3 (3.1–3.4, map shell and raw route overlay) and Phase 4 (4.1, GraphHopper on-device setup) have no mutual dependency and can be worked in parallel by separate developers. Phase 3.5 (street highlights) must wait for Phase 4 coverage data contracts to be defined.
- Phase 6 (Manual Walk Creation) is largely independent of Phase 5 (Route Editing) at the UI layer; both depend on Phase 4's `RoutingEngine`. They can be implemented concurrently once Phase 4's interface is stable.
- Phase 7 (History UI) can begin its list/detail skeleton in parallel with Phase 4, since the list only needs `Walk` entity data from Phase 2.

---

## Risk Register

| # | Risk                                                                                      | Probability | Impact | Mitigation |
|---|-------------------------------------------------------------------------------------------|-------------|--------|------------|
| R-01 | GraphHopper on-device map-matching accuracy poor on sparse GPS (20 s interval)            | Medium | High | Increase sample rate option; allow manual corrections (Phase 5); tune HMM radius parameter |
| R-02 | MapLibre PMTiles tile server on loopback causes latency or port conflicts                 | Low | Medium | Port selected dynamically; tile server benchmarked in Phase 3; fallback to direct asset reads if needed |
| R-03 | Battery drain exceeds target                                                              | Low | High | Profile in Phase 2 before committing to sample rate defaults; reduce interval if needed |
| R-04 | OSM data staleness breaks section stable IDs (street renames)                             | Low | Medium | `osm_name_hash` detects renames; orphan-marking preserves history; migration tool in Phase 8 |
| R-05 | Android background location policy changes post API 35                                    | Low | High | Abstract `LocationProvider` interface for swap; monitor Android release notes |
| R-06 | i18n question unresolved (Section 11.2)                                                   | High | Low | Strings externalized + RTL-safe layouts; adding locale = translation files only |
| R-07 | GraphHopper graph preparation on first launch takes > 60 s on low-end devices             | Medium | Medium | Pre-build `.osm-gh` graph at compile time; bundle pre-built graph instead of raw PBF; show progress UI |
| R-08 | Bundled PMTiles + pre-built graph exceeds comfortable APK size (~150 MB total)            | Medium | Medium | Use Play Asset Delivery (on-demand asset packs) for Play Store distribution; sideload APK unaffected |
| R-09 | GraphHopper JVM memory pressure causes OOM on low-RAM devices (< 2 GB)                    | Low | High | Profile heap usage; use GraphHopper's memory-mapped mode; set minimum RAM requirement in Play listing |
| R-10 | WorkManager job for map-matching killed on aggressive OEM battery savers (Huawei, Xiaomi) | Medium | Medium | Document known OEM issues in README; show "Processing may be delayed" notice for first walk; provide manual retry |

---

## Deliverables Mapping

| Deliverable | Produced In |
|-------------|-------------|
| D-01 Technical Proposal | This document + Architecture ADRs |
| D-02 Project Plan | This phase breakdown |
| D-03 Working APK | Phase 8 completion |
| D-04 Source Code | GitHub repo (all phases) |
| D-05 Build Instructions | Phase 8.9 |
| D-06 User Guide | Phase 8.9 |
| D-07 QA Report | Phase 8.8 |

---

## Open Questions (from RFP)

| # | Question | Impact on Plan | Status |
|---|----------|----------------|--------|
| OQ-1 | GraphHopper: on-device vs remote? | Phase 4 architecture | **Resolved: on-device** — remote violates NFR-10 (GPS data transmitted without consent); on-device GraphHopper with bundled pre-built graph is the correct architecture |
| OQ-2 | Which city's OSM data to bundle? | Phase 3 tile strategy + Phase 4 PBF selection | **Pending client input** — city selection determines PMTiles file and OSM PBF; required before production build |
| OQ-3 | i18n (RFP Section 11.2) | Phase 8.7 | **Pending** — strings externalized + RTL-safe layouts in place; awaiting decision |

---

## Architect Review Notes
**Plan version**: 1.0 → 1.1
**Review date**: 2026-03-20

The following changes were made and why:

### Critical: NFR-10 violation in v1.0 (remote GraphHopper)
The v1.0 plan defaulted to a remote GraphHopper instance. NFR-10 states "no data shall be transmitted to external servers without explicit user consent." GPS traces sent to a remote routing server are a direct violation — GPS coordinates are personal location data. OQ-1 is now resolved as on-device. The architecture change is non-trivial (APK size, first-launch initialization, memory) but necessary and implementable.

### Critical: SpatiaLite claim was incorrect
The v1.0 tech stack listed "Room + SQLite (with SpatiaLite for geometry)." Room has no SpatiaLite support; enabling it requires a custom SQLite build that is unsupported in the standard Android Gradle toolchain. Removed. Geometry is stored as GeoJSON text, which is what MapLibre consumes directly anyway.

### DB schema: multiple gaps corrected
- Added `walks.source` (RECORDED / MANUAL) — required by FR-15, referenced by Phase 6/7 but missing from schema.
- Added `walks.status` value `PENDING_MATCH` — used in Phase 2/4 logic but not in the v1.0 schema definition.
- Added `streets.osm_data_version` and `streets.osm_name_hash` — the v1.0 plan described freshness logic in prose but the columns were absent from the schema.
- Added `street_sections.stable_id` and `street_sections.is_orphaned` — the stable ID concept was described in 4.3 but not reflected in the schema.
- Changed `walk_sections.section_id` from integer FK to `section_stable_id` text reference — an integer FK would break when OSM refresh reassigns PKs, defeating the purpose of stable IDs.
- Added `edit_operations` table — the v1.0 undo mechanism was an in-memory `ArrayDeque<RouteSnapshot>` which does not survive process death during editing. DB-backed edit operations with stored geometry diffs solve this.
- Added `pending_match_jobs` table — the v1.0 plan described offline queueing but had no schema to back it.

### Phase 3/4 dependency ordering corrected
v1.0 placed street highlight layers in Phase 3, but those layers require coverage data that doesn't exist until Phase 4. Phase 3 now delivers the map shell and raw GPS overlay only. Street highlights (FR-21, FR-22) are completed in Phase 4. The dependency map was updated accordingly.

### Edit undo mechanism replaced
v1.0 used `ArrayDeque<RouteSnapshot>` with up to 20 full geometry copies in memory. For a 10 km route with many GPS points, each snapshot can be hundreds of KB. Replaced with DB-backed `edit_operations` storing only the per-edit replaced/new segment geometry (not the full route), which is crash-safe and memory-efficient.

### Tile strategy made concrete
v1.0 deferred the tile strategy decision with two options and no recommendation. The plan now commits to PMTiles + embedded loopback HTTP server (production) and demotiles for development, with rationale. This is a design decision that affects Phase 1 (server wiring in Hilt) and must be settled before implementation begins.

### Missing cross-cutting concerns added
- **Error handling strategy** (1.6): `Result<T>` / sealed `UiState<T>`, layered error surfacing, no raw exception propagation to UI.
- **Logging policy** (1.1): Timber levels per build type; no PII in release logs.
- **Accessibility (a11y)** (3.5, 8.6): Content descriptions, touch targets, WCAG contrast check, screen-reader-accessible coverage list.
- **Android back-stack / predictive back** (1.4, 5.1): `OnBackPressedCallback` for unsaved-edit guard on API 33+; mandatory on minSdk 35.
- **Deep links** (1.4): `streeter://walk/{id}` and `streeter://walk/{id}/edit` declared in manifest.
- **WorkManager for map-matching** (4.7): v1.0 described a processing trigger without specifying the mechanism. WorkManager is the correct choice for durable background work that survives process death.

### Risk register updated
- R-01 reframed from "remote instance unavailable" to "on-device accuracy on sparse GPS" (the relevant risk after OQ-1 resolution).
- R-02 reframed from tile source reliability to loopback tile server latency.
- Removed original R-01 (remote GraphHopper unavailability) — moot after architecture change.
- Added R-07 (GraphHopper first-launch initialization time).
- Added R-08 (APK size with bundled assets).
- Added R-09 (on-device OOM on low-RAM devices).
- Added R-10 (OEM battery saver killing WorkManager jobs).

### OQ-1 resolved
Remote GraphHopper was not a valid option under NFR-10. Marked as resolved in the open questions table.

### Phase parallelization analysis added
The dependency map now explicitly identifies three parallelization opportunities for teams with more than one developer, reducing calendar time for Phases 3–6.

### Minor corrections
- GPS interval default: v1.0 said 20 s; RFP Section 4.1 FR-02 suggests 15–30 s. Plan now states 20 s default and notes the RFP's suggested range.
- `walks.status` enum now listed completely in both schema and phase 2 (RECORDING, PENDING_MATCH, COMPLETED, MANUAL_DRAFT, DELETED).
- Unnamed OSM ways: explicitly documented as excluded from coverage (they appear in route geometry but not the street list). This is an implicit assumption in v1.0 that needed to be explicit.

---

*Next step: `/sc:implement` against this plan, starting with Phase 1.*
