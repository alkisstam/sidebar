# Sidebar

An Android overlay sidebar for quick access to favorite apps. A pull-tab handle sits on the screen edge and opens a floating panel with a 2-column grid of your chosen apps.

## Features

- Pull-tab handle on the screen edge (left or right, configurable position and size)
- Floating panel with a scrollable 2-column grid of favorite apps
- Tap any app to launch it and close the panel
- Persists across reboots via a foreground service and boot receiver
- React Native config UI to manage favorites and handle settings

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
  index.tsx         Settings screen (service toggle, handle config, favorites nav)
  favorites.tsx     App picker for managing sidebar favorites
modules/
  sidebar.ts        React Native bridge to the native Android module
android/
  ...               Native Android service and overlay implementation
```

## To-do

- [✓] Change tabs in the main app by swiping
- [ ] Change from favorite apps to control center by swiping in the floating panel
- [ ] Add a toggle in settings to enable or disable the control center
