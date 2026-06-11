import { Stack } from "expo-router";
import { useEffect, useState } from "react";
import { useColorScheme } from "react-native";
import { GestureHandlerRootView } from "react-native-gesture-handler";
import * as SplashScreen from "expo-splash-screen";
import { ThemeChoice, ThemeContext } from "../src/ThemeContext";

SplashScreen.preventAutoHideAsync();

export default function RootLayout() {
  const systemScheme = useColorScheme();
  const [themeChoice, setThemeChoice] = useState<ThemeChoice>('system');

  useEffect(() => { SplashScreen.hideAsync(); }, []);
  const appTheme = themeChoice === 'system' ? (systemScheme ?? 'dark') : themeChoice;
  const dark = appTheme === "dark";
  return (
    <ThemeContext.Provider value={{ appTheme, themeChoice, setThemeChoice }}>
      <GestureHandlerRootView style={{ flex: 1 }}>
        <Stack
          screenOptions={{
            headerShadowVisible: false,
            headerStyle: { backgroundColor: dark ? "#141218" : "#FFFBFE" },
            headerTintColor: dark ? "#E6E1E5" : "#1C1B1F",
            contentStyle: { backgroundColor: dark ? "#141218" : "#FFFBFE" },
          }}
        />
      </GestureHandlerRootView>
    </ThemeContext.Provider>
  );
}
