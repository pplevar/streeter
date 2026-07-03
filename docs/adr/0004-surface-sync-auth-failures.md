# Surface Sync Auth Failures

[ADR-0002](0002-sync-auth-shared-bearer-token.md) wired a shared bearer token into the sync client but deferred auth-failure UX: every failure was treated identically — mark `SYNC_FAILED`, retry 3× with exponential backoff, then give up silently. A `401` is different in kind from a transient failure: retrying cannot fix a missing or wrong token, and the silence gives the user no cue to set or correct it in Settings. This is safe only while prod runs `PERMISSIVE`; before the flip to `STRICT` (where an unauthenticated user's every walk fails quietly) the failure must become visible and stop wasting retries.

We treat a `401` as a distinct, non-transient **auth failure** and handle it in one place per concern.

## Mechanics

- **Detection (transport):** the Ktor client's `HttpResponseValidator` maps a `401 Unauthorized` to a typed `SyncAuthException` (`domain/sync/`). This sits in the same `configureStreeterClient` block that attaches the token, so every sync call — `syncWalk`, `pullWalks`, `deleteWalk` — surfaces auth failure uniformly. Other statuses keep their existing handling, so a transient `5xx` is never mistaken for a bad token and `deleteWalk`'s `2xx`-confirms-or-`false` contract is unchanged.
- **Fail fast + signal (workers):** a shared `SyncFailureHandler` is the one unit that reacts to a sync outcome. On a `SyncAuthException` it returns "do not retry" (so the worker fails on the first attempt, no backoff) and raises the user signal; on any other failure it retries until the attempt budget is spent; on success it clears the signal. The three sync workers delegate to it instead of each duplicating the retry fold.
- **User signal:** `SyncAuthStatus` holds an observable `authFailed` flag. Settings observes it and shows a banner above the token field ("Sync was rejected — set or correct your token"); editing the token clears it, and the next attempt re-raises it only if the new token is still rejected.

## Considered Options

- **Global `expectSuccess = true` on the Ktor client** so every non-2xx throws. Rejected: it would make `deleteWalk`'s `500`-returns-`false` path throw instead, and conflate transient `5xx` with auth. A targeted validator that only reacts to `401` keeps other statuses' behavior intact.
- **Classify the `401` inside each worker** (inspect the failure and branch on retry). Rejected: duplicated across three workers and hard to unit-test (a `CoroutineWorker` needs a WorkManager/Android harness this project doesn't use). A pure `SyncFailureHandler` + `SyncAuthStatus` puts the fail-fast and signal-raising behavior behind plain JVM tests; the workers become thin delegators.
- **Persist the signal across process death** (SharedPreferences, like the token store). Rejected as unnecessary: an unresolved auth failure re-raises itself on the next sync attempt, so an in-memory flag is enough and needs no `Context`.
- **A per-walk auth status** distinct from the existing `SYNC_FAILED`. Rejected: auth is a single global transport gate (no User/Account concept, per ADR-0002), so one app-level signal — not per-walk state — matches the domain. Walks still land in `SYNC_FAILED` as before.

## Consequences

- **A `401` now fails on the first attempt** with no backoff retries, and the user gets an actionable banner where the fix lives. This is the prerequisite for flipping prod to `STRICT`.
- **The signal is app-level, not per-walk.** The banner says "sync is blocked by auth," not which walk failed; per-walk `SYNC_FAILED` continues to drive the History sync counts unchanged.
- **In-memory signal resets on process death.** Immediately after a cold start the banner is absent until the next sync attempt re-raises it — an accepted trade-off for not persisting a self-healing flag.
