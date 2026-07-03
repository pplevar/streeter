# Delete a Walk via Tombstone

Deleting a walk has to converge across every device, but the pull feed is an add/update stream keyed on `synced_at` — it has no way to signal that a walk is *gone*. So a hard delete on the server is invisible to a device that already holds the walk. We model deletion as a **Tombstone**: the walk is marked `DELETED`, that mark propagates by Sync like any other metadata change, and each device removes the walk (and its local Calculation) on receiving it. The server keeps the tombstoned walk's metadata but discards its GPS Trace.

Deletion is **terminal** — once a walk is `DELETED` on the server, the upsert path will not un-delete it, so a concurrent metadata edit from another device cannot resurrect a deleted walk.

## Mechanics

- **Endpoint:** a dedicated `DELETE /api/streeter/walks/{serverWalkId}` sets `status = DELETED`, bumps `updated_at`/`synced_at`, and purges the `gps_traces` row atomically. Idempotent: deleting an already-deleted or unknown walk returns `204`.
- **Originating device:** marks the walk `DELETED` and strips its Coverage rows immediately (so covered-street counts are correct at once, online or off), then hard-deletes the local row once the server returns `204`. A walk that was never synced (`serverWalkId == null`) is hard-deleted locally with no server call.
- **Peer device:** on pulling a `DELETED` tombstone for a walk it holds, it hard-deletes the local row; Room's `CASCADE` removes the walk's Coverage. `DELETED` therefore never persists in local storage — it is only a transient state (the originating device's pending-delete window, or an in-transit pull tombstone).
- **Pull filtering:** `GET /walks` excludes tombstones by default; the Android sync client passes `?includeDeleted=true` because it is the one consumer that needs them to converge. Deleted walks' Traces are purged, so `GET /walks/{id}/gps-trace` returns `404` regardless.

## Considered Options

- **Hard delete only** (drop the server row, no marker). Rejected: peers that already hold the walk are never told it is gone — the pull cannot express absence — so the walk lingers on every other device.
- **Hard delete + a separate deletions feed** the client must also consume. Rejected: a second sync channel for no near-term benefit, and it fights the existing tombstone scaffolding (`WalkStatus.DELETED`, the pull consumer's tombstone branch). Reclaiming server space eagerly is deferrable.
- **Reuse the upsert `POST /walks` with `status = DELETED`** instead of a dedicated verb. Rejected in favor of an explicit `DELETE` so the Trace purge is atomic and the intent is unambiguous at the API surface.

## Consequences

- **Tombstone rows accumulate** in `streeter.walks` indefinitely. A GC/retention policy is left for later; it is a reversible, isolated decision.
- **The Android sync pull must send `includeDeleted=true`.** If it does not, cross-device deletion silently stops working while everything else keeps passing.
- Hard-deleting local rows on both sides **fixes a latent single-device bug**: `observeCoveredStreetCount` reads `walk_streets` with no status filter, so the previous soft-delete left deleted walks counting toward coverage.
- **Deletion wins over concurrent edits.** Because the tombstone is a sink state, an unsynced local edit to a walk deleted on another device is lost on convergence — the accepted trade-off for making delete terminal.
