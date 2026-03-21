# Streeter

An Android app that tracks walks and computes what percentage of a city's streets the user has walked. Runs entirely offline.

Built with Kotlin and Jetpack Compose. Targets Android SDK 35, minimum SDK 35, Java 17.

---

## What It Does

Streeter records GPS tracks during walks, matches them to the street network using GraphHopper, and computes cumulative street coverage. Coverage data persists in a local Room database and is displayed per-walk and in aggregate. No network connection is required at runtime.

---

## Architecture

Three-layer clean architecture:

```
domain/   Pure Kotlin interfaces and models. No Android dependencies.
data/     Room database, repository implementations, GraphHopperEngine, StreetCoverageEngine.
ui/       Jetpack Compose screens and Hilt ViewModels, one subdirectory per screen.
di/       Hilt DI modules.
service/  LocationService — foreground service, FusedLocationProvider, GPS outlier filter, batched writes.
work/     MapMatchingWorker — WorkManager job, exponential backoff, max 3 retries.
map/      TileServerManager — NanoHTTPD HTTP server, serves PMTiles over loopback for MapLibre.
```

### Walk Lifecycle

```
RECORDING → PENDING_MATCH → COMPLETED
MANUAL_DRAFT (manually created walks)
```

**Recording:** `LocationService` runs as a foreground service and collects GPS points via `FusedLocationProviderClient`. Points are filtered for outliers and flushed to Room in batches of 50. When recording stops, the walk transitions to `PENDING_MATCH` and `MapMatchingWorker` is enqueued.

**Map matching:** `MapMatchingWorker` calls `GraphHopperEngine.matchRoute()`, then `StreetCoverageEngine.computeAndPersistCoverage()`. On success, `WalkStreetCoverage` and `WalkSectionCoverage` records are written and the walk is marked `COMPLETED`. The worker retries up to 3 times with exponential backoff on failure.

**Street section IDs:** Stable across OSM data updates. Each section ID is MD5(`streetName|fromNodeId|toNodeId`) truncated to 16 hex characters.

### Key Libraries

| Library | Role |
|---|---|
| Jetpack Compose + Material3 | UI |
| Hilt | Dependency injection |
| Room | Local database |
| MapLibre | Offline map rendering |
| GraphHopper | Routing and map matching |
| WorkManager | Background map matching jobs |
| NanoHTTPD | Local PMTiles tile server |
| FusedLocationProviderClient | GPS |
| Kotlinx Serialization | Serialization |
| Timber | Logging |
| KSP | Annotation processing (not KAPT) |

---

## Required Assets

Two binary assets are required before building a functional app. These are not included in the repository.

| Asset | Path | Purpose |
|---|---|---|
| OSM PBF | `app/src/main/assets/osm/city.osm.pbf` | GraphHopper routing and map matching |
| PMTiles | `app/src/main/assets/tiles/city.pmtiles` | Offline map tiles for MapLibre |

### Behavior Without Assets

**Without the PBF:** GraphHopper cannot build a routing graph. `MapMatchingWorker` will fail and retry up to 3 times, then leave walks in `PENDING_MATCH` state indefinitely.

**Without PMTiles:** The tile server returns 404 for all tile requests. MapLibre degrades gracefully — the map renders without a base layer but otherwise functions.

### First-Run Behavior

On first launch, GraphHopper copies `city.osm.pbf` from `assets/osm/` to `filesDir/city.osm.pbf` and builds a routing graph at `filesDir/graphhopper/`. This is a one-time operation and may take several minutes depending on PBF size.

### Obtaining Assets

Export the PBF for your target city from [Geofabrik](https://download.geofabrik.de/) or [BBBike](https://extract.bbbike.org/). Generate the PMTiles file from the same OSM data using [planetiler](https://github.com/onthegomap/planetiler) or [tilemaker](https://github.com/systemed/tilemaker). Both files must cover the same geographic area.

---

## Build Instructions

### Prerequisites

- Android Studio Hedgehog or later
- JDK 17
- Android SDK 35

### Commands

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

## Screens

| Screen | Entry Point | Description |
|---|---|---|
| PrivacyDisclosure | First launch only | Shown once before any other screen |
| Home | App launch | Start a walk, access history, manual create, settings |
| Recording | Start walk | Live GPS track displayed on map during active recording |
| History | Home | List of all past walks with summary stats |
| WalkDetail | History list item | Per-walk coverage stats and map visualization |
| RouteEdit | WalkDetail | Drag-to-edit route segments for a completed walk |
| ManualCreate | Home | Draw a walk route manually without GPS recording |
| Settings | Home | Configure GPS interval, speed filter, and related options |

### Deep Links

```
streeter://walk/{walkId}         Opens WalkDetail for the given walk
streeter://walk/{walkId}/edit    Opens RouteEdit for the given walk
```
