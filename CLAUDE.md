# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

An Android-only Expo/React Native app: a pull-tab handle on the screen edge that opens a floating overlay panel (favorite apps grid + quick-control tiles). The settings UI is React Native (Expo Router); the actual on-screen overlay (handle, panel, control tiles, volume/brightness sliders) is built entirely in Kotlin using raw `WindowManager` windows — there is no native UI toolkit involved on that side, and it cannot be previewed in Expo Go.

## Commands

```bash
npm install                      # install JS deps
npx expo run:android             # build debug APK, install, start Metro, launch on connected device
npx expo start                   # Metro only (use when a debug build is already installed)
npx expo run:android --variant release   # release build via Expo CLI
cd android && ./gradlew assembleRelease   # release build directly (faster iteration on native-only changes)
npx tsc --noEmit -p .             # typecheck (no test suite exists in this repo)
npm run lint                      # expo lint
```

No automated test suite — verify changes by running the app on a device (`/run` skill or the above commands) and checking the relevant tab/overlay visually.

### Debug vs release on device

Debug and release builds are signed differently but share the package name `com.alkisstam.sidebar`, so switching between them fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Run `adb uninstall com.alkisstam.sidebar` before installing the other variant. This wipes app data (sidebar settings revert to defaults) — confirm with the user before doing it, since it's a destructive step.

Release signing reads `SIDEBAR_STORE_PASSWORD` / `SIDEBAR_KEY_PASSWORD` from Gradle properties; keystore is `android/app/my-release-key.keystore`.

## Architecture

### Two halves of the app

1. **`app/index.tsx`** — the in-app settings screen (Expo Router, single screen, four swipeable tabs: Handle, Behavior, Control, Apps). All settings are local React state (`pill: PillSettings`, `overlay: OverlaySettings`) synced to native SharedPreferences via `modules/sidebar.ts`. Tab switching is a custom `Animated`/`PanResponder` slide, not `@react-navigation` tabs.
2. **`android/app/src/main/java/com/alkisstam/sidebar/SidebarOverlayService.kt`** — a foreground `Service` that owns the actual on-screen overlay. It adds *multiple independent* `WindowManager` windows (handle pill, panel, control-tiles popup, volume/brightness popup, all-apps drawer, dismiss-catcher), each built with manual `LinearLayout`/`FrameLayout`/`TextView` view trees — no XML layouts, no Compose. This file is large (~1800 lines); when adding a control tile or panel element, find the relevant `wm.addView(...)` block rather than assuming a single root layout.

These two halves only communicate through `modules/sidebar.ts` (the JS-side bridge) and `SidebarModule.kt` (the native module) reading/writing `SharedPreferences` (`SidebarPrefs.kt` holds all the key constants) — there's no live RN↔overlay channel; the overlay service re-reads prefs and rebuilds its views.

### Settings flow

- `OverlaySettings` / `PillSettings` (TypeScript interfaces in `modules/sidebar.ts`) are the single source of truth for shape. Any new toggle needs: a field in the interface, a default in `DEFAULT_OVERLAY`/`DEFAULT_PILL` (`app/index.tsx`), a `Prefs.*` key + read/write in `SidebarModule.kt`'s `getOverlaySettings`/`saveOverlaySettings`, and a matching field in `SidebarOverlayService.kt`'s settings data class plus the code path that acts on it.
- Permission checks (overlay, DND, write-settings, usage-access) follow one repeated pattern: `hasXPermission`/`requestXPermission` native methods, mirrored in `modules/sidebar.ts`, polled on mount and again on app-resume (`AppState` listener in `app/index.tsx`), rendered as a row with either a checkmark or a "Grant" button that deep-links to the relevant system Settings screen.
- Control tiles (`CONTROL_TILES` in `app/index.tsx`) are reorderable; order is persisted as an array of string ids (`controlTilesOrder`) and consumed by `SidebarOverlayService.kt`, which silently skips ids whose `show*` flag is off — so JS-side reordering logic only needs to keep disabled ids somewhere in the list, not filter them out.

### Theming

`src/theme.ts` exports `makeColors(scheme)` and `makeStyles(colors)` (Material 3-flavored palette, light/dark). `src/ThemeContext.tsx` provides the active scheme app-wide. Always derive new styles from the existing `colors`/`s` objects passed into screens rather than hardcoding hex values.

### Versioning

Version lives in `android/app/build.gradle` (`versionCode` int, `versionName` string) — bump both together. `CHANGELOG.md` follows Keep-a-Changelog style; add an entry per release under a new `## [x.y.z] - YYYY-MM-DD` heading.
