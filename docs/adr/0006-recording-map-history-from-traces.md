# The Recording Map's History Layer Draws Traces, Not Coverage

While recording, the useful question is "have I already walked this street?" — and the recording map could answer it from either of two sources. **Coverage** is the app's own answer: street-snapped, deduplicated, the same numbers the rest of the app reports. **Raw GPS Traces** are the observations themselves: unsnapped, drifting off the street by whatever the receiver was wrong by that day.

The recording map's history layer draws **Traces**. It renders every walk except the one in progress as one grey-blue `LineString` per walk, below the live route.

## Why Traces

- **No Calculation in the way.** Traces exist the instant a walk stops. Coverage only exists after Map Matching and Coverage computation have run — a slow, retrying background step (ADR 0001 deliberately decouples Sync from it). A walk you finished ten minutes ago would be missing from a Coverage-based layer, which is exactly the walk you are most likely to be checking against.
- **`PENDING_MATCH` walks are covered.** Same reason, stated as a state: walks awaiting Calculation, or whose Calculation failed and is still retrying, still show up.
- **Synced-from-peer walks are covered.** The Trace is what Sync carries; a walk pulled from another device draws immediately, before this device recomputes anything.
- **Nothing to compute.** One indexed query, grouped in memory. No geometry assembly from section-level records.

## The Accepted Trade-off

Raw traces drift off-street. The history layer will visibly disagree with the app's Coverage numbers: a street the user walked may show a line running through the gardens beside it, and a street shown as touched by a wobbling trace may be at 0% Coverage. **Two different truths on one screen, deliberately.** The recording map is a rough "roughly where I have been" aid, not a coverage report; screens that report Coverage keep reporting Coverage.

This ADR exists so that disagreement is not later mistaken for a bug and "fixed" by switching the layer to Coverage. Changing it means giving up the four properties above — most importantly, freshly-finished and `PENDING_MATCH` walks vanishing from the map.

## Considered Options

- **Coverage-derived geometry.** Rejected per the above: correct-looking but blind to any walk that has not finished Calculation, which includes the most recent one.
- **Matched routes** (Map Matching output, before Coverage). Rejected for the same freshness reason — it is the first half of the same slow step — and it adds a second geometry source with no dedupe across walks.
- **A show/hide toggle** so the user picks. Rejected as UI for a decision the user has no basis to make; the layer is subordinate enough to stay always on.

## Consequences

- History is loaded **once, at `RecordingViewModel` init**, not observed. Past walks cannot change while the recording screen is open, so a live `Flow` would only re-query on every GPS batch insert.
- **The in-progress walk is excluded** by id, since the live route layer already draws it at full strength. On resume, the resumed walk's id is the excluded one.
- **Each walk is its own `LineString`.** A single line across walks would draw a straight segment from the end of one walk to the start of the next.
- **Filtered points are dropped**, matching the live route layer, so `GpsOutlierFilter`'s rejects do not reappear as history.
- **`DELETED` walks are excluded.** Deleting a synced walk leaves a tombstone row — points included — until the server confirms, which offline can be days (ADR 0003). The history query joins `walks` and skips those, so a deleted walk leaves the map at once, like its Coverage does.
- **Manual walks are included.** They are excluded from Map Matching but their drawn points are still a Trace.
- **Volume is unbounded and unpaged.** At the current 20s sampling interval, ~36k points across 200 hour-long walks — fine. Denser sampling or a few hundred more walks makes decimation or viewport-bounded loading necessary; neither is built.
