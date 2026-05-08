# Sidebar

An Android overlay sidebar for quick access to favorite apps. A pull-tab handle sits on the screen edge and opens a floating panel with quick controls and a grid of your chosen apps.

## Features

- Pull-tab handle on the screen edge (left or right) with configurable vertical position, size, opacity, and theme
- Floating panel with a quick-controls strip (torch, auto-rotate, auto-brightness, ringer mode) at the top and a scrollable 2-column grid of favorite apps below
- Tap any app to launch it and close the panel
- Drag-to-reorder favorites via long-press handle in the Apps tab
- Persists across reboots via a foreground service and boot receiver
- Auto-hides in fullscreen apps (configurable); battery-friendly polling that pauses when the screen is off
- Material Design 3 config UI with four tabs: Handle, Behavior, Control, Apps; swipe left/right to switch tabs
- Behavior tab: auto-hide in fullscreen, app labels, vibration feedback, swipe sensitivity
- Control tab: manage overlay, Do Not Disturb, and system-settings permissions in one place

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

