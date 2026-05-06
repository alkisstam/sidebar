import { useEffect, useMemo, useRef, useState } from "react";
import {
  ActivityIndicator,
  FlatList,
  Pressable,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  useColorScheme,
  View,
} from "react-native";
import { Image } from "expo-image";
import { useNavigation, useRouter } from "expo-router";
import Sidebar, { InstalledApp } from "../modules/sidebar";

const ROW_HEIGHT = 70;

export default function Favorites() {
  const scheme = useColorScheme();
  const navigation = useNavigation();
  const router = useRouter();
  const colors = makeColors(scheme);

  const [allApps, setAllApps] = useState<InstalledApp[]>([]);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [query, setQuery] = useState("");
  const [loading, setLoading] = useState(true);
  const selectedRef = useRef(selected);

  useEffect(() => {
    selectedRef.current = selected;
  }, [selected]);

  useEffect(() => {
    Promise.all([Sidebar.getInstalledApps(), Sidebar.getFavorites()]).then(([apps, favs]) => {
      setAllApps(apps);
      setSelected(new Set(favs));
      setLoading(false);
    });
  }, []);

  useEffect(() => {
    navigation.setOptions({
      title: "Favorites",
      headerRight: () => (
        <Pressable onPress={handleDone} style={{ paddingHorizontal: 8 }}>
          <Text style={{ color: "#007AFF", fontSize: 16, fontWeight: "600" }}>Done</Text>
        </Pressable>
      ),
    });
  }, [navigation]);

  async function handleDone() {
    await Sidebar.saveFavorites([...selectedRef.current]);
    router.back();
  }

  function toggle(pkg: string) {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(pkg)) {
        next.delete(pkg);
      } else {
        next.add(pkg);
      }
      return next;
    });
  }

  const filtered = useMemo(
    () => allApps.filter((a) => a.name.toLowerCase().includes(query.toLowerCase())),
    [allApps, query]
  );

  const s = styles(colors);

  return (
    <View style={s.container}>
      <TextInput
        style={s.search}
        placeholder="Search apps…"
        placeholderTextColor={colors.subtext}
        value={query}
        onChangeText={setQuery}
        clearButtonMode="while-editing"
      />
      {loading ? (
        <ActivityIndicator style={{ marginTop: 40 }} color={colors.tint} />
      ) : (
        <FlatList
          data={filtered}
          keyExtractor={(item) => item.packageName}
          getItemLayout={(_data, index) => ({
            length: ROW_HEIGHT,
            offset: ROW_HEIGHT * index,
            index,
          })}
          renderItem={({ item }) => (
            <AppRow
              item={item}
              checked={selected.has(item.packageName)}
              onToggle={toggle}
              colors={colors}
              s={s}
            />
          )}
          ItemSeparatorComponent={() => <View style={s.separator} />}
          contentContainerStyle={{ paddingBottom: 24 }}
        />
      )}
    </View>
  );
}

function AppRow({
  item,
  checked,
  onToggle,
  colors,
  s,
}: {
  item: InstalledApp;
  checked: boolean;
  onToggle: (pkg: string) => void;
  colors: ReturnType<typeof makeColors>;
  s: ReturnType<typeof styles>;
}) {
  return (
    <Pressable style={s.row} onPress={() => onToggle(item.packageName)}>
      <Image
        source={{ uri: `data:image/png;base64,${item.icon}` }}
        style={s.icon}
        contentFit="contain"
      />
      <Text style={s.appName} numberOfLines={1}>
        {item.name}
      </Text>
      <Switch
        value={checked}
        onValueChange={() => onToggle(item.packageName)}
        trackColor={{ true: colors.tint }}
      />
    </Pressable>
  );
}

function makeColors(scheme: ReturnType<typeof useColorScheme>) {
  const dark = scheme === "dark";
  return {
    bg: dark ? "#1c1c1e" : "#f2f2f7",
    card: dark ? "#2c2c2e" : "#ffffff",
    text: dark ? "#ffffff" : "#000000",
    subtext: dark ? "#8e8e93" : "#6c6c70",
    tint: "#007AFF",
    separator: dark ? "#38383a" : "#e0e0e5",
  };
}

function styles(colors: ReturnType<typeof makeColors>) {
  return StyleSheet.create({
    container: { flex: 1, backgroundColor: colors.bg },
    search: {
      margin: 12,
      paddingHorizontal: 14,
      paddingVertical: 10,
      backgroundColor: colors.card,
      borderRadius: 10,
      fontSize: 15,
      color: colors.text,
    },
    row: {
      flexDirection: "row",
      alignItems: "center",
      paddingHorizontal: 16,
      height: ROW_HEIGHT,
      backgroundColor: colors.card,
      gap: 12,
    },
    icon: { width: 48, height: 48, borderRadius: 10 },
    appName: { flex: 1, fontSize: 15, color: colors.text },
    separator: {
      height: StyleSheet.hairlineWidth,
      backgroundColor: colors.separator,
      marginLeft: 76,
    },
  });
}
