import { NativeModules } from 'react-native';

const { SidebarModule } = NativeModules;

export interface InstalledApp {
  name: string;
  packageName: string;
  icon: string;
}

export interface PillSettings {
  height: number;
  width: number;
  position: number;
  side: 'left' | 'right' | 'both';
  opacity: number;
  theme: 'light' | 'dark';
  panelColor: string;
}

export interface OverlaySettings {
  autoHideFullscreen: boolean;
  showLabels: boolean;
  vibration: boolean;
  sensitivity: number;
  quickControlsEnabled: boolean;
  showTorch: boolean;
  showAutoRotate: boolean;
  showAutoBrightness: boolean;
  showRingerMode: boolean;
  showAllApps: boolean;
  showEdit: boolean;
  gestureSwipeUp: 'none' | 'panel' | 'notifications' | 'quick_settings' | 'all_apps';
  gestureSwipeDown: 'none' | 'panel' | 'notifications' | 'quick_settings' | 'all_apps';
  gestureDoubleTap: 'none' | 'panel' | 'notifications' | 'quick_settings' | 'all_apps';
  showBrightnessSlider: boolean;
  showVolumeSlider: boolean;
  showQuickShare: boolean;
}

export default {
  getInstalledApps(): Promise<InstalledApp[]> {
    return SidebarModule.getInstalledApps();
  },
  saveFavorites(packages: string[]): Promise<void> {
    return SidebarModule.saveFavorites(packages);
  },
  getFavorites(): Promise<string[]> {
    return SidebarModule.getFavorites();
  },
  hasOverlayPermission(): Promise<boolean> {
    return SidebarModule.hasOverlayPermission();
  },
  requestOverlayPermission(): Promise<void> {
    return SidebarModule.requestOverlayPermission();
  },
  startService(): Promise<void> {
    return SidebarModule.startService();
  },
  stopService(): Promise<void> {
    return SidebarModule.stopService();
  },
  isServiceEnabled(): Promise<boolean> {
    return SidebarModule.isServiceEnabled();
  },
  savePillSettings(settings: PillSettings): Promise<void> {
    return SidebarModule.savePillSettings(settings);
  },
  getPillSettings(): Promise<PillSettings> {
    return SidebarModule.getPillSettings();
  },
  getOverlaySettings(): Promise<OverlaySettings> {
    return SidebarModule.getOverlaySettings();
  },
  saveOverlaySettings(settings: OverlaySettings): Promise<void> {
    return SidebarModule.saveOverlaySettings(settings);
  },
  getLaunchTab(): Promise<string | null> {
    return SidebarModule.getLaunchTab();
  },
  hasDndPermission(): Promise<boolean> {
    return SidebarModule.hasDndPermission();
  },
  requestDndPermission(): Promise<void> {
    return SidebarModule.requestDndPermission();
  },
  hasWriteSettingsPermission(): Promise<boolean> {
    return SidebarModule.hasWriteSettingsPermission();
  },
  requestWriteSettingsPermission(): Promise<void> {
    return SidebarModule.requestWriteSettingsPermission();
  },
};
