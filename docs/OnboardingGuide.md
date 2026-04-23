# Streeter — Developer Onboarding Guide

Welcome to the Streeter project! This guide walks you through the project's structure, dependencies, and data schemas so you can get productive quickly.

---

## Table of Contents

1. [What is Streeter?](#1-what-is-streeter)
2. [Prerequisites & Setup](#2-prerequisites--setup)
3. [Architecture Overview](#3-architecture-overview)
4. [File Structure](#4-file-structure)
5. [Dependencies](#5-dependencies)
6. [Data Schemas](#6-data-schemas)
7. [Key Data Flows](#7-key-data-flows)
8. [Dependency Injection](#8-dependency-injection)
9. [Navigation](#9-navigation)
10. [Testing](#10-testing)
11. [Build Commands](#11-build-commands)

---

## 1. What is Streeter?

Streeter is an Android app (Kotlin + Jetpack Compose) that:

- **Records** GPS walks using your phone's location services
- **Map-matches** raw GPS traces to actual street geometry using a local offline map database
- **Tracks** which streets you've walked and what percentage of each street you've covered
- **Lets you edit** routes manually on the map if the automatic matching isn't quite right

The app works fully offline. All map data and routing is bundled as local assets.

---

## 2. Prerequisites & Setup

**Tools you need:**
- Android Studio Hedgehog or newer
- JDK 17
- Android SDK (minSdk 35 / Android 15)

**Two asset files are required** (not included in the repo — ask your team lead):

| File | Where to place it | Why it's needed |
|------|------------------|-----------------|
| `city.osm.pbf` | `app/src/main/assets/osm/` | OpenStreetMap data for routing and map matching |
| `city.pmtiles` | `app/src/main/assets/tiles/` | Offline map tiles displayed on-screen |

> **Without these assets:** Unit tests still run fine, but the app will fail to match routes (it retries 3 times then gives up) and maps will display blank tiles.

**Build and run:**
```bash
./gradlew assembleDebug     # builds a debug APK
./gradlew testDebugUnitTest # runs unit tests (no emulator needed)
```

---

## 3. Architecture Overview

Streeter follows **Clean Architecture** with three distinct layers. Think of them as walls — inner layers know nothing about outer layers.

```
┌─────────────────────────────────────────┐
│  UI Layer  (Jetpack Compose + ViewModels) │  ← sees domain only
├─────────────────────────────────────────┤
│  Data Layer  (Room, Engines, Repos)      │  ← sees domain only
├─────────────────────────────────────────┤
│  Domain Layer  (Models, Interfaces)      │  ← pure Kotlin, no Android
└─────────────────────────────────────────┘
```

| Layer | Location | Responsibility |
|-------|----------|----------------|
| **Domain** | `domain/` | Business rules, data models, repository interfaces. No Android imports whatsoever. |
| **Data** | `data/` | Room database, repository implementations, routing engines. Translates between database entities and domain models. |
| **UI** | `ui/` | Compose screens, ViewModels, navigation. Talks to repositories through ViewModels. |

**Why this structure?**
- You can unit-test business logic without an Android device.
- You can swap the database or map engine without touching the UI.
- Each layer has a clear, single purpose — easier to navigate.

**Other important components:**

| Component | Location | Purpose |
|-----------|----------|---------|
| `LocationService` | `service/` | Foreground service; collects GPS while app is backgrounded |
| `MapMatchingWorker` | `work/` | Background WorkManager task; matches GPS trace to streets |
| `TileServerManager` | `map/` | Local HTTP server that serves map tile assets to MapLibre |
| Hilt modules | `di/` | Wires all dependencies together automatically |

---

## 4. File Structure

```
app/src/main/java/com/streeter/
│
├── domain/                        # Pure Kotlin — no Android dependencies
│   ├── model/                     # All data classes the app works with
│   │   ├── Walk.kt                # A single recorded walk session
│   │   ├── WalkStatus.kt          # Enum: RECORDING → PENDING_MATCH → COMPLETED
│   │   ├── WalkSource.kt          # Enum: RECORDED | MANUAL
│   │   ├── GpsPoint.kt            # One GPS coordinate with metadata
│   │   ├── Street.kt              # An OSM street (the whole street)
│   │   ├── StreetSection.kt       # One segment/block of a street
│   │   ├── WalkStreetCoverage.kt  # How much of a street this walk covered
│   │   ├── WalkSectionCoverage.kt # How much of a section this walk covered
│   │   ├── RouteSegment.kt        # Map-matched geometry for a walk
│   │   ├── EditOperation.kt       # A manual route edit the user made
│   │   ├── PendingMatchJob.kt     # Tracks the background matching job
│   │   ├── LatLng.kt              # Simple lat/lng coordinate pair
│   │   ├── MatchResult.kt         # Output from GraphHopper map matching
│   │   └── RouteResult.kt         # Output from GraphHopper routing
│   ├── repository/                # Interfaces — what the data layer must provide
│   │   ├── WalkRepository.kt
│   │   ├── GpsPointRepository.kt
│   │   ├── StreetRepository.kt
│   │   ├── RouteSegmentRepository.kt
│   │   ├── EditOperationRepository.kt
│   │   └── PendingMatchJobRepository.kt
│   └── engine/
│       └── RoutingEngine.kt       # Interface for map matching + routing
│
├── data/                          # Android-specific: Room + engines
│   ├── local/
│   │   ├── entity/                # Room @Entity classes (one per DB table)
│   │   │   ├── WalkEntity.kt
│   │   │   ├── GpsPointEntity.kt
│   │   │   ├── StreetEntity.kt
│   │   │   ├── StreetSectionEntity.kt
│   │   │   ├── WalkStreetCoverageEntity.kt
│   │   │   ├── WalkSectionCoverageEntity.kt
│   │   │   ├── RouteSegmentEntity.kt
│   │   │   ├── EditOperationEntity.kt
│   │   │   └── PendingMatchJobEntity.kt
│   │   ├── dao/                   # Data Access Objects (SQL queries as Kotlin)
│   │   │   ├── WalkDao.kt
│   │   │   ├── GpsPointDao.kt
│   │   │   ├── StreetDao.kt
│   │   │   ├── RouteSegmentDao.kt
│   │   │   ├── EditOperationDao.kt
│   │   │   └── PendingMatchJobDao.kt
│   │   └── mapper/                # Convert entity ↔ domain model
│   │       ├── WalkMapper.kt
│   │       ├── GpsPointMapper.kt
│   │       └── StreetMapper.kt
│   ├── repository/                # Implements domain repository interfaces
│   │   ├── WalkRepositoryImpl.kt
│   │   ├── GpsPointRepositoryImpl.kt
│   │   ├── StreetRepositoryImpl.kt
│   │   ├── RouteSegmentRepositoryImpl.kt
│   │   ├── EditOperationRepositoryImpl.kt
│   │   └── PendingMatchJobRepositoryImpl.kt
│   ├── engine/
│   │   ├── GraphHopperEngine.kt   # Implements RoutingEngine (map matching + routing)
│   │   └── StreetCoverageEngine.kt # Computes and persists street coverage stats
│   └── StreeterDatabase.kt        # Room database definition (lists all tables)
│
├── ui/                            # Compose screens + ViewModels
│   ├── home/
│   │   ├── HomeScreen.kt          # Landing screen (start/resume recording)
│   │   └── HomeViewModel.kt
│   ├── recording/
│   │   ├── RecordingScreen.kt     # Live GPS recording with map
│   │   └── RecordingViewModel.kt
│   ├── history/
│   │   ├── HistoryScreen.kt       # List of all walks
│   │   └── HistoryViewModel.kt
│   ├── detail/
│   │   ├── WalkDetailScreen.kt    # Walk summary, coverage breakdown
│   │   └── WalkDetailViewModel.kt
│   ├── edit/
│   │   ├── RouteEditScreen.kt     # Map editor for route correction
│   │   └── RouteEditViewModel.kt
│   ├── manual/
│   │   ├── ManualCreateScreen.kt  # Draw a route manually on the map
│   │   └── ManualCreateViewModel.kt
│   ├── settings/
│   │   ├── SettingsScreen.kt
│   │   └── SettingsViewModel.kt
│   ├── privacy/
│   │   └── PrivacyDisclosureScreen.kt
│   ├── map/
│   │   └── MapLibreMapView.kt     # Wraps MapLibre in a Compose AndroidView
│   ├── navigation/
│   │   ├── NavGraph.kt            # All routes wired together in NavHost
│   │   └── Screen.kt             # Sealed class defining all screen routes
│   └── theme/
│       └── StreeterTheme.kt
│
├── di/                            # Hilt dependency injection modules
│   ├── DatabaseModule.kt          # Provides Room DB and all DAOs
│   ├── RepositoryModule.kt        # Binds repository interfaces to implementations
│   ├── EngineModule.kt            # Binds RoutingEngine → GraphHopperEngine
│   └── WorkManagerModule.kt       # Provides WorkManager with Hilt worker support
│
├── service/
│   ├── LocationService.kt         # Foreground service: GPS collection + flushing
│   └── GpsOutlierFilter.kt        # Drops implausible GPS points (speed > 50 km/h)
│
├── work/
│   └── MapMatchingWorker.kt       # WorkManager: matches GPS → streets (background)
│
├── map/
│   └── TileServerManager.kt       # NanoHTTPD: serves PMTiles to MapLibre
│
├── MainActivity.kt                # Entry point; sets up Compose and navigation
└── StreeterApp.kt                 # Application class; initializes Timber logging
```

---

## 5. Dependencies

All dependencies are declared in `app/build.gradle.kts`. Here's what each group does and why it's included.

### Jetpack Compose (UI Toolkit)
```
androidx.compose.bom           — version alignment for all Compose libraries
androidx.activity:activity-compose   — integrates Compose with Activity lifecycle
androidx.compose.material3     — Material Design 3 components (buttons, cards, etc.)
androidx.lifecycle:lifecycle-viewmodel-compose  — ViewModels in Compose functions
androidx.navigation:navigation-compose — screen navigation within Compose
```
> Compose replaces XML layouts. You write UI as Kotlin functions annotated with `@Composable`.

### Dependency Injection — Hilt
```
com.google.dagger:hilt-android           — core Hilt runtime
androidx.hilt:hilt-navigation-compose    — injects ViewModels in Compose nav
androidx.hilt:hilt-work                  — lets WorkManager workers be injected
```
> Hilt automatically creates and provides objects you annotate with `@Inject` or `@Provides`. You rarely construct objects manually.

### Database — Room
```
androidx.room:room-runtime    — Room database runtime
androidx.room:room-ktx        — Kotlin coroutine extensions (suspend functions, Flow)
```
> Room is a type-safe wrapper over SQLite. You write `@Entity` data classes and `@Dao` interfaces; Room generates the SQL.

### Map Rendering — MapLibre
```
org.maplibre.gl:android-sdk   — renders offline vector map tiles
```
> MapLibre displays the map on-screen using the tile assets served by `TileServerManager`.

### Routing & Map Matching — GraphHopper
```
com.graphhopper:graphhopper-core          — graph-based routing (finds shortest path)
com.graphhopper:graphhopper-map-matching  — snaps GPS traces to road network
```
> GraphHopper reads the OSM PBF file, builds an internal graph, and answers two kinds of questions: "What route exists between A and B?" and "Which roads does this GPS trace follow?"

### Background Work — WorkManager
```
androidx.work:work-runtime-ktx   — schedules and retries background tasks
```
> `MapMatchingWorker` runs here. WorkManager guarantees the task completes even if the user closes the app, using exponential backoff and retry logic.

### Tile Server — NanoHTTPD
```
org.nanohttpd:nanohttpd   — tiny embedded HTTP server
```
> MapLibre loads map tiles over HTTP. `TileServerManager` starts a local server on `127.0.0.1` that reads tiles from the bundled PMTiles asset and serves them to MapLibre.

### Serialization & Coroutines
```
org.jetbrains.kotlinx:kotlinx-serialization-json   — JSON parsing (GeoJSON geometry strings)
org.jetbrains.kotlinx:kotlinx-coroutines-android    — async/non-blocking code (suspend, Flow)
```

### Location
```
com.google.android.gms:play-services-location   — FusedLocationProviderClient for GPS
```
> Provides battery-efficient GPS updates while `LocationService` is running.

### Logging — Timber
```
com.jakewharton.timber:timber   — structured logging wrapper
```
> In debug builds: all logs appear in Logcat. In release builds: coordinates are stripped and only warnings/errors are logged.

### Testing
```
junit:junit                             — standard unit testing framework
androidx.test.ext:junit                 — Android JUnit extensions
androidx.compose.ui:ui-test-junit4      — Compose UI testing helpers
```

---

## 6. Data Schemas

### Domain Models (what the app thinks in)

These live in `domain/model/` and are plain Kotlin data classes — no database annotations.

#### Walk
Represents one complete recording session.

```kotlin
data class Walk(
    val id: Long,
    val title: String,
    val date: Long,           // Unix timestamp (milliseconds)
    val durationMs: Long,
    val distanceM: Float,
    val status: WalkStatus,
    val source: WalkSource,
    val createdAt: Long,
    val updatedAt: Long
)

enum class WalkStatus {
    RECORDING,        // GPS collection is active right now
    PENDING_MATCH,    // Recording stopped; waiting for map matching
    COMPLETED,        // Map matching succeeded
    MANUAL_DRAFT,     // Walk was drawn manually, not recorded
    DELETED           // Soft-deleted; hidden from UI but still in DB
}

enum class WalkSource {
    RECORDED,   // came from LocationService GPS tracking
    MANUAL      // user drew the route on the map
}
```

#### GpsPoint
One raw GPS reading captured during a walk.

```kotlin
data class GpsPoint(
    val id: Long,
    val walkId: Long,
    val lat: Double,
    val lng: Double,
    val timestamp: Long,      // Unix timestamp (milliseconds)
    val accuracyM: Float,     // GPS accuracy radius in meters
    val speedKmh: Float,
    val isFiltered: Boolean   // true if GpsOutlierFilter rejected this point
)
```

#### Street and StreetSection
OSM streets are split into sections (one per city block / node pair).

```kotlin
data class Street(
    val id: Long,
    val osmWayId: Long,         // OpenStreetMap way ID
    val name: String,
    val cityTotalLengthM: Float,
    val osmDataVersion: Int,
    val osmNameHash: Long
)

data class StreetSection(
    val id: Long,
    val streetId: Long,
    val fromNodeOsmId: Long,    // OSM node at the start of this section
    val toNodeOsmId: Long,      // OSM node at the end
    val lengthM: Float,
    val stableId: String        // MD5("streetName|fromNodeId|toNodeId"), 16 hex chars
                                // survives OSM data refreshes
)
```

#### Coverage Records
Computed by `StreetCoverageEngine` after map matching.

```kotlin
data class WalkStreetCoverage(
    val id: Long,
    val walkId: Long,
    val streetId: Long,
    val streetName: String,
    val coveragePct: Float   // 0.0 → 1.0 (fraction of street length covered)
)

data class WalkSectionCoverage(
    val id: Long,
    val walkId: Long,
    val sectionStableId: String,
    val coveredPct: Float
)
```

#### Route and Edit Records

```kotlin
data class RouteSegment(
    val id: Long,
    val walkId: Long,
    val geometryJson: String,      // GeoJSON LineString
    val matchedWayIds: List<Long>, // OSM way IDs the route follows
    val segmentOrder: Int
)

data class EditOperation(
    val id: Long,
    val walkId: Long,
    val operationOrder: Int,
    val anchor1: LatLng,           // Start of the edited segment
    val anchor2: LatLng,           // End of the edited segment
    val waypoint: LatLng,          // Point the new route must pass through
    val replacedGeometryJson: String,
    val newGeometryJson: String,
    val createdAt: Long
)
```

---

### Room Database Tables (what gets stored on disk)

The database (`StreeterDatabase`) contains nine tables. Each `*Entity` class in `data/local/entity/` maps to one table.

| Table | Maps from entity | Primary key | Foreign keys |
|-------|-----------------|-------------|--------------|
| `walks` | `WalkEntity` | `id` (auto) | — |
| `gps_points` | `GpsPointEntity` | `id` (auto) | `walkId → walks.id` (CASCADE delete) |
| `streets` | `StreetEntity` | `id` (auto) | — |
| `street_sections` | `StreetSectionEntity` | `id` (auto) | `streetId → streets.id` (CASCADE) |
| `walk_streets` | `WalkStreetCoverageEntity` | `id` (auto) | `walkId → walks.id` (CASCADE) |
| `walk_sections` | `WalkSectionCoverageEntity` | `id` (auto) | `walkId → walks.id` (CASCADE) |
| `route_segments` | `RouteSegmentEntity` | `id` (auto) | `walkId → walks.id` (CASCADE) |
| `edit_operations` | `EditOperationEntity` | `id` (auto) | `walkId → walks.id` (CASCADE) |
| `pending_match_jobs` | `PendingMatchJobEntity` | `id` (auto) | `walkId → walks.id` (CASCADE) |

**Key constraints:**
- `streets.osmWayId` — unique index (one row per OSM way)
- `street_sections.stableId` — unique index (stable across data refreshes)
- `gps_points`, `walk_streets`, `walk_sections`, `route_segments`, `edit_operations`, `pending_match_jobs` all have an index on `walkId` for fast per-walk queries

**Cascade deletes:** Deleting a walk automatically removes all its GPS points, coverage records, route segments, edit operations, and pending jobs.

---

### Mappers (entity ↔ domain)

Because Room entities and domain models are separate classes, mappers convert between them. They live in `data/local/mapper/`.

```
WalkMapper.kt
  WalkEntity  →  Walk      (toModel)
  Walk        →  WalkEntity (toEntity)
  — handles WalkStatus and WalkSource enum ↔ String conversion

GpsPointMapper.kt
  GpsPointEntity  →  GpsPoint

StreetMapper.kt
  StreetEntity         →  Street
  StreetSectionEntity  →  StreetSection
  WalkStreetWithName   →  WalkStreetCoverage   (JOIN query result)
```

> **Rule:** Only mappers know about both layers. Repositories receive domain models and return domain models — they never expose entities to the UI.

---

## 7. Key Data Flows

### Recording a Walk

```
User taps "Start Walk"
    ↓
HomeViewModel → startForegroundService(LocationService)
    ↓
LocationService requests GPS updates (every 5 seconds)
    ↓
Each GPS update → GpsOutlierFilter.shouldKeep()
    • Rejects points where implied speed > 50 km/h (Haversine check)
    ↓
Accepted points accumulate in memory
    ↓ (every 50 points)
GpsPointRepository.insertPoints() → Room gps_points table
    ↓
User taps "Stop Walk"
    ↓
walk.status = PENDING_MATCH (saved to DB)
PendingMatchJob created (status = QUEUED)
MapMatchingWorker.enqueue() via WorkManager
LocationService stops
```

### Background Map Matching

```
WorkManager picks up MapMatchingWorker
    ↓
GraphHopperEngine.initialize()
    • Copies city.osm.pbf to filesDir (first run only)
    • Builds GraphHopper routing graph (can take ~30s first time)
    ↓
routingEngine.matchTrace(gpsPoints)
    • Snaps each GPS point to nearest road
    • Returns matched geometry + OSM way IDs
    ↓
StreetCoverageEngine.computeAndPersistCoverage()
    • For each way ID: upsert Street + StreetSections to DB
    • Compute length-weighted coverage percentage per street
    • Persist WalkStreetCoverage + WalkSectionCoverage
    ↓
walk.status = COMPLETED
PendingMatchJob.status = DONE
    ↓
(On failure: retry up to 3 times with exponential backoff)
```

### Walk Status Lifecycle

```
RECORDING  →  PENDING_MATCH  →  COMPLETED
                               ↑ after successful map matching
                    ↑ after stopping LocationService
(for manually drawn routes: MANUAL_DRAFT, no matching needed)
(soft delete: any status → DELETED, row stays in DB)
```

---

## 8. Dependency Injection

Hilt eliminates manual constructor calls. You annotate what you want provided, and Hilt wires it automatically.

**DatabaseModule** — provides Room and all DAOs:
```kotlin
// Hilt creates one StreeterDatabase for the whole app
@Provides @Singleton
fun provideDatabase(@ApplicationContext ctx: Context): StreeterDatabase

// Each DAO is extracted from the database
@Provides
fun provideWalkDao(db: StreeterDatabase): WalkDao = db.walkDao()
// ... repeated for each DAO
```

**RepositoryModule** — binds interfaces to implementations:
```kotlin
// When code asks for WalkRepository, Hilt provides WalkRepositoryImpl
@Binds @Singleton
abstract fun bindWalkRepository(impl: WalkRepositoryImpl): WalkRepository
// ... repeated for each repository
```

**EngineModule** — binds the routing engine:
```kotlin
@Binds @Singleton
abstract fun bindRoutingEngine(impl: GraphHopperEngine): RoutingEngine
```

**WorkManagerModule** — enables Hilt injection inside workers:
```kotlin
@Provides @Singleton
fun provideWorkManager(@ApplicationContext ctx: Context): WorkManager
```

> **When to add to DI:** Any new `Repository`, `Engine`, or singleton service needs to be added to the appropriate module — otherwise Hilt won't know how to provide it.

---

## 9. Navigation

All screens are defined in `ui/navigation/Screen.kt` as a sealed class:

```kotlin
sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Recording : Screen("recording")
    object History : Screen("history")
    data class WalkDetail(val walkId: Long) : Screen("walk/{walkId}")
    data class RouteEdit(val walkId: Long)  : Screen("walk/{walkId}/edit")
    object ManualCreate : Screen("manual_create")
    object Settings : Screen("settings")
    object Privacy : Screen("privacy")
}
```

Screens are registered in `NavGraph.kt` using Jetpack Compose Navigation's `NavHost`. Arguments (like `walkId`) are passed as route path parameters.

**Deep links supported:**
- `streeter://walk/{walkId}` — opens WalkDetail
- `streeter://walk/{walkId}/edit` — opens RouteEdit

---

## 10. Testing

Unit tests live in `app/src/test/` and run entirely on the JVM — no Android emulator or device needed.

```bash
# Run all unit tests
./gradlew testDebugUnitTest

# Run a specific test class
./gradlew testDebugUnitTest --tests "com.streeter.RouteEditOperationTest"
```

Instrumentation tests (requiring a device or emulator) live in `app/src/androidTest/`.

**What to test:**
- Domain logic (models, coverage calculations, outlier filtering) — pure JVM, straightforward
- Repository logic — use Room's in-memory database (`Room.inMemoryDatabaseBuilder`) to avoid mocking
- ViewModel logic — use `kotlinx-coroutines-test` and fake repositories

---

## 11. Build Commands

```bash
# Debug build (installs on device/emulator)
./gradlew assembleDebug

# Release build (requires signing config)
./gradlew assembleRelease

# Run unit tests
./gradlew testDebugUnitTest

# Run specific test class
./gradlew testDebugUnitTest --tests "com.streeter.RouteEditOperationTest"

# Run all checks (lint + unit tests)
./gradlew check
```

---

## Quick Reference: Where to Find Things

| What you're looking for | Where to look |
|------------------------|---------------|
| Data model for a walk | `domain/model/Walk.kt` |
| All database tables | `StreeterDatabase.kt` + `data/local/entity/` |
| SQL queries | `data/local/dao/` |
| Business logic / validation | `domain/` or `data/engine/` |
| Screen UI code | `ui/{screen}/{Screen}Screen.kt` |
| Screen state and logic | `ui/{screen}/{Screen}ViewModel.kt` |
| GPS collection | `service/LocationService.kt` |
| Map matching background job | `work/MapMatchingWorker.kt` |
| Street coverage calculation | `data/engine/StreetCoverageEngine.kt` |
| Route finding / map matching | `data/engine/GraphHopperEngine.kt` |
| Wiring everything together | `di/*.kt` |
| All screen routes | `ui/navigation/Screen.kt` |
