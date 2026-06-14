# Decouple Sync from Calculation

Recording, creating, or editing a walk used to run the slow local Calculation (map matching + coverage) first and only Sync to the server as its final step — so a walk was not durable on the server until slow, retry-prone Calculation finished.

We decoupled them. New walks now Sync immediately and independently of Calculation, which runs in parallel and remains offline-capable. Because the server is authoritative only for walk metadata + GPS Trace (Coverage is a per-device local projection — see [CONTEXT.md](../../CONTEXT.md)), this is safe. Calculation re-syncs its one server-relevant output — the matched distance — on completion, and the user can stop a running Calculation: the walk then rests in `COMPLETED` without coverage and can be recalculated on demand.

## Considered Options

- **Gate Calculation behind Sync success** (literal reading of "calculate only after sync"). Rejected: `SyncWorker` requires network while `MapMatchingWorker` does not, so gating would make coverage unavailable offline until the network returned — an offline-first regression.
- **Treat matched distance as purely local-derived** (sync the raw walk once, never re-sync). Rejected: we chose to keep the server's `distanceM` updated with the matched value via a second sync, accepting one extra round trip per new walk.
