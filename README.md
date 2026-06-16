# Sidebar

An Android overlay sidebar for quick access to favorite apps. A pull-tab handle sits on the screen edge and opens a floating panel with quick controls and a grid of your chosen apps.

## Features

- Pull-tab handle on the screen edge — left, right, or both sides simultaneously — with configurable vertical position, size (down to 2 dp), and opacity (down to fully transparent)
- Pill gestures: swipe up, swipe down, and double tap each map to a configurable action — open panel, expand notifications, expand quick settings, or open all apps
- Floating panel with a unified controls strip containing quick-control tiles (torch, auto-rotate, auto-brightness, ringer mode) alongside All Apps and Edit action tiles; the entire strip positions at the top or bottom of the panel
- All Apps drawer: centered overlay with a 4-column alphabetical grid of all installed apps and a live search bar
- Long-press any app icon to open a context menu with Open or Floating Window options; Floating Window launches the app in a resizable freeform window (works on stock Android with desktop/freeform mode enabled)
- Drag-to-reorder favorites via long-press on the drag indicator in the open panel; tap any app to launch it
- Persists across reboots via a foreground service and boot receiver
- Auto-hides in fullscreen apps (configurable); battery-friendly polling that pauses when the screen is off
- Material Design 3 config UI with four tabs: Handle, Behavior, Control, Apps; swipe left/right to switch tabs
- Handle tab: side (left / right / both), theme (applied to entire app), panel color picker (8 presets + color wheel), position, height, width, opacity
- Behavior tab: auto-hide in fullscreen, app labels, vibration feedback, swipe sensitivity, pill gesture mapping
- Control tab: master and per-tile toggles for quick controls and action buttons; overlay, Do Not Disturb, and system-settings permission management

## Gallery

<img src="https://github.com/alkisstam/sidebar/blob/master/assets/images/floating-panel.jpg" alt="Alt text" width="200"> <img src="https://github.com/alkisstam/sidebar/blob/master/assets/images/main-app.jpg" alt="Alt text" width="200"> <img src="https://github.com/alkisstam/sidebar/blob/master/assets/images/main-app3.jpg" alt="Alt text" width="200"> <img src="https://github.com/alkisstam/sidebar/blob/master/assets/images/all-apps-panel.jpg" alt="Alt text" width="200"> 

## Installation

Download the latest APK from the [Releases](https://github.com/alkisstam/sidebar/releases) page and sideload it onto your Android device. Enable "Install from unknown sources" in your device settings if prompted.

## Setup

1. Install dependencies
   ```
   npm install
   ```

2. Connect an Android device and run
   ```
   npx expo run:android
   ```

3. In the app, grant the "Display over other apps" permission, then toggle the service on.

4. Optionally grant **Do Not Disturb** and **Modify system settings** permissions (Control tab) to enable the ringer and brightness controls in the panel.

## Development

Start the Metro bundler:
```
npx expo start
```

The debug build connects to Metro over USB or Wi-Fi. For a standalone build:
```
npx expo run:android --variant release
```

## Project structure

```
app/
  _layout.tsx       Root stack navigator
  index.tsx         Main settings screen (4 tabs: Handle, Behavior, Control, Apps)
  favorites.tsx     Legacy app picker (kept for reference)
modules/
  sidebar.ts        React Native bridge to the native Android module
android/
  app/src/main/java/com/anonymous/sidebar/
    SidebarOverlayService.kt   Foreground service; floating panel and handle
    SidebarModule.kt           React Native native module
    BootReceiver.kt            Auto-start on device boot
```

