import { createContext, useContext } from "react";

type AppTheme = 'light' | 'dark';

interface ThemeContextValue {
  appTheme: AppTheme;
  setAppTheme: (theme: AppTheme) => void;
}

export const ThemeContext = createContext<ThemeContextValue>({
  appTheme: 'dark',
  setAppTheme: () => {},
});

export function useAppTheme() {
  return useContext(ThemeContext);
}
