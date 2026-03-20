# Request for Proposal (RFP)
## City Walk Tracking System — Android Application (Phase 1)

**Document Version**: 1.1
**Date**: 2026-03-20
**Status**: Draft — one open question remaining (see Section 11)

---

## 1. Overview

We are seeking a qualified development team or individual contractor to design and build an Android mobile application for tracking and managing city walks. The application will allow users to record, visualize, edit, and review their walking routes with a focus on street-level accuracy, battery efficiency, and an intuitive editing interface.

This RFP covers **Phase 1** of a larger city walk tracking system. Future phases may include web interfaces, backend services, social features, or multi-platform support. Proposals should be designed with forward compatibility in mind without over-engineering Phase 1.

---

## 2. Background and Goals

The primary goal of this application is to provide a personal record of which streets a user has walked in a city. The system should capture not just raw GPS tracks, but meaningful data: **which streets were walked** and **what percentage of each street was covered**. This enables users to explore cities systematically and track their progress over time.

Key success criteria:
- A user can start a walk, record it, and review which streets they covered.
- Street coverage data is accurate and actionable.
- The application is practical for daily use (battery-friendly, reliable GPS handling).
- Users can correct routes when GPS or map-matching produces unexpected results.

---

## 3. Target Platform

| Parameter | Requirement                                      |
|-----------|--------------------------------------------------|
| Platform | Android                                          |
| Minimum SDK | Android 16 (API level 35)                        |
| Architecture | ARM64 (primary), x86_64 (emulator support)       |
| Form factor | Smartphone (phone-first; tablet layout optional) |

---

## 4. Functional Requirements

### 4.1 Walk Recording

- **FR-01**: The application shall record a walk session using the device GPS.
- **FR-02**: Geolocation samples shall be taken at a **low frequency** (configurable, suggested default: every 15–30 seconds) to conserve battery life.
- **FR-03**: The application shall run the location service in the background so the user is not required to keep the screen on during a walk.
- **FR-04**: The application shall detect and discard **implausible coordinate jumps** — if the calculated speed between two consecutive samples exceeds a configurable threshold (e.g., > 50 km/h for a pedestrian context), the outlier sample shall be filtered out automatically.
- **FR-05**: The raw GPS trace shall be smoothed and **map-matched to the street network**. The goal is to determine which streets were walked, not to preserve the exact GPS path.
- **FR-06**: The application shall extract and store a **list of streets traversed** during the walk. For each street, coverage shall be reported at two levels:
  - **Street-level**: the percentage of the entire named road (across the whole city) that was covered.
  - **Section-level**: each named road is divided into sections by its intersections with other streets; the application shall report which sections were covered and what percentage of each section was traversed.
  Both levels of coverage shall be recalculated whenever the route is edited.

### 4.2 Route Editing

- **FR-07**: After a walk is recorded (or at any later time), the user shall be able to **edit the route**.
- **FR-08**: Editing is performed by the user **selecting two anchor points** on the existing route and then **specifying a correction waypoint** between them. The application shall **recalculate the route segment between the two anchor points** so that it passes through the correction waypoint, leaving the rest of the route unchanged.
- **FR-09**: Multiple corrections may be applied in a single editing session, each independently replacing a segment between its chosen anchors.
- **FR-10**: After editing, the street coverage list shall be **recalculated** to reflect the corrected route.
- **FR-11**: The user shall be able to undo or discard edits and revert to the last saved state.

### 4.3 Manual Walk Creation

- **FR-12**: The user shall be able to **manually create a walk** without physically walking it — by specifying a start point and an end point on a map.
- **FR-13**: The application shall **automatically generate the shortest walkable route** between the two points using the street network.
- **FR-14**: The generated manual route shall be fully editable using the same waypoint-based editing interface described in FR-07 through FR-11.
- **FR-15**: Street coverage data shall be calculated for manually created walks in the same manner as recorded walks.

### 4.4 Walk Storage and History

- **FR-16**: Completed walks (recorded or manually created) shall be **saved locally** on the device. Each walk record represents **one continuous session**; merging multiple sessions into a single walk record is not supported in Phase 1.
- **FR-17**: The user shall be able to **browse a list of saved walks**, including relevant metadata (date, duration, total distance, number of streets covered).
- **FR-18**: The user shall be able to **open and review** any saved walk at any time.
- **FR-19**: The user shall be able to **delete** saved walks.

### 4.5 Map View and Visualization

- **FR-20**: A map view shall display the current or selected walk's route as an **overlay on the street map**.
- **FR-21**: Streets that have been traversed shall be **visually highlighted** (e.g., a distinct color overlaid on the street).
- **FR-22**: The street coverage percentage shall be accessible from the map view (e.g., as a tap-to-view detail on a highlighted street).
- **FR-23**: The map shall support standard gestures: pan, pinch-to-zoom, and tap for interaction.

---

## 5. Non-Functional Requirements

### 5.1 Battery Efficiency

- **NFR-01**: The background location service shall not cause excessive battery drain. Low-frequency sampling (FR-02) is the primary mechanism; proposals should describe any additional optimizations.
- **NFR-02**: Map rendering and route processing shall not keep the CPU active unnecessarily between user interactions.

### 5.2 Offline Capability

- **NFR-03**: Walk recording shall function fully offline. GPS recording must not depend on a network connection.
- **NFR-04** *(nice-to-have)*: Map tiles for previously visited areas may be cached to support offline map viewing. This is not a hard requirement; proposals that include it will be viewed favorably.
- **NFR-05**: Route smoothing and map-matching may require network access; if unavailable, the raw GPS trace should be stored for later processing.

### 5.3 Data Integrity

- **NFR-06**: Walk data shall not be lost if the application is closed mid-walk. The session shall persist and be recoverable.
- **NFR-07**: Local storage shall use a durable format resistant to data corruption.

### 5.4 Performance

- **NFR-08**: Map rendering and route overlay display shall be responsive; overlays for walks with up to 50 streets shall render within 2 seconds.
- **NFR-09**: Route recalculation after waypoint insertion shall complete within 5 seconds for routes up to 10 km in length under normal network conditions.

### 5.5 Privacy

- **NFR-10**: All walk data shall be stored locally on the device by default. No data shall be transmitted to external servers without explicit user consent.
- **NFR-11**: The application shall comply with Android runtime permission requirements for location access (fine location, background location).

---

## 6. Technical Considerations

Proposals should address the following technical areas. We do not mandate specific choices but expect justification for the approach selected.

### 6.1 Map and Routing Provider

The application requires:
- A street map for display.
- A **routing engine** capable of generating pedestrian walking routes.
- A **map-matching algorithm** to align GPS traces to the street network.

**Constraint**: The solution must use **zero-cost, open-source tooling only**. Commercial map APIs (Google Maps Platform, Mapbox paid tiers, HERE, etc.) and any solution requiring recurring API fees are not permitted.

Required stack elements (exact choices are at the proposer's discretion within this constraint):
- **Map data**: OpenStreetMap (OSM)
- **Map rendering**: e.g., OpenMapTiles, MapLibre GL Android, Mapsforge, or equivalent open-source renderer
- **Routing engine**: e.g., GraphHopper, Valhalla, OSRM, or equivalent; may run on-device or against a self-hosted/community instance
- **Map matching**: must be compatible with the chosen routing engine; proposer must describe the map-matching approach

Proposals must clearly state whether routing and map-matching run **on-device** or against a **remote open-source instance**, and describe the implications for offline use and latency.

### 6.2 Street Coverage Calculation

The proposal must describe the algorithm or approach for all of the following:

- **Street identification**: how named streets are identified from OSM way/relation data.
- **Section decomposition**: how each named street is split into sections at intersections with other streets (or at other topologically significant nodes), and how section identity is maintained across OSM data updates.
- **Coverage computation at section level**: what percentage of each section's length was traversed by the matched route.
- **Coverage rollup at street level**: how section-level coverage is aggregated to report the overall percentage of the entire named road that was covered.
- **Data freshness**: how the application handles OSM data updates that may alter street geometry or intersection topology for previously recorded walks.

### 6.3 GPS Outlier Filtering

The proposal must describe the strategy for detecting and discarding implausible GPS samples (FR-04), including the configurable threshold mechanism.

### 6.4 Local Data Storage

Proposals should specify the local persistence strategy (e.g., Room/SQLite, file-based GeoJSON, etc.) and data format for stored walks.

### 6.5 Background Location

The proposal must describe how background location is handled in compliance with Android 16 background location permission requirements and battery optimization policies (Doze, App Standby).

---

## 7. Deliverables

| # | Deliverable | Description |
|---|-------------|-------------|
| D-01 | Technical Proposal | Architecture overview, technology choices with rationale, team composition |
| D-02 | Project Plan | Milestones, timeline, development phases |
| D-03 | Working Android Application | APK or direct Play Store / sideload delivery meeting all FR/NFR requirements |
| D-04 | Source Code | Full source code in a version-controlled repository (GitHub or equivalent) |
| D-05 | Build Instructions | Documented steps to build the application from source |
| D-06 | User Guide | Brief documentation covering all user-facing features |
| D-07 | QA Report | Test coverage summary covering core functional requirements |

---

## 8. Out of Scope (Phase 1)

The following items are explicitly **not** part of this engagement and should not be included unless the proposal explicitly marks them as optional additions at no cost impact:

- Backend server or cloud infrastructure
- User accounts, authentication, or social features
- Web application or desktop companion app
- iOS or cross-platform version
- Gamification, badges, or achievements
- Integration with fitness tracking platforms (Google Fit, Strava, etc.)
- Paid map tile hosting or commercial map/routing API subscriptions (these are prohibited; see Section 6.1)

---

## 9. Evaluation Criteria

Proposals will be evaluated on the following dimensions:

| Criterion | Weight |
|-----------|--------|
| Technical approach — map-matching and street coverage methodology | 30% |
| Feasibility and realism of the project plan | 20% |
| Relevant experience with Android, mapping, and geospatial features | 20% |
| Battery efficiency strategy | 15% |
| Cost and timeline | 15% |

---

## 10. Proposal Submission Requirements

Proposals must include:

1. **Executive Summary** — brief overview of your proposed approach.
2. **Technical Architecture** — how the application will be structured; which mapping/routing APIs will be used and why.
3. **Map-Matching Strategy** — specific description of how GPS traces will be aligned to streets and how street coverage percentages will be computed.
4. **Battery Efficiency Plan** — specific measures to minimize background battery consumption.
5. **Timeline and Milestones** — proposed delivery schedule with named checkpoints.
6. **Team Composition** — relevant experience of team members.
7. **Cost Estimate** — total fixed-price or time-and-materials estimate with breakdown.
8. **Risk Register** — identified risks and proposed mitigations.
9. **References or Portfolio** — examples of prior Android or geospatial application work.

---

## 11. Open Questions and Resolved Decisions

### 11.1 Resolved Decisions

The following questions have been answered by the client and are now reflected in the requirements above.

| # | Question | Decision |
|---|----------|----------|
| 1 | Routing provider cost model | **Zero-cost / open-source only.** No commercial API spend is permitted. (See Section 6.1) |
| 2 | Offline map tiles | **Nice-to-have.** GPS recording must work offline; offline map viewing is optional but desirable. (See NFR-04) |
| 3 | Route editing scope | **Segment replacement between two anchor points.** The user selects two anchors on the existing route and inserts a correction waypoint between them; only that segment is recalculated. (See FR-08) |
| 4 | Street definition for coverage | **Entire named road, decomposed into sections at intersections.** Coverage is reported both per-section and rolled up to the whole named road. (See FR-06, Section 6.2) |
| 5 | Manual walk route strategy | **Shortest walkable path** between the specified start and end points. (See FR-13) |
| 6 | Multi-session walks | **One continuous session per walk record.** Session merging is out of scope for Phase 1. (See FR-16) |

### 11.2 Remaining Open Question

The following question must be resolved before the RFP is finalized and distributed:

7. **UI language**: Is the application required to support multiple locales (internationalization/localization), or is English only sufficient for Phase 1?

---

## 12. Timeline

| Event | Target Date |
|-------|------------|
| RFP issued | 2026-03-20 |
| Deadline for clarification questions | TBD |
| Proposal submission deadline | TBD |
| Evaluation and selection | TBD |
| Project kickoff | TBD |

---

## 13. Contact

*[Add contact name, email, and preferred communication method here before distributing.]*

---

*This document is a draft (v1.1). One open question remains in Section 11.2 — resolve it before finalizing and distributing the RFP.*
