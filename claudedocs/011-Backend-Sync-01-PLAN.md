# 011-Backend-Sync-01 — Android Implementation Plan
# Scope: Walk + GPS Trace sync only

**Date:** 2026-05-06  
**Status:** In progress — Phase 1 implemented, Phase 2–9 planned  
**Arch ref:** `claudedocs/011-Backend-Sync-ARCH.md`  
**Phase:** 1 (push-only, device → backend) — implemented; Phase 2 (pull sync) — planned

---

## What this plan covers

Adds a one-way push sync layer for **Walk records and GPS traces only**. Route segments, street coverage, and the GET pull endpoints are Phase 2+.

The device remains the source of truth. The backend is a durability layer. The user never waits for sync.

---

## Prerequisites

- Backend endpoints live in `inboxa.be.kt` (see `011-Backend-Sync-BE-01-PLAN.md`).
- A `streeter-android` bearer token has been provisioned in the server config.
- The backend base URL and token are supplied via `BuildConfig` fields or `local.properties` (not hardcoded).

---

## Files to create / modify

### New files

```
app/src/main/java/com/streeter/domain/model/SyncStatus.kt
app/src/main/java/com/streeter/domain/repository/RemoteSyncRepository.kt
app/src/main/java/com/streeter/data/remote/dto/WalkSyncRequest.kt
app/src/main/java/com/streeter/data/remote/dto/WalkSyncResponse.kt
app/src/main/java/com/streeter/data/remote/dto/GpsPointDto.kt
app/src/main/java/com/streeter/data/remote/dto/GpsTraceSyncRequest.kt
app/src/main/java/com/streeter/data/remote/api/StreeterApiService.kt
app/src/main/java/com/streeter/data/repository/RemoteSyncRepositoryImpl.kt
app/src/main/java/com/streeter/di/NetworkModule.kt
app/src/main/java/com/streeter/work/SyncWorker.kt
```

### Modified files

```
app/build.gradle.kts                                            — add Ktor dependencies
gradle/libs.versions.toml                                       — add ktor version
app/src/main/java/com/streeter/domain/model/Walk.kt             — add syncStatus, serverWalkId
app/src/main/java/com/streeter/data/local/entity/WalkEntity.kt  — add syncStatus, serverWalkId columns
app/src/main/java/com/streeter/data/local/dao/WalkDao.kt        — add sync query methods
app/src/main/java/com/streeter/domain/repository/WalkRepository.kt — add sync methods
app/src/main/java/com/streeter/data/repository/WalkRepositoryImpl.kt — map new fields
app/src/main/java/com/streeter/data/local/StreeterDatabase.kt   — bump version to 3, add migration
app/src/main/java/com/streeter/di/RepositoryModule.kt           — bind RemoteSyncRepository
app/src/main/java/com/streeter/work/MapMatchingWorker.kt        — enqueue SyncWorker on completion
```

---

## Phase 0 — Dependencies

**File:** `gradle/libs.versions.toml`

Add:
```toml
[versions]
ktor = "3.1.3"

[libraries]
ktor-client-android = { module = "io.ktor:ktor-client-android", version.ref = "ktor" }
ktor-client-content-negotiation = { module = "io.ktor:ktor-client-content-negotiation", version.ref = "ktor" }
ktor-serialization-kotlinx-json = { module = "io.ktor:ktor-serialization-kotlinx-json", version.ref = "ktor" }
ktor-client-logging = { module = "io.ktor:ktor-client-logging", version.ref = "ktor" }
```

**File:** `app/build.gradle.kts`

Add under `dependencies`:
```kotlin
implementation(libs.ktor.client.android)
implementation(libs.ktor.client.content.negotiation)
implementation(libs.ktor.serialization.kotlinx.json)
implementation(libs.ktor.client.logging)
```

Also add `BuildConfig` fields for server config:
```kotlin
android {
    defaultConfig {
        buildConfigField("String", "STREETER_BASE_URL", "\"${project.findProperty("STREETER_BASE_URL") ?: "http://10.0.2.2:8080"}\"")
        buildConfigField("String", "STREETER_API_TOKEN", "\"${project.findProperty("STREETER_API_TOKEN") ?: ""}\"")
    }
}
```

Store real values in `local.properties` (gitignored):
```
STREETER_BASE_URL=https://your-server.example.com
STREETER_API_TOKEN=your-streeter-android-token
```

---

## Phase 1 — Domain model additions

### 1.1 SyncStatus enum

**New file:** `domain/model/SyncStatus.kt`

```kotlin
enum class SyncStatus {
    PENDING_SYNC,
    SYNCED,
    SYNC_FAILED,
}
```

### 1.2 Walk domain model

**Modify:** `domain/model/Walk.kt`

Add two fields with defaults so all existing call sites compile without changes:
```kotlin
data class Walk(
    val id: Long,
    val title: String?,
    val date: Long,
    val durationMs: Long,
    val distanceM: Double,
    val status: WalkStatus,
    val source: WalkSource,
    val createdAt: Long,
    val updatedAt: Long,
    // — new fields —
    val syncStatus: SyncStatus = SyncStatus.PENDING_SYNC,
    val serverWalkId: Long? = null,
)
```

### 1.3 RemoteSyncRepository interface

**New file:** `domain/repository/RemoteSyncRepository.kt`

```kotlin
interface RemoteSyncRepository {
    suspend fun syncWalk(walkId: Long): Result<Unit>
}
```

---

## Phase 2 — Database changes

### 2.1 WalkEntity

**Modify:** `data/local/entity/WalkEntity.kt`

Add two nullable columns:
```kotlin
val syncStatus: String = SyncStatus.PENDING_SYNC.name,
val serverWalkId: Long? = null,
```

### 2.2 Room migration

**Modify:** `data/local/StreeterDatabase.kt`

Bump `version = 3`. Add migration:
```kotlin
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE walks ADD COLUMN syncStatus TEXT NOT NULL DEFAULT 'PENDING_SYNC'")
        db.execSQL("ALTER TABLE walks ADD COLUMN serverWalkId INTEGER")
    }
}
```

Register it in the `Room.databaseBuilder(...)` call alongside `MIGRATION_1_2`.

### 2.3 WalkDao additions

**Modify:** `data/local/dao/WalkDao.kt`

Add:
```kotlin
@Query("SELECT * FROM walks WHERE syncStatus = 'PENDING_SYNC' AND status = 'COMPLETED'")
suspend fun getPendingSync(): List<WalkEntity>

@Query("UPDATE walks SET syncStatus = :syncStatus, serverWalkId = :serverWalkId WHERE id = :id")
suspend fun updateSyncStatus(id: Long, syncStatus: String, serverWalkId: Long?)
```

### 2.4 WalkRepository interface additions

**Modify:** `domain/repository/WalkRepository.kt`

Add:
```kotlin
suspend fun getWalksPendingSync(): List<Walk>
suspend fun updateSyncStatus(id: Long, syncStatus: SyncStatus, serverWalkId: Long?)
```

### 2.5 WalkRepositoryImpl

**Modify:** `data/repository/WalkRepositoryImpl.kt`

- In the `WalkEntity → Walk` mapping function, map `syncStatus` and `serverWalkId`.
- Implement `getWalksPendingSync()` delegating to `walkDao.getPendingSync()`.
- Implement `updateSyncStatus()` delegating to `walkDao.updateSyncStatus()`.

---

## Phase 3 — Networking infrastructure

### 3.1 Remote DTOs

**New file:** `data/remote/dto/WalkSyncRequest.kt`

```kotlin
@Serializable
data class WalkSyncRequest(
    val clientId: String,
    val localWalkId: Long,
    val title: String?,
    val date: Long,
    val durationMs: Long,
    val distanceM: Double,
    val status: String,
    val source: String,
    val createdAt: Long,
    val updatedAt: Long,
)
```

**New file:** `data/remote/dto/WalkSyncResponse.kt`

```kotlin
@Serializable
data class WalkSyncResponse(
    val serverWalkId: Long,
)
```

**New file:** `data/remote/dto/GpsPointDto.kt`

```kotlin
@Serializable
data class GpsPointDto(
    val lat: Double,
    val lng: Double,
    val timestamp: Long,
    val accuracyM: Float,
    val speedKmh: Float,
    val isFiltered: Boolean,
)
```

**New file:** `data/remote/dto/GpsTraceSyncRequest.kt`

```kotlin
@Serializable
data class GpsTraceSyncRequest(
    val points: List<GpsPointDto>,
)
```

### 3.2 StreeterApiService

**New file:** `data/remote/api/StreeterApiService.kt`

```kotlin
class StreeterApiService(private val client: HttpClient, private val baseUrl: String) {

    suspend fun syncWalk(request: WalkSyncRequest): WalkSyncResponse =
        client.post("$baseUrl/api/streeter/walks") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    suspend fun syncGpsTrace(serverWalkId: Long, request: GpsTraceSyncRequest) {
        client.post("$baseUrl/api/streeter/walks/$serverWalkId/gps-trace") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }
}
```

### 3.3 NetworkModule

**New file:** `di/NetworkModule.kt`

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideHttpClient(): HttpClient = HttpClient(Android) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(Logging) {
            level = if (BuildConfig.DEBUG) LogLevel.BODY else LogLevel.NONE
        }
    }

    @Provides
    @Singleton
    fun provideStreeterApiService(client: HttpClient): StreeterApiService =
        StreeterApiService(client, BuildConfig.STREETER_BASE_URL)
}
```

---

## Phase 4 — RemoteSyncRepositoryImpl

**New file:** `data/repository/RemoteSyncRepositoryImpl.kt`

```kotlin
@Singleton
class RemoteSyncRepositoryImpl @Inject constructor(
    private val apiService: StreeterApiService,
    private val walkRepository: WalkRepository,
    private val gpsPointRepository: GpsPointRepository,
    @ApplicationContext private val context: Context,
) : RemoteSyncRepository {

    override suspend fun syncWalk(walkId: Long): Result<Unit> = runCatching {
        val walk = walkRepository.getWalkById(walkId)
            ?: error("Walk $walkId not found")

        val clientId = getOrCreateClientId(context)

        // Step 1: sync walk record
        val response = apiService.syncWalk(walk.toSyncRequest(clientId))
        val serverWalkId = response.serverWalkId

        // Step 2: sync GPS trace
        val points = gpsPointRepository.getPointsForWalk(walkId)
        apiService.syncGpsTrace(serverWalkId, GpsTraceSyncRequest(points.map { it.toDto() }))

        // Step 3: persist server ID
        walkRepository.updateSyncStatus(walkId, SyncStatus.SYNCED, serverWalkId)
    }

    private fun getOrCreateClientId(context: Context): String {
        val prefs = context.getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
        return prefs.getString("client_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("client_id", it).apply()
        }
    }
}
```

> **Note:** `Walk.toSyncRequest()` and `GpsPoint.toDto()` are private extension functions in this file.

**Bind in RepositoryModule:**

**Modify:** `di/RepositoryModule.kt`

Add:
```kotlin
@Binds
@Singleton
abstract fun bindRemoteSyncRepository(impl: RemoteSyncRepositoryImpl): RemoteSyncRepository
```

---

## Phase 5 — SyncWorker

**New file:** `work/SyncWorker.kt`

```kotlin
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val remoteSyncRepository: RemoteSyncRepository,
    private val walkRepository: WalkRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val walkId = inputData.getLong(KEY_WALK_ID, -1L)
        if (walkId == -1L) return Result.failure()

        return remoteSyncRepository.syncWalk(walkId).fold(
            onSuccess = { Result.success() },
            onFailure = { throwable ->
                Timber.w(throwable, "Sync failed for walk $walkId, attempt $runAttemptCount")
                walkRepository.updateSyncStatus(walkId, SyncStatus.SYNC_FAILED, null)
                if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
            },
        )
    }

    companion object {
        const val KEY_WALK_ID = "walk_id"
        private const val MAX_RETRIES = 3

        fun buildRequest(walkId: Long): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(workDataOf(KEY_WALK_ID to walkId))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("sync_walk_$walkId")
                .build()
    }
}
```

---

## Phase 6 — MapMatchingWorker integration

**Modify:** `work/MapMatchingWorker.kt`

After the walk transitions to `COMPLETED` (inside `completeWalk()` or just after), set sync status and enqueue SyncWorker:

```kotlin
// After walk reaches COMPLETED:
walkRepository.updateSyncStatus(walkId, SyncStatus.PENDING_SYNC, null)
workManager.enqueue(SyncWorker.buildRequest(walkId))
```

`WorkManager` must be injected into `MapMatchingWorker` — add it to the constructor (it's already provided by `WorkManagerModule`).

---

## Phase 7 — Pull sync infrastructure

This phase adds the data layer and networking needed for pulling walks from the backend. No UI is introduced. The pull runs in the background transparently.

### 7.1 New DTO — WalkSyncDto (GET response item)

**New file:** `data/remote/dto/WalkSyncDto.kt`

```kotlin
@Serializable
data class WalkSyncDto(
    val serverWalkId: Long,
    val localWalkId: Long,
    val clientId: String,
    val title: String?,
    val date: Long,
    val durationMs: Long,
    val distanceM: Double,
    val status: String,
    val source: String,
    val createdAt: Long,
    val updatedAt: Long,
    val serverUpdatedAt: Long,   // epoch ms — used for conflict resolution
)
```

`serverUpdatedAt` is the server-side `updated_at` expressed as epoch milliseconds. The client compares this against its local `updatedAt` to determine whether to apply the server's version.

### 7.2 StreeterApiService — add getWalks

**Modify:** `data/remote/api/StreeterApiService.kt`

Add method:
```kotlin
suspend fun getWalks(since: Long, limit: Int, offset: Int): List<WalkSyncDto> =
    client.get("$baseUrl/api/streeter/walks") {
        parameter("clientId", clientId)   // clientId must be injected or passed in
        parameter("since", since)
        parameter("limit", limit)
        parameter("offset", offset)
    }.body()
```

> **Design note:** `clientId` must be resolved before calling this method. Pass it as a parameter from `RemoteSyncRepositoryImpl` rather than storing it in the service itself to keep the service stateless.

Revised signature to make `clientId` explicit:
```kotlin
suspend fun getWalks(clientId: String, since: Long, limit: Int, offset: Int): List<WalkSyncDto> =
    client.get("$baseUrl/api/streeter/walks") {
        parameter("clientId", clientId)
        parameter("since", since)
        parameter("limit", limit)
        parameter("offset", offset)
    }.body()
```

### 7.3 WalkEntity — add lastPullSyncAt column

**Modify:** `data/local/entity/WalkEntity.kt`

Add:
```kotlin
val lastPullSyncAt: Long? = null,   // epoch ms of the last successful pull from backend
```

### 7.4 Room migration 3→4

**Modify:** `data/local/StreeterDatabase.kt`

Bump `version = 4`. Add migration:
```kotlin
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE walks ADD COLUMN lastPullSyncAt INTEGER")
    }
}
```

Register it in the `Room.databaseBuilder(...)` call alongside `MIGRATION_1_2` and `MIGRATION_2_3`.

### 7.5 WalkDao — add pull query methods

**Modify:** `data/local/dao/WalkDao.kt`

Add:
```kotlin
@Query("SELECT * FROM walks WHERE serverWalkId = :serverWalkId LIMIT 1")
suspend fun getWalkByServerWalkId(serverWalkId: Long): WalkEntity?

@Query("SELECT MAX(lastPullSyncAt) FROM walks")
suspend fun getLastPullSyncAt(): Long?

@Query("UPDATE walks SET lastPullSyncAt = :timestamp WHERE id = :id")
suspend fun updateLastPullSyncAt(id: Long, timestamp: Long)
```

`getLastPullSyncAt()` returns the latest cursor stored across all walk rows. Using `MAX` is safe because `lastPullSyncAt` is only written after a successful pull and is monotonically increasing.

### 7.6 WalkRepository interface additions

**Modify:** `domain/repository/WalkRepository.kt`

Add:
```kotlin
suspend fun getWalkByServerWalkId(serverWalkId: Long): Walk?
suspend fun getLastPullSyncAt(): Long?
suspend fun upsertFromRemote(dto: WalkSyncDto)
suspend fun updateLastPullSyncAt(id: Long, timestamp: Long)
```

### 7.7 WalkRepositoryImpl

**Modify:** `data/repository/WalkRepositoryImpl.kt`

Implement the four new methods:

- `getWalkByServerWalkId()` — delegates to `walkDao.getWalkByServerWalkId()` and maps to domain.
- `getLastPullSyncAt()` — delegates to `walkDao.getLastPullSyncAt()`.
- `upsertFromRemote(dto)` — see logic below.
- `updateLastPullSyncAt()` — delegates to `walkDao.updateLastPullSyncAt()`.

`upsertFromRemote` logic:
```kotlin
suspend fun upsertFromRemote(dto: WalkSyncDto) {
    val existing = walkDao.getWalkByServerWalkId(dto.serverWalkId)
    if (existing == null) {
        // Walk arrived from another device — insert it
        walkDao.insert(dto.toNewEntity())
    } else if (dto.serverUpdatedAt > existing.updatedAt) {
        // Server has a newer version — overwrite editable fields
        walkDao.update(existing.copy(
            title      = dto.title,
            durationMs = dto.durationMs,
            distanceM  = dto.distanceM,
            status     = dto.status,
            updatedAt  = dto.updatedAt,
            syncStatus = SyncStatus.SYNCED.name,
        ))
    }
    // else: local copy is at least as new — no action
}
```

### 7.8 RemoteSyncRepository interface — add pullWalks

**Modify:** `domain/repository/RemoteSyncRepository.kt`

Add:
```kotlin
suspend fun pullWalks(since: Long): Result<Unit>
```

### 7.9 RemoteSyncRepositoryImpl — implement pullWalks

**Modify:** `data/repository/RemoteSyncRepositoryImpl.kt`

Add:
```kotlin
override suspend fun pullWalks(since: Long): Result<Unit> = runCatching {
    val clientId = getOrCreateClientId(context)
    val pageSize = 100
    var offset = 0
    var lastSyncedAt = since

    while (true) {
        val page = apiService.getWalks(clientId, since, pageSize, offset)
        if (page.isEmpty()) break

        page.forEach { dto ->
            walkRepository.upsertFromRemote(dto)
            if (dto.updatedAt > lastSyncedAt) lastSyncedAt = dto.updatedAt
        }

        offset += page.size
        if (page.size < pageSize) break
    }

    // Persist the cursor so the next pull only fetches newer records.
    // We write it to every walk that was touched in this pull rather than a
    // separate preferences entry, so the cursor survives DB wipes together
    // with the data it represents.
    if (lastSyncedAt > since) {
        // Use a dedicated SharedPreferences key for the pull cursor — simpler
        // than scattering lastPullSyncAt across rows.
        val prefs = context.getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
        prefs.edit().putLong("last_pull_sync_at", lastSyncedAt).apply()
    }
}
```

> **Cursor storage:** The `lastPullSyncAt` column on `WalkEntity` (Phase 7.3) is used by `PullSyncWorker` to read the starting cursor via `WalkRepository.getLastPullSyncAt()`. Alternatively, a `SharedPreferences` key `last_pull_sync_at` is written here as a fallback. Prefer the `SharedPreferences` path — it survives independently of whether any local walk rows exist.

---

## Phase 8 — Pull triggers

### 8.1 PullSyncWorker

**New file:** `work/PullSyncWorker.kt`

```kotlin
@HiltWorker
class PullSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val remoteSyncRepository: RemoteSyncRepository,
    @ApplicationContext private val appContext: Context,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = appContext.getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
        val since = prefs.getLong("last_pull_sync_at", 0L)

        return remoteSyncRepository.pullWalks(since).fold(
            onSuccess = { Result.success() },
            onFailure = { throwable ->
                Timber.w(throwable, "Pull sync failed, attempt $runAttemptCount")
                if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
            },
        )
    }

    companion object {
        private const val MAX_RETRIES = 3
        const val UNIQUE_WORK_NAME = "pull_sync"

        fun buildOneTimeRequest(): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<PullSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

        fun buildPeriodicRequest(): PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<PullSyncWorker>(24, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
    }
}
```

### 8.2 Trigger 1 — after push succeeds

**Modify:** `work/SyncWorker.kt`

On `Result.success()`, enqueue a one-time `PullSyncWorker`:
```kotlin
onSuccess = {
    workManager.enqueueUniqueWork(
        PullSyncWorker.UNIQUE_WORK_NAME,
        ExistingWorkPolicy.KEEP,   // don't re-enqueue if one is already pending
        PullSyncWorker.buildOneTimeRequest(),
    )
    Result.success()
},
```

`WorkManager` must be added to `SyncWorker`'s constructor (inject via `@HiltWorker`).

### 8.3 Trigger 2 — app foreground

**New file:** `app/src/main/java/com/streeter/lifecycle/AppForegroundObserver.kt`

```kotlin
@Singleton
class AppForegroundObserver @Inject constructor(
    private val workManager: WorkManager,
) : DefaultLifecycleObserver {

    override fun onStart(owner: LifecycleOwner) {
        workManager.enqueueUniqueWork(
            PullSyncWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,   // no-op if RUNNING or ENQUEUED
            PullSyncWorker.buildOneTimeRequest(),
        )
    }
}
```

**Modify:** `MainActivity.kt` (or `Application` subclass)

Register the observer in `onCreate`:
```kotlin
ProcessLifecycleOwner.get().lifecycle.addObserver(appForegroundObserver)
```

`appForegroundObserver` must be injected via Hilt into `MainActivity` or the `Application` class.

### 8.4 Periodic pull

**Modify:** `Application` subclass (or wherever `WorkManager` is initialised at startup)

Enqueue the periodic worker once on app start — `WorkManager` deduplicates by name:
```kotlin
workManager.enqueueUniquePeriodicWork(
    "pull_sync_periodic",
    ExistingPeriodicWorkPolicy.KEEP,
    PullSyncWorker.buildPeriodicRequest(),
)
```

---

## Phase 9 — Conflict resolution for route changes

This phase is a **forward-looking placeholder** for when route geometry is included in pull responses (Phase 3 of the overall sync roadmap). No implementation is required now, but the design is fixed here to guide Phase 3.

### 9.1 What counts as changed route geometry

In Phase 1 the backend does not store route segments. When Phase 3 adds route-segment storage and pull, the following fields on the pulled `WalkSyncDto` will indicate a route change relative to the local record:

- `distanceM` differs by more than 10 m (floating-point tolerance guard)
- A future `routePolylineHash` field differs (to be added in Phase 3 DTO)

### 9.2 Re-enqueue MapMatchingWorker on route change

When `upsertFromRemote` applies a server version that changes route geometry, the local `WalkStreetCoverage` records are stale and must be recomputed. The device is the only place that can run GraphHopper + `StreetCoverageEngine` — the backend cannot recompute coverage.

The detection and re-enqueue logic belongs in `WalkRepositoryImpl.upsertFromRemote()`:

```kotlin
// Inside upsertFromRemote, after determining the server version is newer:
val routeChanged = dto.distanceM !approximatelyEquals existing.distanceM
if (routeChanged && existing.status == WalkStatus.COMPLETED.name) {
    walkDao.update(existing.copy(
        status     = WalkStatus.PENDING_MATCH.name,
        syncStatus = SyncStatus.PENDING_SYNC.name,
    ))
    workManager.enqueue(MapMatchingWorker.buildRequest(existing.id!!))
} else {
    // normal upsert without re-matching
    walkDao.update(existing.copy( /* editable fields */ ))
}
```

> **Note:** `workManager` is not currently available in `WalkRepositoryImpl`. When Phase 9 is implemented, inject `WorkManager` into the repository or move this orchestration into `RemoteSyncRepositoryImpl` which already has `Context`.

### 9.3 Coverage invalidation

When a walk is re-set to `PENDING_MATCH`:

1. Delete existing `WalkStreetCoverage` and `WalkSectionCoverage` rows for that `walkId`.
2. `MapMatchingWorker` runs map-matching and calls `StreetCoverageEngine.computeAndPersistCoverage()` to rebuild them.

This deletion should happen at the start of `MapMatchingWorker.doWork()` before computing the new route — it already does this for the normal completion path; confirm it also runs on re-triggered executions.

---

## Sync trigger summary

| Trigger | Mechanism |
|---|---|
| Walk completes map-matching | `MapMatchingWorker` sets `PENDING_SYNC`, enqueues `SyncWorker` |
| Push succeeds | `SyncWorker.onSuccess` enqueues `PullSyncWorker` (KEEP policy) |
| App comes to foreground | `AppForegroundObserver.onStart` enqueues `PullSyncWorker` (KEEP policy) |
| Periodic background | `PullSyncWorker` periodic every 24 h, network required |
| App in background, network restored | WorkManager constraint satisfied, pending work resumes automatically |
| Manual retry (future) | Query `getWalksPendingSync()`, enqueue `SyncWorker` for each |

---

## Walk status × sync status matrix

| `WalkStatus` | `SyncStatus` | Meaning |
|---|---|---|
| RECORDING | PENDING_SYNC | Walk in progress — never synced, never enqueued |
| PENDING_MATCH | PENDING_SYNC | Map-matching queued — sync not yet triggered |
| COMPLETED | PENDING_SYNC | Ready to sync — SyncWorker will be enqueued |
| COMPLETED | SYNCED | Backend has this walk |
| COMPLETED | SYNC_FAILED | All retries exhausted — manual retry needed |
| MANUAL_DRAFT | PENDING_SYNC | Manual walks are not synced in Phase 1 |

> `SyncWorker` only runs when `WalkStatus == COMPLETED`. The worker checks this before making any network call.

---

## What is NOT in scope

- Route segments sync (`POST /api/streeter/walks/{id}/route-segments`)
- Street coverage sync (`POST /api/streeter/walks/{id}/street-coverage`)
- Aggregate street coverage pull (`GET /api/streeter/coverage/streets`)
- UI changes (no sync progress indicators)
- Manual draft walk sync
- Retry UI or manual flush from settings
- Route geometry comparison in pull (Phase 9 placeholder — requires Phase 3 DTO changes)

---

## Validation checklist

### Phase 1 — Push sync

- [ ] `./gradlew assembleDebug` — builds clean
- [ ] `./gradlew testDebugUnitTest` — no regressions in existing tests
- [ ] Room migration 2→3: install build over existing DB, verify walks table has `syncStatus` + `serverWalkId` columns
- [ ] `SyncWorker` runs on emulator with backend reachable: walk reaches `SYNCED` status after `COMPLETED`
- [ ] `SyncWorker` retries on network failure (disconnect emulator, complete walk, reconnect — verify eventual sync)
- [ ] `serverWalkId` stored in DB after successful sync
- [ ] Walk in `RECORDING` or `PENDING_MATCH` state is never enqueued for sync

### Phase 7 — Pull infrastructure

- [ ] Room migration 3→4: verify `lastPullSyncAt` column added to walks table
- [ ] `getWalkByServerWalkId()` returns the correct entity when `serverWalkId` matches
- [ ] `pullWalks(since=0)` fetches all walks for the client and upserts them locally
- [ ] `pullWalks` with a non-zero cursor fetches only walks updated after that timestamp
- [ ] Server-newer walk overwrites local editable fields (title, durationMs, distanceM, status)
- [ ] Local-newer walk is not overwritten (local `updatedAt` > `serverUpdatedAt`)
- [ ] New walk from another device (no local `serverWalkId` match) is inserted as a new local row
- [ ] Pull cursor advances correctly after each successful pull call
- [ ] Pagination: mock backend returning exactly 100 walks causes a second page request; 99 walks does not

### Phase 8 — Pull triggers

- [ ] After a push completes, a `PullSyncWorker` is enqueued with KEEP policy
- [ ] `ExistingWorkPolicy.KEEP` prevents duplicate `PullSyncWorker` enqueueing when multiple pushes complete rapidly
- [ ] App foregrounding enqueues `PullSyncWorker` — verify via `WorkManager.getWorkInfosForUniqueWork`
- [ ] Periodic pull worker registered at app start with `ExistingPeriodicWorkPolicy.KEEP` (no duplicate on restart)
- [ ] Pull worker respects `CONNECTED` network constraint — does not run on airplane mode
