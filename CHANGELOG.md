# Changelog

## [1.4.0] - 2026-05-09

### Added

**Handle tab — panel color picker**
- New Color row below Theme: 7 preset swatches plus an Auto split-circle (follows theme) and a custom hex input field
- Selected swatch highlighted with a 2.5 dp primary-color ring
- Custom option shows a pencil-icon swatch; tapping toggles an inline `#RRGGBB` input with an Apply button
- Color stored as `panel_color` in SharedPreferences and applied with 248 alpha to both the floating panel and All Apps drawer

**Floating panel — All Apps drawer**
- "All Apps" button at the bottom of the panel opens a centered overlay (90 % × 82 % of screen) with a 4-column alphabetical grid of every installed app
- Live search bar at the top filters the grid as you type; keyboard is dismissed automatically on close
- "Edit" button beside "All Apps" writes a `launch_tab` preference and opens MainActivity on the Apps tab
- Drawer uses the same panel color and effective-luminance color tokens as the main panel

**Control tab — quick-controls toggles**
- Master switch enables or disables the entire quick-controls strip in the floating panel
- Four individual switches (Torch, Auto-rotate, Auto-brightness, Ringer mode) shown when the master is on
- Rows with all controls disabled are omitted from the panel entirely to avoid empty space

### Changed

**Panel color — adaptive content colors**
- `effectiveLight` is derived from the HSV brightness of the resolved panel color (V > 0.5 = light) rather than the theme setting
- All panel content tokens (tile background, active tile, label color, drag indicator, empty state) switch between light and dark variants based on `effectiveLight`, so dark custom colors get light text and vice versa
- Quick-controls strip background (`controlsBg`) is derived from the panel color via a +0.15 / −0.12 HSV brightness shift for a tonal distinction

**Tab slide animation**
- Replaced single-phase 220 ms linear slide with a two-phase animation: 80 ms ease-in exit followed by 160 ms ease-out entry
- `setActiveTab` is called between phases so the content switch happens while both views are off-screen

### Fixed

**Slider fill alignment**
- Fill width now computed as `thumbDiameter + ratio × (trackWidth − thumbDiameter)` using an `onLayout` measurement, so the thumb always sits visually inside the filled end of the track rather than at the boundary between filled and unfilled

## [1.3.0] - 2026-05-08

### Changed

**Floating panel — consolidated single-page layout**
- Removed the second "control center" tab from the floating panel
- Quick controls (Torch, Auto-rotate, Auto-brightness, Ringer mode) moved to a strip at the top of the favorites panel with a distinct M3 `controlsBg` surface and 16 dp rounded corners
- Favorites app grid scrolls below the controls strip in a single unified panel
- Removed status-bar time/battery polling, DND tile, screen-timeout tile, pager dots, and swipe-between-pages gesture from the overlay (~300 lines removed)

**Main app — swipe tab navigation**
- Tabs (Handle, Behavior, Control, Apps) can now be switched by swiping left/right
- Gesture requires a clear horizontal intent (|ΔX| > |ΔY| × 1.5) and a minimum 40 dp displacement to avoid triggering during vertical scrolls

### Fixed

**Code quality improvements**
- Icon encoding now uses an `LruCache<String, String>(200)` keyed by package name — icons are encoded once per process lifetime instead of on every `getInstalledApps` call
- `getInstalledApps` moved from a raw `Thread` to `Executors.newSingleThreadExecutor()` to serialize concurrent calls
- Silent `catch` blocks replaced with `Log.e` and user-facing `Alert` dialogs in both the native module and React Native screens
- `dragMode: Boolean` replaced with a `DragState` enum in `SidebarOverlayService` for explicit state tracking
- Removed unused `READ_EXTERNAL_STORAGE` and `WRITE_EXTERNAL_STORAGE` permissions from `AndroidManifest.xml`

**Battery consumption**
- Fullscreen-detection poll interval slowed from 500 ms to 2000 ms
- Polling stops entirely when the screen is off (`ACTION_SCREEN_OFF`) and resumes on unlock (`ACTION_USER_PRESENT`)
- `vibrationEnabled` flag cached in memory to avoid a `SharedPreferences` read on every swipe event

## [1.2.0] - 2026-05-07

### Added

**Material Expressive 3 redesign**
- New color palette using M3 tokens: purple primary (#6750A4 light / #D0BCFF dark), surface containers, error containers
- M3 Navigation Bar with pill indicator (primaryContainer) on the active tab
- M3 Segmented Button: single outlined group with secondaryContainer fill on active segment
- M3 Filled Button (20 dp radius) for save actions; Tonal Button for grant/permission actions
- Custom filled slider track: 4 dp primary-colored overlay behind the native slider with transparent track
- M3 Search Bar (28 dp pill radius) in the Apps tab
- Cards at 16–20 dp radius; service toggle card at 20 dp

**Control tab improvements**
- "Display over other apps" permission row added (was only shown as a banner)
- "X / 3 permissions granted" summary count at the top
- "Open App Settings" button that opens the system app settings page directly

**Floating panel animation improvements**
- Open: `OvershootInterpolator(1.1f)` + scale 0.94→1, 320 ms — spring-in effect
- Close: `AccelerateInterpolator(1.5f)` + scale 1→0.94, 200 ms
- Page swipe: `DecelerateInterpolator(1.5f)`, 260 ms
- App icon press: scale to 0.85 with `OvershootInterpolator(2f)` spring-back
- Control tile press: scale to 0.90 with `OvershootInterpolator(1.8f)` spring-back

### Changed
- App name updated to "Floating Panel - Sidebar" (launcher label, notification)

## [1.1.0] - 2026-05-07

### Added

**Bottom tab navigation**
- Four tabs: Handle, Behavior, Control, Apps — replaces the single scrolling settings screen

**Control panel (overlay)**
- Quick-settings tile grid accessible from the sidebar panel
- Tiles: Torch, Do Not Disturb, Auto-rotate, Auto-brightness, Ringer mode, Screen timeout
- Ringer mode cycles Ring → Vibrate → Silent (skips Silent if DND permission is not granted)
- Screen timeout toggles between 30 seconds and 30 minutes
- Status bar shows current time (left) and battery percentage (right); updates every 10 s while panel is open
- Control tab in app shows permission status for DND and Write Settings with one-tap grant buttons

**Apps tab — split pane**
- Left panel: 3-column grid of all installed apps with search; tap to add/remove from favorites
- Right panel: favorites list with drag-to-reorder via long-press on the handle icon
- Save button commits order and selection to the overlay service

**Behavior tab**
- Auto-hide in fullscreen, show app labels, vibration feedback toggles
- Swipe sensitivity slider

### Fixed
- Pull-tab touch target too small for narrow pills: transparent 24 dp minimum-width container separates hit area from visual pill width
- Favorites panel collapsed to zero height in split pane due to `style` vs `containerStyle` on DraggableFlatList

## [1.0.0] - 2026-05-06

### Added

**Android overlay service**
- Floating pull-tab handle on the screen edge (left or right)
- Swipe gesture on handle to open/close the panel (excluded from OS back gesture)
- Handle repositioning via long-press on the drag indicator inside the panel
- Favorites panel with 2-column app grid; tap any app to launch it
- Smooth slide + fade animation on panel open/close
- Foreground service persists across background kills
- Auto-restart on device boot via `BootReceiver`

**Handle customisation**
- Side (left/right)
- Vertical position (slider, 0–1)
- Height (slider, 40–200 dp)
- Width (slider, 6–40 dp)
- Opacity (slider, 0.1–1.0)
- Theme (dark/light) — applies to both handle and panel

**React Native config UI**
- Permission request flow for `SYSTEM_ALERT_WINDOW`
- Service on/off toggle
- Handle settings with live sliders
- Favorites manager — search, toggle apps, save on Done

### Fixed
- `libc++_shared.so` missing from `arm64-v8a` APK causing crash on launch with New Architecture enabled
