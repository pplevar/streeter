# Database Structure

Streeter uses Room (SQLite) with 9 entities. Database class: `StreeterDatabase` (version 2).

**Migration 1→2:** Adds `walkedLengthM REAL DEFAULT 0.0` column to `walk_streets`.

---

## Entities

### `walks`

Represents a single recorded or manually created walk.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | INTEGER | PK, autoGenerate | |
| `title` | TEXT | nullable | User-assigned title |
| `date` | INTEGER | NOT NULL | Walk start timestamp (epoch ms) |
| `durationMs` | INTEGER | NOT NULL | Total walk duration in milliseconds |
| `distanceM` | REAL | NOT NULL | Total distance in metres |
| `status` | TEXT | NOT NULL | See [Walk Status](#walk-status) |
| `source` | TEXT | NOT NULL | `RECORDED` or `MANUAL` |
| `createdAt` | INTEGER | NOT NULL | Row creation timestamp |
| `updatedAt` | INTEGER | NOT NULL | Last update timestamp |

---

### `gps_points`

Raw GPS observations captured by `LocationService` during a walk.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | INTEGER | PK, autoGenerate | |
| `walkId` | INTEGER | NOT NULL, FK→walks.id CASCADE DELETE | |
| `lat` | REAL | NOT NULL | |
| `lng` | REAL | NOT NULL | |
| `timestamp` | INTEGER | NOT NULL | Observation epoch ms |
| `accuracyM` | REAL | NOT NULL | GPS accuracy in metres |
| `speedKmh` | REAL | NOT NULL | Reported speed in km/h |
| `isFiltered` | INTEGER | NOT NULL | `1` = outlier, excluded from map matching |

**Indices:** `walkId`

---

### `streets`

Named streets extracted from the OSM graph. One row per unique street name encountered during coverage computation.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | INTEGER | PK, autoGenerate | |
| `osmWayId` | INTEGER | NOT NULL, UNIQUE | OSM way ID |
| `name` | TEXT | NOT NULL | Street name |
| `cityTotalLengthM` | REAL | NOT NULL | Total length of this street across the city (metres) |
| `osmDataVersion` | INTEGER | NOT NULL | Timestamp of last OSM data refresh |
| `osmNameHash` | TEXT | NOT NULL | MD5 of street name (for change detection) |

**Indices:** `osmWayId` (unique)

---

### `street_sections`

Individual graph edges belonging to a street. One section = one directed OSM way segment between two nodes.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | INTEGER | PK, autoGenerate | |
| `streetId` | INTEGER | NOT NULL, FK→streets.id CASCADE DELETE | |
| `fromNodeOsmId` | INTEGER | NOT NULL | Origin node OSM ID |
| `toNodeOsmId` | INTEGER | NOT NULL | Destination node OSM ID |
| `lengthM` | REAL | NOT NULL | Edge length in metres |
| `geometryJson` | TEXT | NOT NULL | GeoJSON LineString |
| `stableId` | TEXT | NOT NULL, UNIQUE | Stable cross-version identifier (see note below) |
| `isOrphaned` | INTEGER | NOT NULL, DEFAULT 0 | `1` if parent street was removed in a data refresh |

**Indices:** `streetId`, `stableId` (unique)

> **Stable ID:** `MD5("streetName|fromNodeOsmId|toNodeOsmId")` truncated to 16 hex characters. Survives OSM primary-key reassignments because it is derived from semantic data, not internal PKs.

---

### `walk_streets`

Per-walk street coverage summary. One row per street touched by a walk.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | INTEGER | PK, autoGenerate | |
| `walkId` | INTEGER | NOT NULL, FK→walks.id CASCADE DELETE | |
| `streetId` | INTEGER | NOT NULL | References `streets.id` (not enforced by FK) |
| `coveragePct` | REAL | NOT NULL | Fraction of street walked (0.0–1.0) |
| `walkedLengthM` | REAL | NOT NULL, DEFAULT 0.0 | Metres walked on this street |

**Indices:** `walkId`, `streetId`

---

### `walk_sections`

Per-walk section-level coverage. One row per `street_sections` edge covered by a walk.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | INTEGER | PK, autoGenerate | |
| `walkId` | INTEGER | NOT NULL, FK→walks.id CASCADE DELETE | |
| `sectionStableId` | TEXT | NOT NULL | References `street_sections.stableId` |
| `coveredPct` | REAL | NOT NULL | Coverage fraction for this section (0.0–1.0) |

**Indices:** `walkId`, `sectionStableId`

---

### `route_segments`

Map-matched or manually built route geometry for a walk. Multiple segments represent a route broken at manually edited points.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | INTEGER | PK, autoGenerate | |
| `walkId` | INTEGER | NOT NULL, FK→walks.id CASCADE DELETE | |
| `geometryJson` | TEXT | NOT NULL | GeoJSON LineString of the matched/routed path |
| `matchedWayIds` | TEXT | NOT NULL | JSON array of OSM way IDs: `[id1, id2, …]` |
| `segmentOrder` | INTEGER | NOT NULL | Ordering index within the walk |

**Indices:** `walkId`

---

### `edit_operations`

Audit log of manual edits made to a walk route in the route editor. Used for undo and replay.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | INTEGER | PK, autoGenerate | |
| `walkId` | INTEGER | NOT NULL, FK→walks.id CASCADE DELETE | |
| `operationOrder` | INTEGER | NOT NULL | Sequence number within the walk |
| `anchor1Lat`, `anchor1Lng` | REAL | NOT NULL | First anchor point of the edited segment |
| `anchor2Lat`, `anchor2Lng` | REAL | NOT NULL | Second anchor point of the edited segment |
| `waypointLat`, `waypointLng` | REAL | NOT NULL | Waypoint that the user dragged through |
| `replacedGeometryJson` | TEXT | NOT NULL | GeoJSON LineString of the segment before the edit |
| `newGeometryJson` | TEXT | NOT NULL | GeoJSON LineString of the segment after the edit |
| `createdAt` | INTEGER | NOT NULL | Edit timestamp |

**Indices:** `walkId`

---

### `pending_match_jobs`

Tracks the lifecycle of each `MapMatchingWorker` job.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | INTEGER | PK, autoGenerate | |
| `walkId` | INTEGER | NOT NULL, FK→walks.id CASCADE DELETE | |
| `queuedAt` | INTEGER | NOT NULL | Enqueue timestamp |
| `status` | TEXT | NOT NULL | `QUEUED`, `IN_PROGRESS`, `DONE`, `FAILED` |
| `retryCount` | INTEGER | NOT NULL, DEFAULT 0 | Attempts so far |
| `lastError` | TEXT | nullable | Last failure message |

**Indices:** `walkId`

---

## Walk Status

| Value | Meaning |
|---|---|
| `RECORDING` | Walk is actively recording GPS points via `LocationService` |
| `PENDING_MATCH` | Walk stopped; `MapMatchingWorker` job enqueued |
| `COMPLETED` | Map matching finished; coverage data persisted |
| `MANUAL_DRAFT` | Manually drawn route not yet submitted |
| `DELETED` | Soft-deleted; excluded from all queries |

---

## DAO Reference

### `WalkDao`

| Method | Description |
|---|---|
| `getAllWalks(): Flow<List<WalkEntity>>` | All non-deleted walks, ordered by `date DESC` |
| `getById(id): WalkEntity?` | Single walk by PK |
| `observeById(id): Flow<WalkEntity?>` | Reactive walk by PK |
| `insert(walk): Long` | Insert, returns new PK |
| `update(walk)` | Full row update |
| `softDelete(id)` | Sets `status = DELETED` |
| `hardDelete(id)` | Physical row deletion |
| `getActiveRecording(): WalkEntity?` | Walk with `status = RECORDING`, LIMIT 1 |

### `GpsPointDao`

| Method | Description |
|---|---|
| `insertAll(points)` | Batch insert |
| `getUnfilteredPoints(walkId): List<GpsPointEntity>` | Points where `isFiltered = 0` |
| `observePoints(walkId): Flow<List<GpsPointEntity>>` | All points ordered by timestamp |

### `StreetDao`

| Method | Description |
|---|---|
| `upsertStreet(street): Long` | Insert-or-replace street |
| `getStreetByOsmWayId(osmWayId): StreetEntity?` | |
| `upsertSection(section)` | Insert-or-replace section |
| `getSectionsByStreetId(streetId): List<StreetSectionEntity>` | |
| `getSectionByStableId(stableId): StreetSectionEntity?` | |
| `insertWalkStreet(coverage)` | |
| `insertWalkSection(coverage)` | |
| `deleteWalkStreets(walkId)` | Clears street coverage for a walk |
| `deleteWalkSections(walkId)` | Clears section coverage for a walk |
| `observeWalkCoverage(walkId): Flow<List<WalkStreetWithName>>` | Reactive coverage with street names |
| `getWalkCoverage(walkId): List<WalkStreetWithName>` | One-shot coverage read |
| `getStreetCountForWalk(walkId): Int` | |
| `observeCoveredStreetCount(): Flow<Int>` | City-wide covered street count |
| `observeTotalStreetCount(): Flow<Int>` | City-wide total street count |
| `getStreetById(streetId): StreetEntity?` | |
| `getCoveredLengthForStreet(streetId): Double` | Sum of `lengthM` across covered sections |
| `getWalksForStreet(streetId): List<StreetWalkRow>` | Completed walks that touched a street |
| `getCoveredSectionEdgeIdsForWalk(walkId, streetId): List<Long>` | Edge IDs covered in a specific walk+street combination |

**Custom DTOs:**
- `WalkStreetWithName` — flattened join: `(id, walkId, streetId, streetName, coveragePct, walkedLengthM)`
- `StreetWalkRow` — `(walkId, walkDate, walkTitle, walkedLengthM, coveragePct)`

### `RouteSegmentDao`

| Method | Description |
|---|---|
| `insert(segment): Long` | |
| `getSegmentsForWalk(walkId): List<RouteSegmentEntity>` | |
| `deleteForWalk(walkId)` | |

### `EditOperationDao`

| Method | Description |
|---|---|
| `insert(op): Long` | |
| `getOperationsForWalk(walkId): List<EditOperationEntity>` | Ordered by `operationOrder` |
| `deleteLastOperation(walkId)` | Deletes the row with the highest `id` for the walk (undo) |
| `deleteAllForWalk(walkId)` | |

### `PendingMatchJobDao`

| Method | Description |
|---|---|
| `insert(job): Long` | |
| `getJobForWalk(walkId): PendingMatchJobEntity?` | |
| `update(job)` | |
| `deleteForWalk(walkId)` | |

---

## Entity Relationship Diagram

```
walks (1)─────────────────────────────(N) gps_points
  │                                             
  ├──(1)──────────────────────────────(N) walk_streets
  │                                             
  ├──(1)──────────────────────────────(N) walk_sections
  │                                             
  ├──(1)──────────────────────────────(N) route_segments
  │                                             
  ├──(1)──────────────────────────────(N) edit_operations
  │                                             
  └──(1)──────────────────────────────(1) pending_match_jobs

streets (1)─────────────────────────(N) street_sections
  │                                             
  └─────────────────────────────────(N) walk_streets (via streetId)

street_sections ──stableId──────────(N) walk_sections (via sectionStableId)
```

All child tables cascade-delete when their parent `walks` row is deleted.
