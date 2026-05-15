# Streeter

An Android app that tracks walks and computes street coverage — what percentage of a city's streets you have walked.

Built with Kotlin and Jetpack Compose. Targets Android SDK 35 (minimum SDK 35), Java 17.

---

## How It Works

Streeter records GPS tracks during walks, matches them to the street network using GraphHopper, and computes cumulative street coverage. Coverage data is stored in a local Room database and displayed per-walk and in aggregate. Map tiles and routing data are bundled as local assets — no network connection is required for recording or map matching. An optional background sync service can push completed walks to a remote API when a connection is available.

---

## Quick Start

### Prerequisites

- Android Studio Hedgehog or later
- JDK 17
- Android SDK 35

### Required Assets

Two binary assets must be placed in the repository before the app is functional. They are not included.

| Asset   | Path                                          | Purpose                              |
|---------|-----------------------------------------------|--------------------------------------|
| OSM PBF | `app/src/main/assets/osm/city.osm.pbf`        | GraphHopper routing and map matching |
| PMTiles | `app/src/main/assets/tiles/city.pmtiles`      | Offline map tiles for MapLibre       |

Both files must cover the same geographic area.

**Obtaining assets:** Download a city extract in PBF format from [Geofabrik](https://download.geofabrik.de/) or [BBBike](https://extract.bbbike.org/). Generate the PMTiles file from the same OSM data using [planetiler](https://github.com/onthegomap/planetiler) or [tilemaker](https://github.com/systemed/tilemaker).

**First run:** GraphHopper copies `city.osm.pbf` from `assets/osm/` to `filesDir/city.osm.pbf` and builds a routing graph at `filesDir/graphhopper/`. This is a one-time operation and may take several minutes depending on file size.

**Without the PBF:** `MapMatchingWorker` will fail and retry up to 3 times, leaving walks in `PENDING_MATCH` state indefinitely.

**Without PMTiles:** The tile server returns 404 for all tile requests. MapLibre degrades gracefully — the map renders without a base layer but otherwise functions.

### Build Commands

```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease

# Run all unit tests
./gradlew testDebugUnitTest

# Run a specific test class
./gradlew testDebugUnitTest --tests "com.streeter.RouteEditOperationTest"
```

The assembled APK is output to `app/build/outputs/apk/`.

---

## Architecture

Three-layer clean architecture:

```
domain/    Pure Kotlin interfaces and models. No Android dependencies.
data/      Room database, repository implementations, GraphHopperEngine, StreetCoverageEngine.
           data/remote/ holds the Ktor API client and DTOs for optional walk sync.
ui/        Jetpack Compose screens and Hilt ViewModels, one subdirectory per screen.
           ui/map/ holds the shared MapLibre composable used across screens.
di/        Hilt modules.
service/   LocationService — foreground service, FusedLocationProvider, GPS outlier filter, batched writes.
work/      WorkManager jobs: MapMatchingWorker (offline map matching), SyncWorker and PullSyncWorker (remote sync, network-gated).
map/       TileServerManager — NanoHTTPD server serving PMTiles over loopback for MapLibre.
lifecycle/ AppForegroundObserver — triggers PullSyncWorker on app foreground.
```

### Walk Status Lifecycle

```
RECORDING → PENDING_MATCH → COMPLETED
MANUAL_DRAFT  (manually created walks, bypasses recording)
DELETED       (soft-deleted walks)
```

**Recording:** `LocationService` runs as a foreground service and collects GPS points via `FusedLocationProviderClient`. Points are filtered for outliers and flushed to Room in batches of 50. On stop, the walk moves to `PENDING_MATCH` and `MapMatchingWorker` is enqueued.

**Map matching:** `MapMatchingWorker` calls `GraphHopperEngine.matchRoute()`, then `StreetCoverageEngine.computeAndPersistCoverage()`. On success, `WalkStreetCoverage` and `WalkSectionCoverage` records are written and the walk is marked `COMPLETED`. The worker retries up to 3 times with exponential backoff.

**Street section IDs:** Stable across OSM data refreshes. Each ID is MD5(`streetName|fromNodeId|toNodeId`) truncated to 16 hex characters.

### Key Libraries

| Library                      | Role                                     |
|------------------------------|------------------------------------------|
| Jetpack Compose + Material3  | UI                                       |
| Hilt                         | Dependency injection                     |
| Room                         | Local database                           |
| MapLibre                     | Offline map rendering                    |
| GraphHopper                  | Routing and map matching                 |
| WorkManager                  | Background jobs (map matching and sync)  |
| NanoHTTPD                    | Local PMTiles tile server                |
| FusedLocationProviderClient  | GPS                                      |
| Ktor                         | HTTP client for optional remote sync     |
| Kotlinx Serialization        | Serialization                            |
| Timber                       | Logging                                  |
| KSP                          | Annotation processing                    |

---

## Screens

| Screen            | Entry Point        | Description                                          |
|-------------------|--------------------|------------------------------------------------------|
| PrivacyDisclosure | First launch only  | Shown once before any other screen                   |
| Home              | App launch         | Start a walk, access history, manual create, settings|
| Recording         | Home               | Live GPS track on map during active recording        |
| History           | Home               | All past walks with summary stats                    |
| WalkDetail        | History            | Per-walk coverage stats and map visualization        |
| StreetDetail      | WalkDetail         | Coverage history and map for a single street segment |
| RouteEdit         | WalkDetail         | Drag-to-edit route segments for a completed walk     |
| ManualCreate      | Home               | Draw a route manually without GPS recording          |
| Settings          | Home               | GPS interval, speed filter, and related options      |

### Deep Links

```
streeter://walk/{walkId}       Opens WalkDetail for the given walk
streeter://walk/{walkId}/edit  Opens RouteEdit for the given walk
```
