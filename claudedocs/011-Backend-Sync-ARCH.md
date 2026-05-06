# 011 — Backend Sync Architecture

**Date:** 2026-05-05  
**Status:** Proposal  
**Scope:** Walk data, street coverage, GPS traces — device ↔ backend sync

---

## Context

The app currently runs fully on-device: GPS recording, map matching (GraphHopper), and street coverage computation (`StreetCoverageEngine`) all happen locally with no backend. This proposal covers what a sync-capable backend would look like and which responsibilities should move server-side.

---

## Decision Summary

**Do not move heavy engines (GraphHopper, StreetCoverageEngine) to the backend.** Add a push-sync layer on top of the existing on-device pipeline. The backend acts as a durability and future multi-device coordination layer — not a processing authority.

---

## What Stays On-Device (Always)

| Component | Reason |
|---|---|
| GPS collection + `GpsOutlierFilter` | Real-time, must be offline |
| `GraphHopperEngine.route()` | Interactive, latency-sensitive, offline |
| `GraphHopperEngine.matchTrace()` | Offline-first; backend path is additive later |
| `StreetCoverageEngine` | Tightly coupled to the local graph index |
| PMTiles tile serving | Already local, no benefit in moving |

The coverage engine cannot move without also moving the entire GraphHopper graph — that is the key constraint. `StreetCoverageEngine` calls `getStreetName()`, `getEdgeLength()`, `getStreetTotalLength()`, and `findNearestNamedStreet()` against the local graph on every matched way ID.

---

## What Gets Added to Android

A **`SyncWorker`** (WorkManager, requires network) enqueued after `MapMatchingWorker` completes. It pushes four payloads in dependency order:

1. Walk record
2. GPS trace (compressed blob) + route segments (parallel)
3. Street coverage results

### New fields on `WalkEntity`

```
syncStatus: String       // PENDING_SYNC | SYNCED | SYNC_FAILED
serverWalkId: Long?      // backend-assigned ID, stored for reference
```

### New domain interface

```kotlin
// domain/repository/RemoteSyncRepository.kt
interface RemoteSyncRepository {
    suspend fun syncWalk(walkId: Long): Result<Unit>
}
```

`SyncWorker` calls `syncWalk(walkId)`, which orchestrates the four POST calls. A thin `NetworkModule` provides the Ktor `HttpClient` and the `RemoteSyncRepository` binding. No UI changes required in Phase 1.

---

## Backend Technology Stack

The backend is implemented in the **inboxa.be.kt** project. The table below reflects the actual stack in that codebase.

| Concern | Choice | Notes |
|---|---|---|
| Framework | Spring Boot 3.5.11 (Kotlin 2.3.10, Java 21) | Not Ktor. `@SpringBootApplication`, Tomcat embedded server |
| Database | PostgreSQL (port 5433) | Schemas: `inboxa` (existing) + new `streeter` schema to be created |
| ORM | Spring Data JPA + Hibernate | `@Entity` / `@Table(schema = "streeter")`, `@GeneratedValue(IDENTITY)`, `JpaRepository<T, Long>` |
| Schema migrations | Flyway | Versioned SQL files at `src/main/resources/db/migration/`; existing migrations are V2, V3 — Streeter migrations start at V4 |
| Auth | Custom static-token filter (`ApiTokenAuthFilter`) | `Authorization: Bearer <token>`; tokens configured via `inboxa.auth.tokens.*` properties. No Firebase JWT — the filter resolves a named principal from a pre-shared token map |
| Content negotiation | Jackson (Kotlin module) | ISO-8601 timestamps, UTC; `application/json`; `non_null` serialization in production |
| API documentation | SpringDoc OpenAPI 2.8.16 | Swagger UI at `/swagger-ui.html`; docs at `/api-docs`; controllers annotated with `@Tag`, `@Operation`, `@ApiResponse` |
| Hosting | Docker container (GHCR image), self-hosted or cloud VM | `eclipse-temurin:21-jre-alpine` runtime; port 8080; health check via `/actuator/health` |
| Connection pool | HikariCP (prod: max 20, min-idle 5; dev: max 5, min-idle 2) | Pool name distinguishable per profile |
| Code style | ktlint 1.7.1 (Gradle plugin 14.1.0) | Enforced in CI via `./gradlew ktlintCheck` |

---

## Sync Model

### Direction (Phase 1)

**One-way: device → backend.** The device is the source of truth for all data originating on that device. The backend is a durability layer. The device never waits for the backend before allowing the user to proceed.

### Sync triggers

- Walk reaches `COMPLETED` status → enqueue `SyncWorker`
- App comes to foreground after being offline → flush pending sync queue
- Periodic background sync via WorkManager (every 24h, network required)

### Atomicity

A walk is not considered `SYNCED` until all four sub-records have been acknowledged. If any step fails, `syncStatus` stays `SYNC_FAILED` and the whole walk retries on the next trigger.

### Idempotency

The device generates a `clientId` (UUID at install, stored in `SharedPreferences`). All sync requests include it. The backend uses `(clientId, localWalkId)` as a deduplication key. The backend returns `serverWalkId`; the device stores it but does not use it as a local primary key.

### No sync during recording

A walk in `RECORDING` state is never synced. Partial GPS traces mid-walk have no backend value and would add battery pressure during the one moment the foreground service is already consuming resources.

---

## API Shape

All endpoints require `Authorization: Bearer <token>` (validated by `ApiTokenAuthFilter`). A dedicated named token — e.g. `inboxa.auth.tokens.streeter-android` — should be provisioned for the Android client and kept in the server's environment/properties.

All routes live under the `/api/` prefix, matching the existing convention in inboxa.be.kt (e.g. `/api/dribs`, `/api/investments/transactions`). There is no `/v1/` version segment in the current codebase; Streeter endpoints follow the same unversioned `/api/streeter/` sub-namespace.

Content type for all requests and responses: `application/json`.

```
POST /api/streeter/walks
Body:     { clientId, localWalkId, title, date, durationMs, distanceM, status, source, createdAt, updatedAt }
Response: { serverWalkId }
Note:     Idempotent on (clientId, localWalkId). Create or update (upsert by unique constraint).

POST /api/streeter/walks/{serverWalkId}/gps-trace
Body:     { points: [{ lat, lng, timestamp, accuracyM, speedKmh, isFiltered }], compressed: true }
Response: { accepted: true }
Note:     Replace semantics. Store as compressed blob, not individual rows.

POST /api/streeter/walks/{serverWalkId}/route-segments
Body:     [{ segmentOrder, geometryJson, matchedWayIds }]
Response: { accepted: true }
Note:     Replace semantics.

POST /api/streeter/walks/{serverWalkId}/street-coverage
Body:     { streets: [{ streetName, coveragePct, walkedLengthM, sections: [{ stableId, coveredPct }] }] }
Response: { accepted: true }
Note:     Key sync payload for future multi-device aggregate queries.

GET /api/streeter/walks
Params:   since (epoch ms), limit, offset
Response: [{ serverWalkId, localWalkId, clientId, ...walk fields }]
Note:     Phase 2 — pull for secondary device or web view.

GET /api/streeter/coverage/streets
Params:   minCoverage (0.0–1.0)
Response: [{ streetName, aggregateCoveredPct, lastWalkedAt }]
Note:     Future endpoint for aggregate cross-device street coverage view.
```

Error responses follow the existing `ErrorResponseDto` shape used across all inboxa.be.kt controllers:

```json
{
  "timestamp": "2026-05-05T10:00:00.000Z",
  "status": 404,
  "error": "Not Found",
  "message": "Walk not found with serverWalkId: 42",
  "path": "/api/streeter/walks/42/gps-trace",
  "requestId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
}
```

---

## Conflict Resolution

### Phase 1 (one-way push)

No conflict to resolve. Device wins unconditionally.

### Phase 2+ (if server-side editing is added)

If routes or walk metadata can be edited on a web UI and need to flow back to the device, the architecture must become **bidirectional**, which is a meaningfully harder problem.

Three strategies and their trade-offs:

| Strategy | How it works | Trade-off |
|---|---|---|
| Last-write-wins | Newer `updatedAt` timestamp wins | Simple, but silent data loss on concurrent edits |
| Server wins | Server changes always overwrite device on pull | Easy to implement; surprising if device edits get silently dropped |
| Three-way merge | Diff both sides against a common ancestor | Correct, but complex (essentially what Git does) |

**Recommended if bidirectional sync is needed:** server wins on pull, with the pull step running at app foreground. This is pragmatic: if a user deliberately edited a route on the web, they want it on their device.

#### What bidirectional sync adds

1. A `serverUpdatedAt` timestamp in `GET /api/streeter/walks` responses
2. A pull sync step on app foreground/open
3. Logic in `WalkRepository` to compare `local.updatedAt` vs `server.serverUpdatedAt` and overwrite if server is newer
4. The device can no longer treat itself as unconditional source of truth for remotely editable fields

#### Complication specific to Streeter

A route edit is not just a metadata change — it changes `RouteSegment` geometry, `matchedWayIds`, and therefore all `WalkStreetCoverage` records. A server-side route change must either:
- Invalidate coverage on the server and trigger the device to re-run `StreetCoverageEngine` locally after pulling the new route, or
- Be treated as a full walk replacement (delete + re-push) rather than a partial update.

---

## Multi-Device Conflict Resolution (Coverage)

The `sectionStableId` (MD5 of `streetName|fromNodeId|toNodeId`, truncated to 16 hex chars) is the natural cross-device merge key. Coverage records from the same `stableId` across devices are **additive** — a section is covered if any device has walked it. Walk records are not merged across devices; each device's walks remain independent.

---

## Known Limitations

1. **Backend cannot recompute coverage.** The backend stores coverage results computed on-device but cannot recompute them without the same GraphHopper graph. An OSM data refresh requires re-triggering on-device reprocessing — the backend has no path to do this independently.

2. **GPS trace size at high frequency.** At 20-second intervals, a 1-hour walk produces ~9KB compressed — negligible. At 1-second intervals, this scales to ~180KB per walk. The blob storage approach accommodates this, but upload time becomes a concern at high frequency.

3. **Phase 1 is push-only.** Changes made on the server do not flow back to the device without implementing Phase 2 bidirectional sync.

---

## inboxa.be.kt Integration Notes

### Where new code should live

Follow the existing domain-grouping convention. New files belong in a `streeter` sub-namespace mirroring how `investments` is organised:

| Type | Location |
|---|---|
| JPA entities | `ru.levar.inboxa.be.domain.streeter` |
| Spring Data repositories | `ru.levar.inboxa.be.repositories.streeter` |
| Service classes | `ru.levar.inboxa.be.services.streeter` |
| REST controllers | `ru.levar.inboxa.be.controllers.streeter` |
| DTOs (request/response) | `ru.levar.inboxa.be.dto.streeter` |
| Database migrations | `src/main/resources/db/migration/V4__create_streeter_schema.sql` (next available version after V3) |

### Auth middleware

The new controllers must authenticate through the existing `ApiTokenAuthFilter`. No code changes to `SecurityConfig` are required — the filter already intercepts all `/api/**` paths. What is needed:

- Add a named token entry to the server environment: `inboxa.auth.tokens.streeter-android=<secret>`
- Ensure `inboxa.auth.enabled=true` in the production properties profile (it is currently `false` in `application-prod.properties` — confirm and align with the ops config before deploying)
- The Android `SyncWorker` sends `Authorization: Bearer <secret>` on every request

### JPA entity and table conventions

Follow the pattern established in `domain/investments/`:

- Entities are Kotlin `data class` annotated `@Entity` + `@Table(name = "...", schema = "streeter")`
- Primary key: `val id: Long? = null` with `@Id @GeneratedValue(strategy = GenerationType.IDENTITY)`
- Foreign key columns are declared twice: once as `val fooId: Long` (the bare column) and once as `@ManyToOne(fetch = LAZY) @JoinColumn(insertable = false, updatable = false) val foo: Foo?` (the association)
- Timestamps use `java.time.Instant` (see `Drib.regDate`) for UTC epoch values, or `java.time.LocalDate` for date-only fields (see `Transaction.transactionDate`)
- Index declarations belong on the `@Table` annotation as `indexes = [Index(...)]`
- Database schema `streeter` must be created in the Flyway migration and added to `spring.flyway.schemas` in both `application-dev.properties` and `application-prod.properties` (currently `inboxa,investments`)

### Exception handling

Throw `ResourceNotFoundException` (from `ru.levar.inboxa.be.exceptions`) for 404 cases and `ValidationException` for 400 cases. `GlobalExceptionHandler` (`@RestControllerAdvice` on `ru.levar.inboxa.be.controllers`) already maps these to `ErrorResponseDto` — no new handler needed.

### OpenAPI documentation

Annotate all new controllers and DTOs with `@Tag`, `@Operation`, `@ApiResponse`, and `@Schema` following the style in `TransactionController` and `DribsController`. SpringDoc picks up all controllers under `ru.levar.inboxa.be.controllers` automatically (`springdoc.packages-to-scan`).

---

## Migration Path

### Phase 1 — Push sync (now)
- Add `SyncWorker`, `RemoteSyncRepository`, `NetworkModule` to Android
- Add `syncStatus` + `serverWalkId` columns to `WalkEntity`
- Add `V4__create_streeter_schema.sql` Flyway migration; implement Spring Boot controllers, services, JPA entities under the `streeter` sub-namespace; provision `streeter-android` bearer token
- No Android UI changes required

### Phase 2 — Multi-device pull
- Add `GET /api/streeter/walks` pull endpoint
- Add pull-side sync path in Android client (incremental via `lastSyncedAt`)
- Conflict resolution: server wins on pull for editable fields

### Phase 3 — Optional: server-side map matching
- Move `matchTrace()` to backend for devices that prefer not to carry the PBF asset
- Device calls backend match endpoint, receives `matchedWayIds`, passes directly into `StreetCoverageEngine`
- The `RoutingEngine` interface boundary already makes this a bounded change — `matchTrace` is already separated from graph inspection methods
