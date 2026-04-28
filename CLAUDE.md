# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

```bash
# Build
./gradlew assembleDebug
./gradlew assembleRelease

# Run all unit tests
./gradlew testDebugUnitTest

# Run a single test class
./gradlew testDebugUnitTest --tests "com.streeter.RouteEditOperationTest"

# Run all checks (lint + tests)
./gradlew check
```

Unit tests live in `app/src/test/` and run on the JVM (no emulator needed). Android instrumentation tests live in `app/src/androidTest/`.

## Architecture

Clean architecture with three layers:

- **`domain/`** — pure Kotlin; defines repository interfaces, domain models, and `RoutingEngine` interface. No Android imports.
- **`data/`** — implements domain interfaces: Room database (`local/`), repository implementations (`repository/`), and two engines (`engine/`).
- **`ui/`** — Jetpack Compose screens + Hilt `ViewModel`s, one subdirectory per screen.

DI wiring lives in `di/` (Hilt modules). `service/` holds `LocationService` (foreground). `work/` holds `MapMatchingWorker`. `map/` holds `TileServerManager`.

## Key Data Flows

**Recording a walk:**
`LocationService` (foreground service) → batches GPS points (flush at 50) via `GpsOutlierFilter` → persists to Room → on stop, enqueues `MapMatchingWorker` and sets walk status to `PENDING_MATCH`.

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

`StreeterNavGraph` in `ui/navigation/` defines all routes via the `Screen` sealed class. Entry point is `Screen.Home`. Deep links follow the scheme `streeter://walk/{walkId}` and `streeter://walk/{walkId}/edit`.

## Tooling Preferences

Use the `android` CLI (android-cli) as the preferred tool for:
- **Emulator management** — `android emulator create/start/stop/list`
- **APK deployment** — `android run`
- **UI inspection** — `android layout`, `android screen capture`, `android screen resolve`
- **SDK management** — `android sdk install/list/update/remove`
- **Android docs** — `android docs search`

Prefer `android` CLI over raw `adb` or `avdmanager` for these tasks. Continue using `./gradlew` for building and testing.

## DI Modules

| Module | Provides |
|---|---|
| `DatabaseModule` | Room database + all DAOs |
| `RepositoryModule` | Repository interface → impl bindings |
| `EngineModule` | `RoutingEngine` → `GraphHopperEngine` binding |
| `WorkManagerModule` | `HiltWorkerFactory` for `MapMatchingWorker` |
