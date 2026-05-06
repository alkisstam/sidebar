# Changelog

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
