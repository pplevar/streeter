# 011 — Streeter Sync: Backend Implementation Requirements

**Project**: `inboxa.be.kt`
**Feature**: Streeter walk sync API (Phase 1 push, Phase 2 pull)
**Author**: Generated from codebase analysis, 2026-05-05

---

## Table of Contents

1. [Overview](#1-overview)
2. [Prerequisites / Dependencies](#2-prerequisites--dependencies)
3. [Flyway Migration — V4](#3-flyway-migration--v4)
4. [JPA Entities](#4-jpa-entities)
5. [DTOs](#5-dtos)
6. [Repository Interfaces](#6-repository-interfaces)
7. [Service Layer](#7-service-layer)
8. [Controller](#8-controller)
9. [Configuration Changes](#9-configuration-changes)
10. [Auth Provisioning](#10-auth-provisioning)
11. [OpenAPI Annotations](#11-openapi-annotations)
12. [Testing Requirements](#12-testing-requirements)
13. [Acceptance Criteria](#13-acceptance-criteria)

---

## 1. Overview

This feature adds a `streeter` domain to the `inboxa.be.kt` Spring Boot backend. The Streeter Android app records walking routes and computes street coverage locally. This API allows the device to push synced walk data to a central server for storage and later querying.

**Phase 1** (this document) covers the four push endpoints that receive a walk and its sub-records. **Phase 2** (future) adds read endpoints for paginated walk lists and aggregated street coverage.

The API lives at `/api/streeter/**` and is secured by the same `ApiTokenAuthFilter` used by all other `/api/**` routes. No changes to `SecurityConfig` are required.

---

## 2. Prerequisites / Dependencies

All the following must be in place before development begins.

| Prerequisite | Detail |
|---|---|
| Flyway baseline | Existing migrations are V2 and V3. New migration is V4. The `streeter` schema must be added to `spring.flyway.schemas` in both profiles before the application starts. |
| `streeter` schema | Created inside V4 migration. Hibernate must also be aware of it (see Section 9). |
| Auth token | A new token named `streeter-android` must be provisioned before any device can call the API (see Section 10). |
| No new Gradle dependencies | All required libraries (`spring-boot-starter-data-jpa`, `spring-boot-starter-validation`, `springdoc-openapi-starter-webmvc-ui`, PostgreSQL driver) are already declared in `build.gradle.kts`. |

---

## 3. Flyway Migration — V4

**File path**: `src/main/resources/db/migration/V4__create_streeter_schema.sql`

Follow the style established in `V2__create_investments_schema.sql`: schema creation first, then tables in dependency order, then indexes, then constraints. Use `BIGSERIAL` for surrogate PKs, `VARCHAR` for bounded strings, `TEXT` for unbounded strings, `BYTEA` for binary blobs, `NUMERIC` for decimals, `TIMESTAMPTZ` for timestamps, and `BOOLEAN` with explicit defaults.

```sql
-- ============================================================
-- STREETER WALK SYNC SYSTEM - DATABASE SCHEMA
-- Version: 1.0
-- Date: 2026-05-05
-- ============================================================

-- Step 1: Create schema
CREATE SCHEMA IF NOT EXISTS streeter;

-- Step 2: Create walks table (no dependencies)
-- client_id:     UUID string sent by the device (stable across reinstalls)
-- local_walk_id: device-local Long walk ID
-- (client_id, local_walk_id) together form the idempotency key
CREATE TABLE streeter.walks (
    id              BIGSERIAL       PRIMARY KEY,
    client_id       VARCHAR(36)     NOT NULL,
    local_walk_id   BIGINT          NOT NULL,
    title           VARCHAR(500),
    status          VARCHAR(30)     NOT NULL,
    started_at      TIMESTAMPTZ     NOT NULL,
    ended_at        TIMESTAMPTZ,
    distance_meters NUMERIC(12,2),
    sync_status     VARCHAR(30)     NOT NULL DEFAULT 'PENDING',
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_walk_status CHECK (
        status IN ('RECORDING','PENDING_MATCH','COMPLETED','MANUAL_DRAFT')
    ),
    CONSTRAINT chk_walk_sync_status CHECK (
        sync_status IN ('PENDING','WALK_SYNCED','GPS_SYNCED','SEGMENTS_SYNCED','COVERAGE_SYNCED')
    ),
    CONSTRAINT uq_walk_client_local UNIQUE (client_id, local_walk_id)
);

CREATE INDEX idx_walk_client_id         ON streeter.walks(client_id);
CREATE INDEX idx_walk_status            ON streeter.walks(status);
CREATE INDEX idx_walk_sync_status       ON streeter.walks(sync_status);
CREATE INDEX idx_walk_started_at        ON streeter.walks(started_at DESC);

-- Step 3: Create gps_traces table (depends on walks)
-- Stores a single compressed binary blob per walk (replace semantics).
CREATE TABLE streeter.gps_traces (
    id          BIGSERIAL   PRIMARY KEY,
    walk_id     BIGINT      NOT NULL,
    point_count INT         NOT NULL DEFAULT 0,
    data        BYTEA       NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_gps_trace_walk FOREIGN KEY (walk_id)
        REFERENCES streeter.walks(id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT uq_gps_trace_walk UNIQUE (walk_id)
);

CREATE INDEX idx_gps_trace_walk_id ON streeter.gps_traces(walk_id);

-- Step 4: Create route_segments table (depends on walks)
-- One row per segment, ordered by segment_index. Replace semantics (delete-all then insert).
CREATE TABLE streeter.route_segments (
    id              BIGSERIAL       PRIMARY KEY,
    walk_id         BIGINT          NOT NULL,
    segment_index   INT             NOT NULL,
    geometry_json   TEXT            NOT NULL,
    matched_way_ids TEXT,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_route_segment_walk FOREIGN KEY (walk_id)
        REFERENCES streeter.walks(id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT uq_route_segment_position UNIQUE (walk_id, segment_index)
);

CREATE INDEX idx_route_segment_walk_id ON streeter.route_segments(walk_id);

-- Step 5: Create street_coverage table (depends on walks)
-- One row per street per walk. Replace semantics.
CREATE TABLE streeter.street_coverage (
    id           BIGSERIAL       PRIMARY KEY,
    walk_id      BIGINT          NOT NULL,
    street_name  VARCHAR(500)    NOT NULL,
    covered_pct  NUMERIC(5,2)    NOT NULL DEFAULT 0.00,
    created_at   TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_street_covered_pct CHECK (covered_pct >= 0 AND covered_pct <= 100),
    CONSTRAINT fk_street_coverage_walk FOREIGN KEY (walk_id)
        REFERENCES streeter.walks(id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT uq_street_coverage_walk_street UNIQUE (walk_id, street_name)
);

CREATE INDEX idx_street_coverage_walk_id    ON streeter.street_coverage(walk_id);
CREATE INDEX idx_street_coverage_name       ON streeter.street_coverage(street_name);

-- Step 6: Create section_coverage table (depends on street_coverage)
-- One row per section per walk. Replace semantics (cascades from street_coverage delete).
CREATE TABLE streeter.section_coverage (
    id                  BIGSERIAL       PRIMARY KEY,
    street_coverage_id  BIGINT          NOT NULL,
    walk_id             BIGINT          NOT NULL,
    section_id          VARCHAR(16)     NOT NULL,
    from_node_id        BIGINT          NOT NULL,
    to_node_id          BIGINT          NOT NULL,
    length_meters       NUMERIC(10,2)   NOT NULL DEFAULT 0.00,
    is_covered          BOOLEAN         NOT NULL DEFAULT false,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_section_coverage_street FOREIGN KEY (street_coverage_id)
        REFERENCES streeter.street_coverage(id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT fk_section_coverage_walk FOREIGN KEY (walk_id)
        REFERENCES streeter.walks(id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT uq_section_coverage_walk_section UNIQUE (walk_id, section_id)
);

CREATE INDEX idx_section_coverage_street_cov_id ON streeter.section_coverage(street_coverage_id);
CREATE INDEX idx_section_coverage_walk_id       ON streeter.section_coverage(walk_id);
CREATE INDEX idx_section_coverage_section_id    ON streeter.section_coverage(section_id);
```

---

## 4. JPA Entities

All entities go in package `ru.levar.inboxa.be.domain.streeter`.

Follow the exact patterns from `domain/investments/Transaction.kt` and `domain/investments/Broker.kt`:
- `data class` with `val` fields
- `val id: Long? = null` with `@Id @GeneratedValue(strategy = GenerationType.IDENTITY)`
- `@Entity`, `@Table(name = "...", schema = "streeter", indexes = [...])`
- `@field:Schema(...)` on every field (not `@Schema` on the property)
- `@Column(name = "...", nullable = false)` explicit on every mapped column
- `@Enumerated(EnumType.STRING)` for enum columns
- Validation annotations (`@field:NotBlank`, `@field:Size`, `@field:DecimalMin`) where applicable
- KDoc on the class

### 4.1 WalkStatus enum

```kotlin
package ru.levar.inboxa.be.domain.streeter

/**
 * Lifecycle status of a walk, mirroring the Android app's WalkStatus enum.
 */
enum class WalkStatus {
    RECORDING,
    PENDING_MATCH,
    COMPLETED,
    MANUAL_DRAFT,
}
```

### 4.2 WalkSyncStatus enum

```kotlin
package ru.levar.inboxa.be.domain.streeter

/**
 * Tracks which sub-records have been successfully received from the device.
 * PENDING  → walk row created, no sub-records yet
 * WALK_SYNCED → walk metadata accepted
 * GPS_SYNCED → GPS trace accepted
 * SEGMENTS_SYNCED → route segments accepted
 * COVERAGE_SYNCED → street coverage (and sections) accepted — walk fully synced
 */
enum class WalkSyncStatus {
    PENDING,
    WALK_SYNCED,
    GPS_SYNCED,
    SEGMENTS_SYNCED,
    COVERAGE_SYNCED,
}
```

### 4.3 StreeterWalk

```kotlin
package ru.levar.inboxa.be.domain.streeter

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant

/**
 * Entity representing a walk pushed from the Streeter Android app.
 * (clientId, localWalkId) is the idempotency key; id is the server-assigned surrogate key.
 */
@Entity
@Table(
    name = "walks",
    schema = "streeter",
    indexes = [
        Index(name = "idx_walk_client_id",   columnList = "client_id"),
        Index(name = "idx_walk_status",      columnList = "status"),
        Index(name = "idx_walk_sync_status", columnList = "sync_status"),
        Index(name = "idx_walk_started_at",  columnList = "started_at"),
    ],
)
@Schema(description = "A walk recorded and pushed by the Streeter Android app")
data class StreeterWalk(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Schema(description = "Server-assigned walk identifier", example = "42")
    val id: Long? = null,

    @Column(name = "client_id", nullable = false, length = 36)
    @field:NotBlank(message = "Client ID is required")
    @field:Size(max = 36, message = "Client ID must not exceed 36 characters")
    @field:Schema(description = "Device UUID identifying the client", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890", required = true)
    val clientId: String,

    @Column(name = "local_walk_id", nullable = false)
    @field:Schema(description = "Device-local walk ID", example = "7", required = true)
    val localWalkId: Long,

    @Column(name = "title", length = 500)
    @field:Size(max = 500, message = "Title must not exceed 500 characters")
    @field:Schema(description = "Optional walk title", example = "Morning run")
    val title: String? = null,

    @Column(name = "status", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    @field:Schema(description = "Walk lifecycle status", example = "COMPLETED", required = true)
    val status: WalkStatus,

    @Column(name = "started_at", nullable = false)
    @field:Schema(description = "Walk start timestamp (UTC)", example = "2026-05-01T08:00:00Z", required = true)
    val startedAt: Instant,

    @Column(name = "ended_at")
    @field:Schema(description = "Walk end timestamp (UTC)", example = "2026-05-01T09:00:00Z")
    val endedAt: Instant? = null,

    @Column(name = "distance_meters", precision = 12, scale = 2)
    @field:Schema(description = "Total distance in metres", example = "5430.25")
    val distanceMeters: BigDecimal? = null,

    @Column(name = "sync_status", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    @field:Schema(description = "Which sub-records have been synced", example = "WALK_SYNCED")
    val syncStatus: WalkSyncStatus = WalkSyncStatus.PENDING,

    @Column(name = "created_at", nullable = false, updatable = false)
    @field:Schema(description = "Server creation timestamp")
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    @field:Schema(description = "Server last-updated timestamp")
    val updatedAt: Instant = Instant.now(),
)
```

### 4.4 StreeterGpsTrace

```kotlin
package ru.levar.inboxa.be.domain.streeter

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant

/**
 * Stores the compressed GPS trace blob for a walk.
 * One row per walk; posting again replaces the existing row.
 */
@Entity
@Table(
    name = "gps_traces",
    schema = "streeter",
    indexes = [
        Index(name = "idx_gps_trace_walk_id", columnList = "walk_id"),
    ],
)
@Schema(description = "Compressed GPS trace associated with a walk")
data class StreeterGpsTrace(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Schema(description = "Server-assigned trace identifier")
    val id: Long? = null,

    @Column(name = "walk_id", nullable = false)
    @field:Schema(description = "Server walk ID this trace belongs to", example = "42", required = true)
    val walkId: Long,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "walk_id", referencedColumnName = "id", insertable = false, updatable = false)
    val walk: StreeterWalk? = null,

    @Column(name = "point_count", nullable = false)
    @field:Schema(description = "Number of GPS points encoded in the blob", example = "843")
    val pointCount: Int = 0,

    @Column(name = "data", nullable = false, columnDefinition = "BYTEA")
    @field:Schema(description = "Compressed binary GPS trace data")
    val data: ByteArray,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant = Instant.now(),
) {
    // ByteArray requires manual equals/hashCode to satisfy data class contract
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StreeterGpsTrace) return false
        return id == other.id && walkId == other.walkId
    }

    override fun hashCode(): Int = 31 * (id?.hashCode() ?: 0) + walkId.hashCode()
}
```

### 4.5 StreeterRouteSegment

```kotlin
package ru.levar.inboxa.be.domain.streeter

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table

/**
 * One matched route segment belonging to a walk.
 * The full set is replaced atomically on each push.
 */
@Entity
@Table(
    name = "route_segments",
    schema = "streeter",
    indexes = [
        Index(name = "idx_route_segment_walk_id", columnList = "walk_id"),
    ],
)
@Schema(description = "A single matched route segment within a walk")
data class StreeterRouteSegment(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Schema(description = "Server-assigned segment identifier")
    val id: Long? = null,

    @Column(name = "walk_id", nullable = false)
    @field:Schema(description = "Server walk ID this segment belongs to", example = "42", required = true)
    val walkId: Long,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "walk_id", referencedColumnName = "id", insertable = false, updatable = false)
    val walk: StreeterWalk? = null,

    @Column(name = "segment_index", nullable = false)
    @field:Schema(description = "Zero-based position of this segment in the route", example = "0", required = true)
    val segmentIndex: Int,

    @Column(name = "geometry_json", nullable = false, columnDefinition = "TEXT")
    @field:Schema(description = "GeoJSON LineString geometry of the segment", required = true)
    val geometryJson: String,

    @Column(name = "matched_way_ids", columnDefinition = "TEXT")
    @field:Schema(description = "Comma-separated OSM way IDs matched to this segment")
    val matchedWayIds: String? = null,
)
```

### 4.6 StreeterStreetCoverage

```kotlin
package ru.levar.inboxa.be.domain.streeter

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant

/**
 * Street-level coverage record for one walk.
 * The full set is replaced atomically on each push.
 */
@Entity
@Table(
    name = "street_coverage",
    schema = "streeter",
    indexes = [
        Index(name = "idx_street_coverage_walk_id", columnList = "walk_id"),
        Index(name = "idx_street_coverage_name",    columnList = "street_name"),
    ],
)
@Schema(description = "Street-level coverage record for a walk")
data class StreeterStreetCoverage(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Schema(description = "Server-assigned coverage record identifier")
    val id: Long? = null,

    @Column(name = "walk_id", nullable = false)
    @field:Schema(description = "Server walk ID this record belongs to", example = "42", required = true)
    val walkId: Long,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "walk_id", referencedColumnName = "id", insertable = false, updatable = false)
    val walk: StreeterWalk? = null,

    @Column(name = "street_name", nullable = false, length = 500)
    @field:NotBlank(message = "Street name is required")
    @field:Size(max = 500, message = "Street name must not exceed 500 characters")
    @field:Schema(description = "Name of the covered street", example = "Main Street", required = true)
    val streetName: String,

    @Column(name = "covered_pct", nullable = false, precision = 5, scale = 2)
    @field:DecimalMin(value = "0.00", message = "Coverage percentage cannot be negative")
    @field:DecimalMax(value = "100.00", message = "Coverage percentage cannot exceed 100")
    @field:Schema(description = "Percentage of the street covered by this walk", example = "67.50", required = true)
    val coveredPct: BigDecimal = BigDecimal.ZERO,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant = Instant.now(),
)
```

### 4.7 StreeterSectionCoverage

```kotlin
package ru.levar.inboxa.be.domain.streeter

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.math.BigDecimal

/**
 * Section-level coverage detail within a street coverage record.
 * Section IDs are stable 16-char hex strings (MD5 of streetName|fromNodeId|toNodeId).
 */
@Entity
@Table(
    name = "section_coverage",
    schema = "streeter",
    indexes = [
        Index(name = "idx_section_coverage_street_cov_id", columnList = "street_coverage_id"),
        Index(name = "idx_section_coverage_walk_id",       columnList = "walk_id"),
        Index(name = "idx_section_coverage_section_id",    columnList = "section_id"),
    ],
)
@Schema(description = "Section-level coverage detail within a street")
data class StreeterSectionCoverage(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Schema(description = "Server-assigned section coverage identifier")
    val id: Long? = null,

    @Column(name = "street_coverage_id", nullable = false)
    @field:Schema(description = "Parent street coverage record ID", example = "18", required = true)
    val streetCoverageId: Long,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "street_coverage_id", referencedColumnName = "id", insertable = false, updatable = false)
    val streetCoverage: StreeterStreetCoverage? = null,

    @Column(name = "walk_id", nullable = false)
    @field:Schema(description = "Server walk ID this section belongs to", example = "42", required = true)
    val walkId: Long,

    @Column(name = "section_id", nullable = false, length = 16)
    @field:NotBlank(message = "Section ID is required")
    @field:Size(min = 16, max = 16, message = "Section ID must be exactly 16 characters")
    @field:Schema(description = "Stable 16-char hex section identifier", example = "a1b2c3d4e5f60718", required = true)
    val sectionId: String,

    @Column(name = "from_node_id", nullable = false)
    @field:Schema(description = "OSM node ID at the start of the section", example = "123456789", required = true)
    val fromNodeId: Long,

    @Column(name = "to_node_id", nullable = false)
    @field:Schema(description = "OSM node ID at the end of the section", example = "987654321", required = true)
    val toNodeId: Long,

    @Column(name = "length_meters", nullable = false, precision = 10, scale = 2)
    @field:DecimalMin(value = "0.00", message = "Length cannot be negative")
    @field:Schema(description = "Section length in metres", example = "42.75", required = true)
    val lengthMeters: BigDecimal = BigDecimal.ZERO,

    @Column(name = "is_covered", nullable = false)
    @field:Schema(description = "Whether this section was traversed by the walk", example = "true", required = true)
    val isCovered: Boolean = false,
)
```

---

## 5. DTOs

All DTOs go in package `ru.levar.inboxa.be.dto.streeter`.

Follow the patterns from `dto/investments/TransactionDto.kt`:
- `data class` with `val` fields
- Validation annotations use `@field:` prefix
- `@field:NotNull` for required non-nullable types, `@field:NotBlank` for required strings
- `@field:Schema(...)` on every field
- Request and response DTOs are separate classes in the same file per domain object
- No business logic in DTOs

### 5.1 WalkDto.kt

```kotlin
package ru.levar.inboxa.be.dto.streeter

import com.fasterxml.jackson.annotation.JsonFormat
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import ru.levar.inboxa.be.domain.streeter.WalkStatus
import ru.levar.inboxa.be.domain.streeter.WalkSyncStatus
import java.math.BigDecimal
import java.time.Instant

/**
 * Response DTO for a synced walk.
 */
@Schema(description = "Walk record as stored on the server")
data class WalkDto(
    @field:Schema(description = "Server-assigned walk identifier", example = "42")
    val id: Long,
    @field:Schema(description = "Device UUID", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
    val clientId: String,
    @field:Schema(description = "Device-local walk ID", example = "7")
    val localWalkId: Long,
    @field:Schema(description = "Optional walk title", example = "Morning run")
    val title: String?,
    @field:Schema(description = "Walk lifecycle status", example = "COMPLETED")
    val status: WalkStatus,
    @field:JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
    @field:Schema(description = "Walk start timestamp (UTC)", example = "2026-05-01T08:00:00.000Z")
    val startedAt: Instant,
    @field:JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
    @field:Schema(description = "Walk end timestamp (UTC)", example = "2026-05-01T09:00:00.000Z")
    val endedAt: Instant?,
    @field:Schema(description = "Total distance in metres", example = "5430.25")
    val distanceMeters: BigDecimal?,
    @field:Schema(description = "Sync progress indicator", example = "WALK_SYNCED")
    val syncStatus: WalkSyncStatus,
)

/**
 * Request DTO for upserting a walk (POST /api/streeter/walks).
 */
@Schema(description = "Walk upsert request from the Android device")
data class UpsertWalkRequest(
    @field:NotBlank(message = "Client ID is required")
    @field:Size(max = 36, message = "Client ID must not exceed 36 characters")
    @field:Schema(description = "Device UUID", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890", required = true)
    val clientId: String,
    @field:NotNull(message = "Local walk ID is required")
    @field:Schema(description = "Device-local walk ID", example = "7", required = true)
    val localWalkId: Long,
    @field:Size(max = 500, message = "Title must not exceed 500 characters")
    @field:Schema(description = "Optional walk title", example = "Morning run")
    val title: String? = null,
    @field:NotNull(message = "Status is required")
    @field:Schema(description = "Walk lifecycle status", example = "COMPLETED", required = true)
    val status: WalkStatus,
    @field:NotNull(message = "Started-at timestamp is required")
    @field:Schema(description = "Walk start timestamp (UTC)", example = "2026-05-01T08:00:00.000Z", required = true)
    val startedAt: Instant,
    @field:Schema(description = "Walk end timestamp (UTC)", example = "2026-05-01T09:00:00.000Z")
    val endedAt: Instant? = null,
    @field:DecimalMin(value = "0.0", message = "Distance cannot be negative")
    @field:Schema(description = "Total distance in metres", example = "5430.25")
    val distanceMeters: BigDecimal? = null,
)

/**
 * Response DTO for a successful walk upsert — returns only the server ID.
 */
@Schema(description = "Response returned after a walk is accepted")
data class WalkUpsertResponse(
    @field:Schema(description = "Server-assigned walk identifier", example = "42")
    val serverWalkId: Long,
)
```

### 5.2 GpsTraceDto.kt

```kotlin
package ru.levar.inboxa.be.dto.streeter

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull

/**
 * Request DTO for replacing the GPS trace of a walk.
 * The binary blob is base64-encoded in JSON.
 */
@Schema(description = "Compressed GPS trace push request")
data class PushGpsTraceRequest(
    @field:NotNull(message = "Point count is required")
    @field:Min(value = 0, message = "Point count cannot be negative")
    @field:Schema(description = "Number of GPS points in the trace", example = "843", required = true)
    val pointCount: Int,
    @field:NotNull(message = "Trace data is required")
    @field:Schema(description = "Base64-encoded compressed GPS trace binary", required = true)
    val data: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PushGpsTraceRequest) return false
        return pointCount == other.pointCount && data.contentEquals(other.data)
    }

    override fun hashCode(): Int = 31 * pointCount + data.contentHashCode()
}
```

### 5.3 RouteSegmentDto.kt

```kotlin
package ru.levar.inboxa.be.dto.streeter

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull

/**
 * A single route segment as sent by the device.
 */
@Schema(description = "A single matched route segment")
data class RouteSegmentItem(
    @field:NotNull(message = "Segment index is required")
    @field:Min(value = 0, message = "Segment index cannot be negative")
    @field:Schema(description = "Zero-based position of the segment", example = "0", required = true)
    val segmentIndex: Int,
    @field:NotBlank(message = "Geometry JSON is required")
    @field:Schema(description = "GeoJSON LineString geometry", required = true)
    val geometryJson: String,
    @field:Schema(description = "Comma-separated OSM way IDs")
    val matchedWayIds: String? = null,
)

/**
 * Request DTO for replacing all route segments for a walk.
 */
@Schema(description = "Route segments push request — replaces the full set")
data class PushRouteSegmentsRequest(
    @field:NotEmpty(message = "At least one segment is required")
    @field:Schema(description = "Ordered list of route segments", required = true)
    val segments: List<RouteSegmentItem>,
)
```

### 5.4 StreetCoverageDto.kt

```kotlin
package ru.levar.inboxa.be.dto.streeter

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal

/**
 * A single section within a street coverage record.
 */
@Schema(description = "A single section's coverage within a street")
data class SectionCoverageItem(
    @field:NotBlank(message = "Section ID is required")
    @field:Size(min = 16, max = 16, message = "Section ID must be exactly 16 characters")
    @field:Schema(description = "Stable 16-char hex section identifier", example = "a1b2c3d4e5f60718", required = true)
    val sectionId: String,
    @field:NotNull(message = "From-node ID is required")
    @field:Schema(description = "OSM node ID at the start of the section", example = "123456789", required = true)
    val fromNodeId: Long,
    @field:NotNull(message = "To-node ID is required")
    @field:Schema(description = "OSM node ID at the end of the section", example = "987654321", required = true)
    val toNodeId: Long,
    @field:NotNull(message = "Length is required")
    @field:DecimalMin(value = "0.0", message = "Length cannot be negative")
    @field:Schema(description = "Section length in metres", example = "42.75", required = true)
    val lengthMeters: BigDecimal,
    @field:Schema(description = "Whether this section was traversed", example = "true", required = true)
    val isCovered: Boolean,
)

/**
 * A single street coverage record as sent by the device.
 */
@Schema(description = "Coverage data for one street")
data class StreetCoverageItem(
    @field:NotBlank(message = "Street name is required")
    @field:Size(max = 500, message = "Street name must not exceed 500 characters")
    @field:Schema(description = "Name of the street", example = "Main Street", required = true)
    val streetName: String,
    @field:NotNull(message = "Covered percentage is required")
    @field:DecimalMin(value = "0.00", message = "Coverage percentage cannot be negative")
    @field:DecimalMax(value = "100.00", message = "Coverage percentage cannot exceed 100")
    @field:Schema(description = "Percentage of this street covered by the walk", example = "67.50", required = true)
    val coveredPct: BigDecimal,
    @field:NotEmpty(message = "At least one section is required")
    @field:Schema(description = "Sections making up this street record", required = true)
    val sections: List<SectionCoverageItem>,
)

/**
 * Request DTO for replacing all street coverage for a walk.
 */
@Schema(description = "Street coverage push request — replaces the full set")
data class PushStreetCoverageRequest(
    @field:NotEmpty(message = "At least one street coverage record is required")
    @field:Schema(description = "Street coverage entries for the walk", required = true)
    val streets: List<StreetCoverageItem>,
)
```

---

## 6. Repository Interfaces

All repositories go in package `ru.levar.inboxa.be.repositories.streeter`.

Follow the pattern from `repositories/investments/BrokerRepository.kt`:
- `@Repository` annotation
- Extends `JpaRepository<Entity, Long>`
- Only declare methods beyond what `JpaRepository` provides
- KDoc on the interface and each custom method

### 6.1 StreeterWalkRepository

```kotlin
package ru.levar.inboxa.be.repositories.streeter

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import ru.levar.inboxa.be.domain.streeter.StreeterWalk

/**
 * Repository for StreeterWalk entity operations.
 */
@Repository
interface StreeterWalkRepository : JpaRepository<StreeterWalk, Long> {
    /**
     * Find a walk by the device's idempotency key.
     *
     * @param clientId  Device UUID
     * @param localWalkId  Device-local walk ID
     * @return The walk if it exists, null otherwise
     */
    fun findByClientIdAndLocalWalkId(clientId: String, localWalkId: Long): StreeterWalk?

    /**
     * Check whether a walk with the given idempotency key already exists.
     */
    fun existsByClientIdAndLocalWalkId(clientId: String, localWalkId: Long): Boolean

    /**
     * Retrieve all walks ordered by start time descending with pagination.
     */
    fun findAllByOrderByStartedAtDesc(pageable: Pageable): Page<StreeterWalk>
}
```

### 6.2 StreeterGpsTraceRepository

```kotlin
package ru.levar.inboxa.be.repositories.streeter

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import ru.levar.inboxa.be.domain.streeter.StreeterGpsTrace

/**
 * Repository for StreeterGpsTrace entity operations.
 */
@Repository
interface StreeterGpsTraceRepository : JpaRepository<StreeterGpsTrace, Long> {
    /**
     * Find the GPS trace for a specific walk.
     *
     * @param walkId Server walk ID
     * @return The GPS trace if present, null otherwise
     */
    fun findByWalkId(walkId: Long): StreeterGpsTrace?

    /**
     * Delete the GPS trace for a specific walk (used before replacement).
     */
    fun deleteByWalkId(walkId: Long)
}
```

### 6.3 StreeterRouteSegmentRepository

```kotlin
package ru.levar.inboxa.be.repositories.streeter

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import ru.levar.inboxa.be.domain.streeter.StreeterRouteSegment

/**
 * Repository for StreeterRouteSegment entity operations.
 */
@Repository
interface StreeterRouteSegmentRepository : JpaRepository<StreeterRouteSegment, Long> {
    /**
     * Find all segments for a walk ordered by position.
     */
    fun findByWalkIdOrderBySegmentIndexAsc(walkId: Long): List<StreeterRouteSegment>

    /**
     * Delete all segments for a walk (used before replacement).
     */
    fun deleteByWalkId(walkId: Long)
}
```

### 6.4 StreeterStreetCoverageRepository

```kotlin
package ru.levar.inboxa.be.repositories.streeter

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import ru.levar.inboxa.be.domain.streeter.StreeterStreetCoverage

/**
 * Repository for StreeterStreetCoverage entity operations.
 */
@Repository
interface StreeterStreetCoverageRepository : JpaRepository<StreeterStreetCoverage, Long> {
    /**
     * Find all street coverage records for a walk.
     */
    fun findByWalkId(walkId: Long): List<StreeterStreetCoverage>

    /**
     * Delete all street coverage records for a walk (cascades to section_coverage).
     */
    fun deleteByWalkId(walkId: Long)
}
```

### 6.5 StreeterSectionCoverageRepository

```kotlin
package ru.levar.inboxa.be.repositories.streeter

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import ru.levar.inboxa.be.domain.streeter.StreeterSectionCoverage

/**
 * Repository for StreeterSectionCoverage entity operations.
 */
@Repository
interface StreeterSectionCoverageRepository : JpaRepository<StreeterSectionCoverage, Long> {
    /**
     * Find all section coverage records for a walk.
     */
    fun findByWalkId(walkId: Long): List<StreeterSectionCoverage>
}
```

---

## 7. Service Layer

**File**: `src/main/kotlin/ru/levar/inboxa/be/services/streeter/StreeterSyncService.kt`
**Package**: `ru.levar.inboxa.be.services.streeter`

Follow the pattern from `services/investments/TransactionService.kt`:
- `@Service` + `@Transactional(readOnly = true)` at class level
- Write methods annotated individually with `@Transactional`
- Constructor injection of all repository dependencies
- Throw `ResourceNotFoundException` (existing) for missing resources, `ValidationException` (existing) for business rule failures
- Private extension function `Entity.toDto()` for mapping — do not use a separate mapper class
- KDoc on the class and each public method

```kotlin
package ru.levar.inboxa.be.services.streeter

import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.levar.inboxa.be.domain.streeter.WalkSyncStatus
import ru.levar.inboxa.be.dto.streeter.*
import ru.levar.inboxa.be.exceptions.ResourceNotFoundException
import ru.levar.inboxa.be.repositories.streeter.*

/**
 * Service for the Streeter push-sync API.
 * Each public method corresponds to one HTTP endpoint.
 */
@Service
@Transactional(readOnly = true)
class StreeterSyncService(
    private val walkRepository: StreeterWalkRepository,
    private val gpsTraceRepository: StreeterGpsTraceRepository,
    private val routeSegmentRepository: StreeterRouteSegmentRepository,
    private val streetCoverageRepository: StreeterStreetCoverageRepository,
    private val sectionCoverageRepository: StreeterSectionCoverageRepository,
)
```

The following method signatures must be implemented in full:

| Method | Signature | Transaction | Behaviour |
|---|---|---|---|
| `upsertWalk` | `fun upsertWalk(request: UpsertWalkRequest): WalkUpsertResponse` | `@Transactional` | Look up by `(clientId, localWalkId)`. If found, update all mutable fields and set `updatedAt = now()`, `syncStatus = WALK_SYNCED`. If not found, insert with `syncStatus = WALK_SYNCED`. Return `WalkUpsertResponse(serverWalkId = saved.id!!)`. |
| `replaceGpsTrace` | `fun replaceGpsTrace(serverWalkId: Long, request: PushGpsTraceRequest)` | `@Transactional` | Load walk by `serverWalkId`; throw `ResourceNotFoundException("Walk not found with id: $serverWalkId")` if absent. Delete existing `StreeterGpsTrace` for this walk (if any). Insert new trace. Update walk `syncStatus` to `GPS_SYNCED` and `updatedAt`. |
| `replaceRouteSegments` | `fun replaceRouteSegments(serverWalkId: Long, request: PushRouteSegmentsRequest)` | `@Transactional` | Load walk; throw `ResourceNotFoundException` if absent. Delete all existing `StreeterRouteSegment` rows for this walk. Insert all segments from request preserving `segmentIndex`. Update walk `syncStatus` to `SEGMENTS_SYNCED` and `updatedAt`. |
| `replaceStreetCoverage` | `fun replaceStreetCoverage(serverWalkId: Long, request: PushStreetCoverageRequest)` | `@Transactional` | Load walk; throw `ResourceNotFoundException` if absent. Delete all `StreeterStreetCoverage` rows for this walk (cascades to `StreeterSectionCoverage`). Insert all street coverage and section coverage records from request. Update walk `syncStatus` to `COVERAGE_SYNCED` and `updatedAt`. |
| `getWalks` | `fun getWalks(limit: Int = 50, offset: Int = 0): Page<WalkDto>` | read-only | Paginate `StreeterWalk` ordered by `startedAt DESC`. Map each to `WalkDto`. |

**syncStatus progression rule**: Each write method must advance `syncStatus` unconditionally to the new value, even if it would be a regression (e.g., the device re-sends GPS after coverage was already synced). This keeps upsert semantics simple. If stricter progression is needed it can be added later.

---

## 8. Controller

**File**: `src/main/kotlin/ru/levar/inboxa/be/controllers/streeter/StreeterSyncController.kt`
**Package**: `ru.levar.inboxa.be.controllers.streeter`

Follow the exact style from `controllers/investments/TransactionController.kt`:
- `@RestController`, `@RequestMapping`, `@Tag` at class level
- `@Operation`, `@ApiResponse`, `@Parameter` on every handler
- `@Valid @RequestBody` on all write endpoints
- Return `ResponseEntity<T>`
- `HttpStatus.CREATED` (201) for the walk upsert (first-time insert and re-upsert both return 201 for simplicity — the response body distinguishes new from existing via `serverWalkId`)
- `HttpStatus.NO_CONTENT` (204) for the three sub-record replace endpoints (no meaningful body to return)
- Import `ErrorResponseDto` for `@ApiResponse` content schemas on 4xx

```kotlin
@RestController
@RequestMapping("/api/streeter")
@Tag(name = "Streeter Sync", description = "Walk sync endpoints for the Streeter Android app")
class StreeterSyncController(
    private val streeterSyncService: StreeterSyncService,
)
```

### 8.1 Endpoint table

| Handler method | HTTP method + path | Request body | Success response | Error responses |
|---|---|---|---|---|
| `upsertWalk` | `POST /api/streeter/walks` | `@Valid UpsertWalkRequest` | `201 WalkUpsertResponse` | `400 ErrorResponseDto` |
| `replaceGpsTrace` | `POST /api/streeter/walks/{serverWalkId}/gps-trace` | `@Valid PushGpsTraceRequest` | `204 Void` | `400 ErrorResponseDto`, `404 ErrorResponseDto` |
| `replaceRouteSegments` | `POST /api/streeter/walks/{serverWalkId}/route-segments` | `@Valid PushRouteSegmentsRequest` | `204 Void` | `400 ErrorResponseDto`, `404 ErrorResponseDto` |
| `replaceStreetCoverage` | `POST /api/streeter/walks/{serverWalkId}/street-coverage` | `@Valid PushStreetCoverageRequest` | `204 Void` | `400 ErrorResponseDto`, `404 ErrorResponseDto` |
| `getWalks` | `GET /api/streeter/walks` | — | `200 Page<WalkDto>` | `400 ErrorResponseDto` |

### 8.2 Path variable and query parameter annotations

- `@PathVariable serverWalkId: Long` — annotate with `@Parameter(description = "Server walk ID", example = "42")`
- `@RequestParam(defaultValue = "50") limit: Int` — `@Parameter(description = "Max records to return (1-1000)")`
- `@RequestParam(defaultValue = "0") offset: Int` — `@Parameter(description = "Records to skip")`

---

## 9. Configuration Changes

### 9.1 application-dev.properties

Add `streeter` to the Flyway schemas list:

```properties
spring.flyway.schemas=inboxa,investments,streeter
```

### 9.2 application-prod.properties

Add `streeter` to the Flyway schemas list:

```properties
spring.flyway.schemas=inboxa,investments,streeter
```

### 9.3 application.properties (base)

No change required to the base file. The `spring.jpa.properties.hibernate.default_schema=inboxa` setting does not affect multi-schema JPA entities because each entity declares its schema explicitly via `@Table(schema = "streeter")`.

### 9.4 Test properties

For H2-based unit/controller tests, add the `streeter` schema to the H2 init string in `src/test/resources/application-test.properties`:

```properties
spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=jsonb;INIT=CREATE SCHEMA IF NOT EXISTS inboxa\;CREATE SCHEMA IF NOT EXISTS investments\;CREATE SCHEMA IF NOT EXISTS streeter
```

Also add to `src/test/resources/schema.sql`:

```sql
CREATE SCHEMA IF NOT EXISTS streeter;
```

For Testcontainer-based integration tests (`AbstractPostgresIntegrationTest`) no change is needed — Flyway runs V4 automatically against the real PostgreSQL container.

---

## 10. Auth Provisioning

`ApiTokenProperties` reads all tokens from the map `inboxa.auth.tokens.*` (prefix `inboxa.auth`, property name `tokens`, which is a `MutableMap<String, String>`). The key is a human-readable name used for logging; the value is the raw bearer token string compared with constant-time equality.

**Development** — add to `application-dev.properties`:

```properties
inboxa.auth.tokens.streeter-android=streeter-dev-token-changeme
```

**Production** — add to `application-prod.properties`:

```properties
inboxa.auth.tokens.streeter-android=${INBOXA_API_TOKEN_STREETER_ANDROID}
```

Then set the environment variable `INBOXA_API_TOKEN_STREETER_ANDROID` on the production host to a securely generated random string (minimum 32 characters). The Android app must send `Authorization: Bearer <token>` on every request using this value.

No code changes are required. The `ApiTokenAuthFilter` iterates over all entries in `apiTokenProperties.tokens` and accepts any matching token.

---

## 11. OpenAPI Annotations

### Tag

```
name = "Streeter Sync"
description = "Walk sync endpoints for the Streeter Android app"
```

### Operation summaries

| Handler | summary |
|---|---|
| `upsertWalk` | `"Upsert a walk"` |
| `replaceGpsTrace` | `"Replace GPS trace for a walk"` |
| `replaceRouteSegments` | `"Replace route segments for a walk"` |
| `replaceStreetCoverage` | `"Replace street coverage for a walk"` |
| `getWalks` | `"List synced walks"` |

### Operation descriptions (non-trivial)

- `upsertWalk`: "Creates a new walk or updates an existing one identified by (clientId, localWalkId). Returns the server-assigned walkId. Idempotent — safe to call multiple times with the same payload."
- `replaceGpsTrace`: "Replaces the entire GPS trace for the given walk. Previous trace is deleted. Idempotent."
- `replaceRouteSegments`: "Replaces all route segments for the given walk. All previous segments are deleted. Idempotent."
- `replaceStreetCoverage`: "Replaces all street coverage (including section detail) for the given walk. Previous records are deleted. Idempotent."

---

## 12. Testing Requirements

Follow the established patterns: `@ExtendWith(MockKExtension::class)` for pure unit tests, `@WebMvcTest` + `@Import(TestConfig::class, GlobalExceptionHandler::class)` for controller slice tests.

### 12.1 Unit tests — StreeterSyncServiceTest

**File**: `src/test/kotlin/ru/levar/inboxa/be/services/streeter/StreeterSyncServiceTest.kt`
**Extension**: `@ExtendWith(MockKExtension::class)` only (no Spring context)
**Setup**: Construct the service manually using `mockk<>()` repositories (same pattern as `TransactionServiceTest`)

Required test cases:

| Test name (backtick style) | Verifies |
|---|---|
| `should create walk when none exists` | New walk inserted; `syncStatus = WALK_SYNCED`; returns correct `serverWalkId` |
| `should update walk when idempotency key already exists` | Existing walk updated, not duplicated |
| `should throw ResourceNotFoundException when walk not found for gps trace` | `replaceGpsTrace` with unknown `serverWalkId` throws `ResourceNotFoundException` |
| `should delete existing trace before inserting new one` | `deleteByWalkId` called before `save` |
| `should advance syncStatus to GPS_SYNCED` | Walk `syncStatus` updated after GPS trace accepted |
| `should throw ResourceNotFoundException when walk not found for route segments` | |
| `should delete all segments before inserting new ones` | |
| `should advance syncStatus to SEGMENTS_SYNCED` | |
| `should throw ResourceNotFoundException when walk not found for street coverage` | |
| `should delete all street coverage before inserting new ones` | |
| `should advance syncStatus to COVERAGE_SYNCED` | |
| `should paginate walks correctly` | Correct `PageRequest` constructed from `limit`/`offset` |

### 12.2 Controller slice tests — StreeterSyncControllerTest

**File**: `src/test/kotlin/ru/levar/inboxa/be/controllers/streeter/StreeterSyncControllerTest.kt`
**Annotations**: `@ExtendWith(SpringExtension::class, MockKExtension::class)`, `@WebMvcTest(StreeterSyncController::class)`, `@Import(StreeterSyncControllerTest.TestConfig::class, GlobalExceptionHandler::class)`
**Auth**: `inboxa.auth.enabled=false` is set in `application-test.properties` so no token header is needed in tests.

Required test cases:

| Test name | Verifies |
|---|---|
| `should return 201 with serverWalkId on valid upsert` | Happy path for `POST /api/streeter/walks` |
| `should return 400 when clientId is blank` | Validation rejection |
| `should return 400 when status is missing` | Validation rejection |
| `should return 204 on valid gps trace push` | Happy path for GPS sub-endpoint |
| `should return 404 when walk not found for gps trace` | `ResourceNotFoundException` → 404 |
| `should return 204 on valid route segments push` | Happy path |
| `should return 404 when walk not found for route segments` | |
| `should return 204 on valid street coverage push` | Happy path |
| `should return 404 when walk not found for street coverage` | |
| `should return 200 with page of walks` | `GET /api/streeter/walks` |

### 12.3 Integration tests (optional, Phase 1)

If full DB integration coverage is desired, extend `AbstractPostgresIntegrationTest`. The Testcontainer will run V4 via Flyway automatically. Verify that:
- Upsert creates exactly one walk row
- Re-upsert with same `(clientId, localWalkId)` does not insert a second row
- GPS replace deletes the old blob and stores the new one
- Street coverage replace cascades to `section_coverage`

---

## 13. Acceptance Criteria

All criteria are verifiable with `curl` against a running dev server (`SPRING_PROFILES_ACTIVE=dev`).

### AC-1: Walk upsert — new walk

```
POST /api/streeter/walks
Authorization: Bearer streeter-dev-token-changeme
Content-Type: application/json

{
  "clientId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "localWalkId": 1,
  "status": "COMPLETED",
  "startedAt": "2026-05-01T08:00:00.000Z",
  "endedAt": "2026-05-01T09:00:00.000Z",
  "distanceMeters": 5430.25
}
```

Expected: HTTP 201, body `{"serverWalkId": <positive Long>}`. Database row exists in `streeter.walks` with `sync_status = 'WALK_SYNCED'`.

### AC-2: Walk upsert — idempotent re-send

Call AC-1 again with the same `clientId` and `localWalkId`. Expected: HTTP 201, same `serverWalkId` as before. No duplicate row in `streeter.walks`.

### AC-3: GPS trace replace

```
POST /api/streeter/walks/{serverWalkId}/gps-trace
Authorization: Bearer streeter-dev-token-changeme
Content-Type: application/json

{
  "pointCount": 3,
  "data": "AQIDBA=="
}
```

Expected: HTTP 204. Row inserted (or replaced) in `streeter.gps_traces`. Walk `sync_status` updated to `'GPS_SYNCED'`.

### AC-4: GPS trace replace — unknown walk

Call AC-3 with `serverWalkId = 99999`. Expected: HTTP 404, body matches `ErrorResponseDto` schema (`status: 404`, `error: "Not Found"`).

### AC-5: Route segments replace

```
POST /api/streeter/walks/{serverWalkId}/route-segments
Authorization: Bearer streeter-dev-token-changeme
Content-Type: application/json

{
  "segments": [
    {
      "segmentIndex": 0,
      "geometryJson": "{\"type\":\"LineString\",\"coordinates\":[[0,0],[1,1]]}",
      "matchedWayIds": "123,456"
    }
  ]
}
```

Expected: HTTP 204. Previous segments for the walk deleted; new segments inserted. Walk `sync_status` = `'SEGMENTS_SYNCED'`.

### AC-6: Street coverage replace

```
POST /api/streeter/walks/{serverWalkId}/street-coverage
Authorization: Bearer streeter-dev-token-changeme
Content-Type: application/json

{
  "streets": [
    {
      "streetName": "Main Street",
      "coveredPct": 67.50,
      "sections": [
        {
          "sectionId": "a1b2c3d4e5f60718",
          "fromNodeId": 123456789,
          "toNodeId": 987654321,
          "lengthMeters": 42.75,
          "isCovered": true
        }
      ]
    }
  ]
}
```

Expected: HTTP 204. Previous street_coverage and section_coverage rows for the walk deleted; new rows inserted. Walk `sync_status` = `'COVERAGE_SYNCED'`.

### AC-7: Authentication enforcement

Call any endpoint without `Authorization` header. Expected: HTTP 401.

Call any endpoint with a wrong token. Expected: HTTP 401.

### AC-8: Validation rejection

Call `POST /api/streeter/walks` with `clientId` absent or empty. Expected: HTTP 400, body matches `ErrorResponseDto` with `error: "Validation Error"` and a message naming the failing field.

### AC-9: Walk list

```
GET /api/streeter/walks?limit=10&offset=0
Authorization: Bearer streeter-dev-token-changeme
```

Expected: HTTP 200, body is a Spring `Page` JSON structure (`content`, `totalElements`, `totalPages`, etc.) with walk entries ordered by `startedAt` descending.

### AC-10: Flyway schema

On first application start after adding `streeter` to `spring.flyway.schemas`, V4 migration runs without error. Table `streeter.walks` exists and Flyway history shows version 4 as successful.
