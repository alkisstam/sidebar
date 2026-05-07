# Changelog

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
