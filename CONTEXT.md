# Streeter

An offline-first Android app for recording walks and tracking which city streets you have covered. Walk metadata and raw GPS traces are synchronized with a server; everything derived from them is computed locally on each device.

## Language

**Walk**:
The central record — one recording or manually-created journey. A walk is either a **Recorded Walk** (captured from a live GPS trace) or a **Manual Walk** (a path the user draws).
_Avoid_: trip, route (a walk *has* a route; it is not one).

**GPS Trace**:
The ordered series of raw GPS observations belonging to a Recorded Walk. The trace, together with walk metadata, is the only walk data that is synchronized to the server.
_Avoid_: track, path.

**Sync**:
Pushing a walk's metadata and GPS Trace to the server (and pulling other devices' walks back). Sync is the mechanism that makes a walk durable and shareable across devices. The server is authoritative **only** for metadata and the GPS Trace — never for anything derived.
_Avoid_: upload, backup.

**Calculation**:
The local, derived work that turns a walk into street coverage: **Map Matching** followed by **Coverage** computation. It is per-device and reproducible — it is never synchronized; each device recomputes it from the synced GPS Trace. This is the slow step.
_Avoid_: processing (overloaded — it has historically meant both Calculation and Sync; do not use it for either).

**Map Matching**:
Snapping a GPS Trace onto the real street network to produce a matched route and the set of streets it traverses. The first half of Calculation.

**Coverage**:
How much of a street (or street section) a walk traversed, expressed as covered length and percentage. Derived locally during Calculation; overlapping walks are accounted for so the same metres are not double-counted. Never synchronized.
_Avoid_: completion, progress.

**Street** / **Street Section**:
A street is a named road derived from OpenStreetMap data; a section is one span of it between two intersections. Both are derived from the map data, not user-created.

## Boundaries

- The server owns **Walk metadata + GPS Trace**. Other devices reference walks by a server-assigned id.
- Each device owns its own **Calculation** results (Map Matching + Coverage). These are local projections of the synced trace and are recomputed independently per device.
- Because Calculation is local and reproducible, **Sync never waits for Calculation**, and a freshly-synced walk on another device shows an estimated distance until that device recomputes.

## Example dialogue

> **Dev:** When I stop a walk, does coverage go up to the server?
> **Expert:** No. Sync only sends the walk's metadata and its GPS Trace. Coverage is part of Calculation — it's derived locally. Every device runs its own Calculation off the trace.
> **Dev:** So if I open the app on my tablet, the walk shows zero coverage at first?
> **Expert:** Right — it's synced but not yet calculated on the tablet. Once the tablet runs Calculation, its coverage fills in. The distance you see before that is just an estimate carried in the metadata.
> **Dev:** And if Calculation is slow, the walk is still safe?
> **Expert:** Yes. Sync doesn't wait for Calculation, so the walk is durable on the server the moment Sync finishes, even if Calculation is still running or stopped.
