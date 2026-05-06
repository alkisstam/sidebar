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
  side: 'left' | 'right';
  opacity: number;
  theme: 'light' | 'dark';
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
};
