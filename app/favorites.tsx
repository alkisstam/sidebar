import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  ActivityIndicator,
  Alert,
  FlatList,
  Pressable,
  StyleSheet,
  Text,
  TextInput,
  useColorScheme,
  View,
} from "react-native";
import { Image } from "expo-image";
import { useNavigation, useRouter } from "expo-router";
import DraggableFlatList, {
  RenderItemParams,
  ScaleDecorator,
} from "react-native-draggable-flatlist";
import Sidebar, { InstalledApp } from "../modules/sidebar";

const ROW_HEIGHT = 70;

export default function Favorites() {
  const scheme = useColorScheme();
  const navigation = useNavigation();
  const router = useRouter();
  const colors = makeColors(scheme);

  const [orderedFavs, setOrderedFavs] = useState<InstalledApp[]>([]);
  const [appMap, setAppMap] = useState<Map<string, InstalledApp>>(new Map());
  const [allLoaded, setAllLoaded] = useState(false);
  const [loadError, setLoadError] = useState(false);
  const [query, setQuery] = useState("");

  const orderedFavsRef = useRef(orderedFavs);
  useEffect(() => {
    orderedFavsRef.current = orderedFavs;
  }, [orderedFavs]);

  useEffect(() => {
    Sidebar.getFavorites()
      .then((favs) => {
        setOrderedFavs(favs.map((pkg) => ({ name: "", packageName: pkg, icon: "" })));
      })
      .catch(() => Alert.alert("Error", "Failed to load favorites."));
    Sidebar.getInstalledApps()
      .then((apps) => {
        const map = new Map(apps.map((a) => [a.packageName, a]));
        setAppMap(map);
        setAllLoaded(true);
        setOrderedFavs((prev) => prev.map((p) => map.get(p.packageName) ?? p));
      })
      .catch(() => {
        setLoadError(true);
        setAllLoaded(true);
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
    try {
      await Sidebar.saveFavorites(orderedFavsRef.current.map((a) => a.packageName));
      router.back();
    } catch {
      Alert.alert("Error", "Failed to save favorites. Please try again.");
    }
  }

  function addFav(app: InstalledApp) {
    setOrderedFavs((prev) => [...prev, app]);
  }

  function removeFav(pkg: string) {
    setOrderedFavs((prev) => prev.filter((a) => a.packageName !== pkg));
  }

  const favPackages = useMemo(
    () => new Set(orderedFavs.map((a) => a.packageName)),
    [orderedFavs]
  );

  const filteredUnselected = useMemo(() => {
    if (!allLoaded) return [];
    const q = query.toLowerCase();
    return [...appMap.values()].filter(
      (a) => !favPackages.has(a.packageName) && a.name.toLowerCase().includes(q)
    );
  }, [appMap, favPackages, query, allLoaded]);

  const s = styles(colors);

  const renderFavItem = useCallback(
    ({ item, drag, isActive }: RenderItemParams<InstalledApp>) => (
      <ScaleDecorator>
        <View style={[s.row, isActive && s.rowActive]}>
          {item.icon ? (
            <Image
              source={{ uri: `data:image/png;base64,${item.icon}` }}
              style={s.icon}
              contentFit="contain"
            />
          ) : (
            <View style={[s.icon, s.iconPlaceholder]} />
          )}
          <Text style={s.appName} numberOfLines={1}>
            {item.name || item.packageName.split(".").pop() || item.packageName}
          </Text>
          <Pressable
            onPress={() => removeFav(item.packageName)}
            style={s.removeBtn}
            hitSlop={8}
          >
            <Text style={s.removeBtnText}>✕</Text>
          </Pressable>
          <Pressable onLongPress={drag} delayLongPress={150} style={s.dragHandle} hitSlop={8}>
            <Text style={s.dragHandleText}>☰</Text>
          </Pressable>
        </View>
      </ScaleDecorator>
    ),
    [s]
  );

  const favListHeight = orderedFavs.length * ROW_HEIGHT + 4;

  const listHeader = (
    <>
      <Text style={s.sectionHeader}>MY SIDEBAR</Text>
      <View style={s.card}>
        {orderedFavs.length === 0 ? (
          <Text style={s.emptyText}>No favorites yet — add apps below.</Text>
        ) : (
          <DraggableFlatList
            data={orderedFavs}
            keyExtractor={(item) => item.packageName}
            renderItem={renderFavItem}
            onDragEnd={({ data }) => setOrderedFavs(data)}
            scrollEnabled={false}
            style={{ height: favListHeight }}
            ItemSeparatorComponent={() => <View style={s.separator} />}
          />
        )}
      </View>

      <Text style={s.sectionHeader}>ADD APPS</Text>
      <TextInput
        style={s.search}
        placeholder="Search apps…"
        placeholderTextColor={colors.subtext}
        value={query}
        onChangeText={setQuery}
        clearButtonMode="while-editing"
      />
      {!allLoaded && (
        <ActivityIndicator style={{ marginVertical: 20 }} color={colors.tint} />
      )}
      {loadError && (
        <Text style={s.errorText}>Failed to load installed apps.</Text>
      )}
    </>
  );

  return (
    <FlatList
      style={s.container}
      data={filteredUnselected}
      keyExtractor={(item) => item.packageName}
      renderItem={({ item }) => (
        <Pressable style={s.row} onPress={() => addFav(item)}>
          {item.icon ? (
            <Image
              source={{ uri: `data:image/png;base64,${item.icon}` }}
              style={s.icon}
              contentFit="contain"
            />
          ) : (
            <View style={[s.icon, s.iconPlaceholder]} />
          )}
          <Text style={s.appName} numberOfLines={1}>
            {item.name}
          </Text>
          <Text style={s.addBtn}>+</Text>
        </Pressable>
      )}
      ListHeaderComponent={listHeader}
      ItemSeparatorComponent={() => <View style={s.separatorIndented} />}
      contentContainerStyle={{ paddingBottom: 24 }}
    />
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
    danger: "#FF3B30",
  };
}

function styles(colors: ReturnType<typeof makeColors>) {
  return StyleSheet.create({
    container: { flex: 1, backgroundColor: colors.bg },
    sectionHeader: {
      fontSize: 12,
      fontWeight: "600",
      color: colors.subtext,
      marginTop: 24,
      marginBottom: 6,
      marginLeft: 16,
      letterSpacing: 0.5,
    },
    card: {
      backgroundColor: colors.card,
      borderRadius: 12,
      overflow: "hidden",
      marginHorizontal: 16,
    },
    emptyText: {
      fontSize: 14,
      color: colors.subtext,
      padding: 16,
      textAlign: "center",
    },
    search: {
      marginHorizontal: 16,
      marginBottom: 4,
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
    rowActive: { opacity: 0.9, backgroundColor: colors.bg },
    icon: { width: 48, height: 48, borderRadius: 10 },
    iconPlaceholder: { backgroundColor: colors.separator },
    appName: { flex: 1, fontSize: 15, color: colors.text },
    removeBtn: {
      width: 28,
      height: 28,
      alignItems: "center",
      justifyContent: "center",
    },
    removeBtnText: { fontSize: 16, color: colors.danger, fontWeight: "600" },
    dragHandle: {
      width: 32,
      height: 32,
      alignItems: "center",
      justifyContent: "center",
    },
    dragHandleText: { fontSize: 20, color: colors.subtext },
    addBtn: {
      fontSize: 24,
      color: colors.tint,
      fontWeight: "300",
      lineHeight: 28,
      paddingHorizontal: 4,
    },
    separator: {
      height: StyleSheet.hairlineWidth,
      backgroundColor: colors.separator,
      marginLeft: 76,
    },
    separatorIndented: {
      height: StyleSheet.hairlineWidth,
      backgroundColor: colors.separator,
      marginLeft: 76,
    },
    errorText: {
      fontSize: 14,
      color: colors.danger,
      textAlign: "center",
      marginVertical: 16,
    },
  });
}
