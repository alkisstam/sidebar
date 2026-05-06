import { Stack } from "expo-router";
import { useColorScheme } from "react-native";

export default function RootLayout() {
  const scheme = useColorScheme();
  return (
    <Stack
      screenOptions={{
        headerShadowVisible: false,
        contentStyle: { backgroundColor: scheme === "dark" ? "#1c1c1e" : "#f2f2f7" },
      }}
    />
  );
}
