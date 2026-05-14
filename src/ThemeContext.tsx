import { createContext, useContext } from "react";

export type AppTheme = 'light' | 'dark';
export type ThemeChoice = 'light' | 'dark' | 'system';

interface ThemeContextValue {
  appTheme: AppTheme;
  themeChoice: ThemeChoice;
  setThemeChoice: (choice: ThemeChoice) => void;
}

export const ThemeContext = createContext<ThemeContextValue>({
  appTheme: 'dark',
  themeChoice: 'system',
  setThemeChoice: () => {},
});

export function useAppTheme() {
  return useContext(ThemeContext);
}
