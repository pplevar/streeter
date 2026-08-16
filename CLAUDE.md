# CLAUDE.md

## Architecture

Clean architecture: `domain/` is pure Kotlin — no Android imports.

## Key Data Flows

**Recording a walk:**
`LocationService` (foreground service) owns the Android side — notification, location callbacks, lifecycle — and delegates every decision to `RecordingSession` (`domain/recording/`), which batches GPS points (flush at 50) via `GpsOutlierFilter`, persists them to Room, and accumulates duration across pause/resume off an injectable `Clock`. On stop it enqueues a `PendingMatchJob` and hands the walk to `WalkRecalculator`, which sets `PENDING_MATCH` and schedules Sync + Calculation.

**Walk status lifecycle:** `RECORDING` → `PENDING_MATCH` → `COMPLETED` (or `MANUAL_DRAFT` for manually created walks).

**Map matching (background):**
`MapMatchingWorker` (WorkManager, exponential backoff, max 3 retries) → calls `GraphHopperEngine.matchRoute()` → calls `StreetCoverageEngine.computeAndPersistCoverage()` → persists `WalkStreetCoverage` and `WalkSectionCoverage` records → sets walk to `COMPLETED`.

**Street coverage IDs:** `StreetCoverageEngine` generates stable section IDs using MD5(`streetName|fromNodeId|toNodeId`), truncated to 16 hex chars. These survive OSM primary-key reassignments across data refreshes.

## Asset Requirements

Two bundled assets are required for full functionality (not included in the repo):

| Asset | Path | Purpose |
|---|---|---|
| OSM PBF | `app/src/main/assets/osm/city.osm.pbf` | GraphHopper routing & map matching |
| PMTiles | `app/src/main/assets/tiles/city.pmtiles` | Offline map tiles (NanoHTTPD tile server) |

`GraphHopperEngine` copies the PBF to `filesDir/city.osm.pbf` on first run and builds a GraphHopper graph at `filesDir/graphhopper/`. Without the PBF, `MapMatchingWorker` will fail and retry.
`TileServerManager` starts a loopback NanoHTTPD server (OS-assigned port) serving PMTiles. Without the PMTiles file, tile requests return 404 but MapLibre degrades gracefully.

## Navigation

Prefer `android` CLI over raw `adb` or `avdmanager` for these tasks. Continue using `./gradlew` for building and testing.

## Agent skills

### Issue tracker

Issues and PRDs live as GitHub issues, managed via the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Five canonical triage roles using the default label strings. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: `CONTEXT.md` + `docs/adr/` at the repo root. See `docs/agents/domain.md`.
