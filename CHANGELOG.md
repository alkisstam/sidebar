# Changelog

## [2.0.1] - 2026-05-23

### Added

- Recent apps section at the top of the panel: when enabled, the first two rows show the four most recently used apps, separated from favorites by a subtle divider; toggle in Behavior tab (requires Usage Access permission)

### Changed

- Settings now apply immediately on change (600 ms debounce) — Save buttons removed from Handle, Behavior, and Control tabs

### Removed

- Volume slider removed from the panel and Control tab (was non-functional)

## [2.0.0] - 2026-05-20

### Changed

- Package renamed from `com.anonymous.sidebar` to `com.alkisstam.sidebar`
- Material You applied throughout — settings screen, sidebar panel, and quick controls now use the Material3 theme with dynamic wallpaper colours on Android 12+
- Sidebar panel width reduced to 168dp for a more compact layout; app icon grid padding tightened
- Quick controls rearranged to a maximum of 2 tiles per row (Torch/Rotate, Brightness/Ringer, Share/Power, QR/DND)
- Handle tab colour presets replaced: 4 dark presets (navy, purple, teal, warm brown) and 4 light presets (lavender, sky blue, mint, peach) in place of the previous 7 mixed swatches

### Removed

- Freeform window launch option removed from the app long-press context menu

## [1.9.2] - 2026-05-15

### Fixed

- `getPillSettings` now falls back to the stored `PILL_THEME` value when `THEME_CHOICE` has never been written (existing installs), so upgrading users with an explicit dark or light theme are not silently reset to System
- Theme button press now keeps `pill.themeChoice` in state in sync with the context `themeChoice`; previously `pill.themeChoice` stayed at the loaded value regardless of user interaction
- Sidebar handle and panel now update immediately when the OS dark/light mode changes while System theme is active — `SidebarOverlayService` resolves the theme from `resources.configuration` at runtime rather than reading the stale `PILL_THEME` pref, and `onConfigurationChanged` triggers a handle rebuild on every system theme switch

## [1.9.1] - 2026-05-14

### Fixed

- `saveHandle` now resolves `pill.theme` from the live system scheme at save time when `themeChoice` is `system`, preventing a stale theme being written to `PILL_THEME` if the system switched dark/light after the button was last pressed
- `DEFAULT_PILL` was missing the required `themeChoice` field (TypeScript type error)
- `ThemeContext` default value for `themeChoice` corrected from `'dark'` to `'system'` to match the provider's initial state
- Removed `ActivityManager.supportsMultiWindow(Context)` from the freeform detection gate — it returns `true` on any split-screen-capable device, causing the Freeform menu item to appear and immediately fail on devices that don't support freeform windows

## [1.9.0] - 2026-05-13

### Added

**Behavior tab — pill gestures overhaul**
- New "Pill gestures" master toggle enables or disables all handle gestures; when off, swipe-in always opens the panel regardless of other gesture settings
- New "Swipe in" gesture slot (formerly the hardcoded panel-open swipe); configurable to the same five actions as the other slots: Off, Panel, Notifications, Quick Settings, All Apps — defaults to Panel
- All four gesture rows (Swipe in, Swipe up, Swipe down, Double tap) now use an inline popup/accordion picker instead of the previous segmented button layout; tapping a row expands a rounded card listing the five options with a checkmark on the active one

**Handle tab — Follow System theme**
- New "System" option added to the Theme segmented control alongside Dark and Light
- When System is selected, the app UI and overlay panel theme follow the device's current dark/light mode and update automatically when the system switches
- The resolved theme choice (`light` / `dark` / `system`) is persisted in SharedPreferences (`theme_choice`) so the selection survives app restarts

**Floating panel — position follows handle**
- Panel now opens vertically centred on the handle's position rather than always at the centre of the screen
- Position is clamped so the panel never extends beyond the top or bottom screen edge

**Freeform launch — improved detection**
- Added `ActivityManager.supportsMultiWindow(Context)` as a third detection path alongside `FEATURE_FREEFORM_WINDOW_MANAGEMENT` and the `enable_freeform_support` developer setting, so the Freeform option appears on devices that support it without requiring the developer option to be manually re-enabled

### Changed

**Tab slide animation**
- Exit phase shortened from 80 ms to 55 ms and easing changed from `Easing.in(Quad)` to `Easing.in(Cubic)` for a snappier departure
- Enter phase now uses `Easing.out(Back(1.4))` (subtle overshoot) and enters from 180 px instead of 240 px, making content feel like it lands rather than stops

**Floating panel open animation**
- Duration reduced from 260 ms to 220 ms
- Interpolator changed from `OvershootInterpolator(1.1f)` to `DecelerateInterpolator(2.5f)` for a weighted deceleration feel
- Opening scale changed to asymmetric 0.92 × 0.96 (narrower horizontally, matching the slide direction)

**Floating panel close animation**
- Duration reduced from 180 ms to 160 ms
- Interpolator tightened to `AccelerateInterpolator(2.2f)`
- Scale animation removed on close; only translation + fade, producing a clean snap-away

**Quick Controls — expandable accordion**
- "Quick controls" row in the Control tab now acts as an expand/collapse header with a rotating chevron; individual control toggles are hidden inside the accordion body and revealed on tap

**Navigation bar — active tab indicator**
- Fixed rounded corners disappearing from the active tab pill after the first tab change on Android
- Base style now includes `backgroundColor: transparent` and active style repeats `borderRadius: 26` to ensure Android's background drawable is always rounded regardless of style-diff order

### Fixed

- Updated color presets 4 and 6 (`#36043f` → `#084f36`, `#a9eede` → `#c2eea9`)

## [1.8.0] - 2026-05-12

### Added

**Quick controls — Power, QR scanner, and DND tiles**
- New Power tile opens the system power menu via `AccessibilityService.GLOBAL_ACTION_POWER_DIALOG`; requires enabling Sidebar in Settings → Accessibility once — if not yet enabled, tapping opens Accessibility settings directly
- New QR scanner tile launches a QR scanning activity via intent chain (ZXing URI → Google Lens → OPPO camera → system camera fallback)
- New Do Not Disturb tile toggles between `INTERRUPTION_FILTER_ALL` and `INTERRUPTION_FILTER_NONE`; tapping without the DND permission granted opens Notification Policy settings
- All three appear in a new third tile row in the controls strip, each off by default; toggles added to the Control tab

**Quick controls — Quick Share tile**
- New optional Quick Share tile in the controls strip (off by default); toggle it in the Behavior tab
- Tapping opens Quick Share receive mode (`com.google.android.gms.RECEIVE_NEARBY`) — makes the device discoverable to receive files from nearby devices
- Falls back through the unified sharing UI, explicit GMS activity, and Nearby Sharing settings in order


## [1.7.0] - 2026-05-11

### Added

**Quick controls — brightness and volume sliders**
- Brightness and volume can each be added as sliders inside the controls strip; both are off by default
- Brightness writes to `Settings.System.SCREEN_BRIGHTNESS` (requires WRITE_SETTINGS); volume uses `AudioManager.STREAM_MUSIC`
- When enabled, sliders appear at the top of the controls strip above the tile rows
- Slider style matches the main app: 10 dp pill track, purple fill, white thumb

**Handle — auto color**
- Auto mode now maps to near-black (#1A1A1A) on dark theme and near-white (#F5F5F5) on light theme
- Preset and custom colors apply directly to the handle (pill)

### Changed

**Controls strip — always collapsed by default**
- Strip is always rendered collapsed regardless of how many rows are present; swipe up to expand, swipe down (anywhere over the strip, including over tile icons) to collapse
- Drag indicator doubled in size (64 × 8 dp); "Controls" label underneath is 16 sp and centered
- Strip background uses a distinct tonal color derived from the panel color (HSV ±0.18–0.22 shift)

**Controls strip — sliders always on top**
- Brightness and volume sliders are inserted above tile rows regardless of their order in the settings screen

**All Apps / Edit visibility**
- All Apps and Edit tiles are hidden when the main quick-controls toggle is disabled

**Controls position**
- Quick controls position option removed; strip is now always at the bottom of the panel

### Removed

- Panel drag indicator and drag-mode functionality (long-press to reposition pill from within the panel)
- Quick controls position setting from the Behavior tab

## [1.6.0] - 2026-05-10

### Added

**Floating window launch**
- Long-pressing an app now shows a Freeform option alongside Open in the context menu
- Launches the app in a resizable floating window centered at 75 % × 65 % of screen using `WINDOWING_MODE_FREEFORM` via `HiddenApiBypass`
- Tapping Freeform closes the sidebar panel before launching
- Option only appears on devices where freeform is available (`FEATURE_FREEFORM_WINDOW_MANAGEMENT` or `enable_freeform_support` developer setting)

### Changed

- `LSPosed/AndroidHiddenApiBypass 4.3` added as a dependency; initialized in `Application.onCreate` to exempt all hidden APIs on Android 9+, making hidden-API reflection reliable across Android versions without native code

### Removed

- Split Screen option from the long-press context menu

## [1.5.0] - 2026-05-10

### Added

**Pill gestures**
- Three new gesture slots on the pull-tab handle: swipe up, swipe down, double tap
- Each slot is independently configurable to one of five actions: Off, Open Panel, Notifications, Quick Settings, All Apps
- Default: swipe down → expand notifications; swipe up and double tap → Off
- Notification and quick-settings expansion uses `StatusBarManager` via the normal `EXPAND_STATUS_BAR` permission (no root required)

**Handle — Both sides**
- New "Both" option in the Side setting spawns a pull-tab on each screen edge simultaneously
- Swiping either handle opens the panel on that side; both handles hide and show together

**Long-press app context menu**
- Long-pressing any app cell (panel grid or All Apps drawer) shows a small floating menu with Open and Split Screen options
- Split Screen attempts `WINDOWING_MODE_SPLIT_SCREEN_SECONDARY` via reflection; works on stock Android 7+

**Control tab — All Apps and Edit toggles**
- All Apps and Edit can now be individually enabled or disabled from the Control tab, matching the per-tile toggle pattern of the quick controls

### Changed

**Controls strip — unified section**
- All Apps and Edit are now rendered as quick-control-style tiles (rounded square, icon, label) inside the controls strip rather than a separate bottom bar
- The entire strip — quick controls and action tiles — moves together when the position is switched between top and bottom

**Handle tab — color picker**
- Custom panel color input now uses a `HueCircular` + `Panel1` color wheel from `reanimated-color-picker` instead of a plain hex text field; the hex input remains as a secondary precision entry

**Theme scope**
- The Light/Dark theme setting in the Handle tab now applies immediately to the main app UI, not only the overlay panel

**Handle tab — range changes**
- Opacity minimum reduced from 0.1 to 0 (fully transparent hot zone)
- Width minimum reduced from 6 dp to 2 dp

**Behavior tab — quick controls position**
- New Top/Bottom segmented control sets whether the controls strip appears above or below the favorites grid

### Removed

- Floating window launch option (relied on OEM-proprietary APIs not accessible from user-space on ColorOS and similar; split screen covers the multi-window use case on stock Android)
- One-column panel layout option (two columns retained as the only layout)

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
