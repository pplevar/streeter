# Streeter — Design Requirements

**App type:** Android (Material You / Material 3)  
**Document purpose:** Provide a designer with everything needed to create high-fidelity screens and a design system

---

## 1. App Overview

Streeter is a walking-tracker app focused on street coverage. Users record or plan walks, and the app shows which city streets they have covered and by how much. All processing happens on-device — no data leaves the phone.

**Core value proposition:** "Walk every street in your city. Track your progress, street by street."

**Key concepts the designer must understand:**

| Concept | Explanation |
|---|---|
| Walk | A single recorded or manually planned walking session |
| Map matching | Background process that snaps raw GPS points to the road network |
| Street coverage | For each street segment walked, the app calculates what percentage was covered |
| Route edit | User can correct a walk's matched route by placing waypoints on the map |
| Manual walk | User draws a planned route (start + end point) without recording GPS |

---

## 2. Navigation Structure

```
Privacy Disclosure (first-launch only)
    ↓
Home
├── Recording
│   └── (stop) → Walk Detail
├── History
│   └── (tap walk) → Walk Detail
│                       └── (edit) → Route Edit
├── Create Manual Walk
│   └── (generate) → Route Edit
│                       └── (save) → Walk Detail
└── Settings
```

Deep links supported: `streeter://walk/{walkId}`, `streeter://walk/{walkId}/edit`

---

## 3. Design System Guidelines

### 3.1 Theming

- Material 3 (Material You) with **dynamic color** — the scheme adapts to the user's wallpaper via `DynamicColorScheme`.
- The designer should define a seed color palette in addition to dynamic colors for use in emulators and on API < 31.
- Suggested seed color: **blue-green** family (the app is about streets and nature).

### 3.2 Hardcoded Accent Colors

Some colors are not part of the Material scheme and must be explicitly designed:

| Token | Hex | Usage |
|---|---|---|
| `route-blue` | `#3B82F6` | Walked route line on map |
| `preview-orange` | `#F59E0B` | Correction preview line on map (in-progress edit) |
| `pin-start` | `#4CAF50` (green) | Start pin on Manual Create map |
| `pin-end` | `#F44336` (red) | End pin on Manual Create map |

### 3.3 Typography

Standard Material 3 type scale. Key text styles used in the app:

| Style | Usage |
|---|---|
| `headlineSmall` | Screen section headers, walk title |
| `titleMedium` | Card titles, walk date in list |
| `bodyMedium` | Body text, instructions |
| `labelSmall` | Badges (Manual, Processing), secondary metadata |
| `displaySmall` | Large metric values (distance, duration) |

### 3.4 Spacing

Standard 8dp grid. Common values: 8dp, 12dp, 16dp, 24dp.

### 3.5 Iconography

Material Icons (filled + outlined variants):

| Icon | Usage |
|---|---|
| `Settings` | Home TopAppBar action |
| `ArrowBack` | Back button on all secondary screens |
| `Edit` | Walk Detail TopAppBar action |
| `Delete` | Walk Detail TopAppBar action |
| `Undo` | Route Edit TopAppBar action |
| `Check` (Save) | Route Edit TopAppBar action |
| `Close` | Discard overlay actions |
| `PlayArrow` / `Stop` | Start / Stop walk buttons |
| `Refresh` | Settings — refresh map data |

---

## 4. Screen Specifications

---

### 4.1 Privacy Disclosure

**When shown:** First launch only. Replaced by Home on subsequent launches.

**Purpose:** Inform the user about local-only data handling before requesting any permissions.

**Layout:** Centered scrollable column, no TopAppBar.

**Content:**

| Section | Title | Body |
|---|---|---|
| 1 | Your data stays on your device | All processing (GPS, route matching, street coverage) happens locally. Nothing is sent to any server. |
| 2 | Background location | GPS is only recorded when you explicitly start a walk. No background tracking otherwise. |
| 3 | What is collected | GPS coordinates, timestamps, and accuracy values, stored in a local database. |
| 4 | Exporting data | Your walk data only leaves the device through an explicit export action you initiate. |

**CTA:** Single large primary button "Got it — let's walk" at the bottom, sticky or after scroll.

**Design notes:**
- Calming, trust-building tone — avoid legal/scary language in visual hierarchy
- Can use an illustration or icon for each section

---

### 4.2 Home

**Entry point** for all main actions.

**TopAppBar:**
- Title: "Streeter"
- Trailing action: Settings icon → Settings screen

**Body — vertical button stack, centered:**

| Button | Style | Label | Condition |
|---|---|---|---|
| Start Walk | Primary, large | "Start Walk" | No active walk |
| Resume Walk | Primary, large, **destructive red** | "Resume Walk" | Active walk in progress |
| View History | Outlined | "View History" | Always shown |
| Create Manual Walk | Outlined | "Create Manual Walk" | Always shown |

**Design notes:**
- "Resume Walk" replaces "Start Walk" when a walk is already recording — it must be visually distinct (red) to signal an active state
- Buttons should feel prominent; this is a launcher-style screen with minimal chrome

---

### 4.3 Recording

**Purpose:** Show the live map while a walk is being recorded.

**Layout:** Full-screen map, bottom sheet card, no background content.

**Map:**
- Full screen, auto-follows user location
- Draws a **blue line** connecting recorded GPS points as they arrive
- Shows current location indicator (pulsing blue dot or similar)

**Bottom Card (always visible, not dismissible):**
- GPS point count: e.g. "42 points recorded"
- Single action: **"Stop Walk"** — large red button

**Design notes:**
- Minimal UI — the map is primary; the user needs spatial awareness while moving
- No TopAppBar; consider a small translucent back/cancel affordance if needed
- Consider showing distance and elapsed time in addition to point count

---

### 4.4 History

**Purpose:** Browse all past walks.

**TopAppBar:**
- Leading: Back arrow
- Title: "History"
- Trailing: Sort dropdown (icon or text trigger)
  - Sort options: "Newest first", "Longest first"

**Body:** Scrollable list of Walk Cards.

**Walk Card layout:**

```
┌──────────────────────────────────────────────┐
│  [Walk Title / Date]         [Manual] [badge] │
│  ──────────────────────────────────────────── │
│  📍 2.4 km          ⏱ 42 min                  │
│                                  [Processing] │
└──────────────────────────────────────────────┘
```

**Walk Card fields:**

| Field | Value source | Format |
|---|---|---|
| Title | `Walk.title` or formatted date | "Mon, Apr 21" |
| Distance | `Walk.distanceM` | `< 1000m` → "850 m"; `≥ 1000m` → "2.4 km" |
| Duration | `Walk.durationMs` | `< 1h` → "42 min"; `≥ 1h` → "1h 23m" |
| Manual badge | `Walk.source == MANUAL` | Chip/label "Manual" |
| Processing badge | `Walk.status == PENDING_MATCH` | Chip/label "Processing" |

**Empty state:** Show a friendly empty state illustration + "No walks yet. Start your first walk!" with a CTA.

**Design notes:**
- Cards are tappable — entire card navigates to Walk Detail
- Consider subtle dividers or card elevation to separate items
- Processing badge should be visually distinct (e.g. animated or accent-colored)

---

### 4.5 Walk Detail

**Purpose:** Show full details of a single walk, including street coverage and processing state.

**TopAppBar:**
- Leading: Back arrow
- Title: Walk title or date
- Trailing actions (shown when not RECORDING):
  - Edit icon → Route Edit screen
  - Delete icon → Delete confirmation dialog

**Body — scrollable:**

#### Section 1: Walk Header Card

| Field | Format | Notes |
|---|---|---|
| Title / Date | "Mon, Apr 21" | Large headline |
| Distance | "2.4 km" | Metric chip or row |
| Duration | "42 min" | Metric chip or row |
| Manual badge | Chip "Manual" | Only if source == MANUAL |

#### Section 2: Processing Banner (`PENDING_MATCH` status only)

Shown while map matching is running:

```
┌─────────────────────────────────────────────┐
│  🔄 Matching route…  37%                     │
│  ▓▓▓▓▓▓▓▓░░░░░░░░   "Snapping GPS points"   │
└─────────────────────────────────────────────┘
```

Fields:
- Progress percentage (0–100)
- Current step label (e.g. "Snapping GPS points", "Computing coverage")
- Progress bar (linear)

#### Section 2 (alt): Recalculate Button (`COMPLETED` status)

- Text button or outlined button: "Recalculate Route"
- Triggers re-processing (sets walk to PENDING_MATCH, re-queues worker)

#### Section 3: Streets Covered

Header: "Streets Covered"

Streets grouped into three tiers:

| Tier | Condition | Visual treatment |
|---|---|---|
| Full coverage | `coveragePct == 100%` | Green accent |
| Partial coverage | `50% ≤ coveragePct < 100%` | Yellow/amber accent |
| Low coverage | `coveragePct < 50%` | Red or muted accent |

Each street row:
```
Street Name                              87%
```

**Dialogs:**

Delete confirmation:
- Title: "Delete walk?"
- Body: "This action cannot be undone."
- Actions: "Cancel" (outlined) / "Delete" (destructive red)

**Error handling:** Snackbar for non-critical errors (e.g. recalculate failed).

**Design notes:**
- Walk Detail is the richest informational screen — consider giving it a more data-dense layout
- Street coverage list can be long; consider grouping with expandable sections

---

### 4.6 Route Edit

**Purpose:** Allow the user to manually correct the map-matched route by placing correction waypoints.

**Layout:** Full-screen map with floating top card, bottom bar, and overlays.

**TopAppBar:**
- Leading: Back arrow (with unsaved-changes guard)
- Title: "Edit Route" — if corrections exist: "Edit Route (3)"
- Trailing: Undo icon (active only if corrections exist), Save icon (checkmark)

**Map:**
- Full-screen; camera fits to route bounds on load
- **Blue line** = matched/current route geometry
- **Orange line** = in-progress correction preview (shown during PREVIEW mode)
- Map is tappable during anchor/waypoint selection modes
- Tapped points are marked (anchors on route, waypoint off-route)

**Edit State Machine:**

The bottom bar content changes per state:

| State | Top card instruction | Bottom bar content |
|---|---|---|
| `IDLE` | — | "Add Correction" button |
| `SELECT_ANCHOR_1` | "Tap the route: first anchor point" | Cancel button |
| `SELECT_ANCHOR_2` | "Tap the route: second anchor point" | Cancel button |
| `SELECT_WAYPOINT` | "Tap the map: correction waypoint" | Cancel button |
| `RECALCULATING` | "Calculating…" | Spinner only |
| `PREVIEW` | "Preview" | "Discard" + "Confirm" buttons |
| `SAVING` | — | Full-screen overlay with spinner + "Saving…" |

**Overlays:**

Saving/Recalculating overlay (full screen, semi-transparent):
```
        ⟳
    Recalculating…
```

**Dialogs:**

Discard unsaved edits (shown on back when `hasUnsavedChanges == true`):
- Title: "Discard unsaved edits?"
- Body: "Your corrections will be lost."
- Actions: "Keep editing" / "Discard"

**Design notes:**
- This is the most complex interaction screen — the state machine must be clearly communicated via visual feedback
- Anchors (on route) and waypoint (off route) should use distinct markers (shape, color)
- Orange preview line must contrast clearly against the blue route line
- The top instruction card should be prominent but not obstruct the map

---

### 4.7 Create Manual Walk

**Purpose:** Let the user set a start and end point on the map; the app generates a route between them.

**Layout:** Full-screen map with centered overlay pin, bottom chip bar, top instruction card.

**TopAppBar:**
- Leading: Back arrow
- Title: "Create Manual Walk"

**Center Pin Overlay (always centered on screen):**
- Visible only when in SET_START or SET_END step
- Drops to map center as the camera moves
- **Green pin** during SET_START
- **Red pin** during SET_END

**Top Instruction Card:**

| Step | Instruction |
|---|---|
| `SET_START` | "Move map to set start point" |
| `SET_END` | "Move map to set end point" |
| `GENERATING` | "Generating route…" |

**Bottom BottomAppBar — 3 chips:**

| Chip | State: SET_START | State: SET_END |
|---|---|---|
| Set Start | Selected (green tint) | "Start: set" (inactive, shows confirmed) |
| Set End | Unselected | Selected (red tint) |
| Generate Route | Disabled | Enabled when both pins set |

**Generating Overlay:**

Full-screen semi-transparent overlay:
```
        ⟳
    Generating route…
```

**Design notes:**
- The center pin metaphor (map moves under pin) is a standard pattern — ensure the pin visually "sticks" to center
- The chip bar serves double duty as status display and action trigger
- "Generate Route" should only be tappable when both pins are confirmed

---

### 4.8 Settings

**Purpose:** Configure GPS recording sensitivity and data management.

**TopAppBar:**
- Leading: Back arrow
- Title: "Settings"

**Sections:**

#### GPS Recording

| Setting | UI | Range | Default |
|---|---|---|---|
| GPS sample interval | Labeled slider | 5–60 seconds | 20 s |
| Max speed filter | Labeled slider | 20–100 km/h | 50 km/h |

Labels should show current value next to slider: "GPS sample interval: 20 s"

#### Map Data

| Item | Description | Action |
|---|---|---|
| Refresh map data | "Re-indexes OSM street data from bundled assets." | "Refresh" button (shows spinner while loading) |

#### Data

| Item | Description | Action |
|---|---|---|
| Clear all data | "Permanently deletes all walks and coverage data." | Red "Clear" button |

**Dialog — Clear all data:**
- Title: "Clear all data?"
- Body: "All walks, routes, and coverage data will be permanently deleted. This cannot be undone."
- Actions: "Cancel" / "Clear" (destructive red)

---

## 5. User Flows (Summary)

### 5.1 Record a Walk

```
Home → [Start Walk]
    → Permission prompt (location, if not granted)
    → Recording screen (live map + GPS)
    → [Stop Walk]
    → Walk Detail (status: Processing → Completed)
```

### 5.2 View Walk History

```
Home → [View History]
    → History screen (list)
    → [Tap walk card]
    → Walk Detail
```

### 5.3 Edit a Matched Route

```
Walk Detail → [Edit icon]
    → Route Edit screen
    → [Add Correction]
    → Tap route (anchor 1)
    → Tap route (anchor 2)
    → Tap map (waypoint)
    → Preview (orange line shown)
    → [Confirm] or [Discard]
    → Repeat or [Save]
    → Walk Detail (status: Processing again → Completed)
```

### 5.4 Create a Manual Walk

```
Home → [Create Manual Walk]
    → Manual Create screen
    → [Set Start chip] → move map → pin confirms start
    → [Set End chip] → move map → pin confirms end
    → [Generate Route]
    → Route Edit screen (optional corrections)
    → [Save]
    → Walk Detail
```

### 5.5 Delete a Walk

```
Walk Detail → [Delete icon]
    → Confirmation dialog
    → [Delete]
    → Navigate back to History
```

---

## 6. States and Edge Cases to Design For

| Screen | State | Required design |
|---|---|---|
| History | Empty (no walks) | Empty state illustration + CTA |
| Walk Detail | `PENDING_MATCH` | Processing banner with progress bar |
| Walk Detail | `COMPLETED` | Recalculate button, street coverage list |
| Walk Detail | `RECORDING` | No edit/delete icons (walk is live) |
| Walk Detail | Streets covered empty | "No streets matched yet" placeholder |
| Route Edit | No corrections | Undo button disabled |
| Route Edit | Unsaved changes on back | Discard confirmation dialog |
| Route Edit | Saving in progress | Full-screen saving overlay |
| Manual Create | Both pins not set | Generate Route chip disabled |
| Manual Create | Generating | Spinner overlay, chips disabled |
| Settings | Refreshing map data | Spinner replaces Refresh button |
| Home | Active walk in progress | "Resume Walk" replaces "Start Walk" (red) |

---

## 7. Permissions Flow

Location permission is required before starting a walk. The OS dialog is system-controlled, but the app should:

1. Explain why location is needed before triggering the request (rationale screen or in-context explanation)
2. Handle the case where permission is denied gracefully (show an explanation + link to Settings)

**Required permissions:**
- `ACCESS_FINE_LOCATION` (GPS recording)
- `POST_NOTIFICATIONS` (foreground service notification during recording) — Android 13+

---

## 8. Map Considerations

The map is a central component appearing in 3 screens (Recording, Route Edit, Manual Create). Design notes:

- Map style should be a **clean, minimal street map** — ideally a light/neutral style that does not compete with the route overlays
- Consider a **dark map style** as an alternative for night walking
- The blue route line (`#3B82F6`) must be legible on both light and dark map styles — consider adding a white outline/shadow

**Map markers needed (custom assets required):**

| Asset | Description |
|---|---|
| Anchor marker | Placed on route during edit; two per correction |
| Waypoint marker | Placed off-route during edit; one per correction |
| Start pin | Green, for Manual Create |
| End pin | Red, for Manual Create |
| Current location | Pulsing blue dot with accuracy circle |

---

## 9. Foreground Service Notification

When a walk is recording, a persistent notification is displayed in the Android notification shade. This notification keeps the service alive and informs the user.

**Notification design (standard Android NotificationCompat):**

| Field | Content |
|---|---|
| Small icon | App icon (monochrome) |
| Title | "Recording walk" |
| Text | "Walk in progress — tap to open" |
| Action | Tap → opens Recording screen |
| Dismissible | No (ongoing notification) |

---

## 10. Deliverables Requested

1. **Design system file** — colors, typography, spacing, iconography, component library
2. **High-fidelity screens** — all 8 screens listed in Section 4, in light and dark variants
3. **State variants** — all states listed in Section 6 (empty, loading, processing, error)
4. **Map assets** — custom markers and pin assets as SVG/vector
5. **Flow diagrams** — annotated flows for the 5 user journeys in Section 5
6. **Component specs** — spacing, sizing, and interaction notes for all custom components
