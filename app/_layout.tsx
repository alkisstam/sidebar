import { Stack } from "expo-router";
import { useColorScheme } from "react-native";
import { GestureHandlerRootView } from "react-native-gesture-handler";

export default function RootLayout() {
  const scheme = useColorScheme();
  return (
    <GestureHandlerRootView style={{ flex: 1 }}>
      <Stack
        screenOptions={{
          headerShadowVisible: false,
          contentStyle: { backgroundColor: scheme === "dark" ? "#141218" : "#FFFBFE" },
        }}
      />
    </GestureHandlerRootView>
  );
}
