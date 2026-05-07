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

  const primaryColor = colors.primary;
  useEffect(() => {
    navigation.setOptions({
      title: "Favorites",
      headerRight: () => (
        <Pressable onPress={handleDone} style={{ paddingHorizontal: 8 }}>
          <Text style={{ color: primaryColor, fontSize: 14, fontWeight: "500", letterSpacing: 0.1 }}>Done</Text>
        </Pressable>
      ),
    });
  }, [navigation, primaryColor]);

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
        <ActivityIndicator style={{ marginVertical: 20 }} color={colors.primary} />
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
    bg:                   dark ? "#141218" : "#FFFBFE",
    surfaceContainer:     dark ? "#211F26" : "#F3EFF7",
    surfaceContainerHigh: dark ? "#2B2930" : "#EDE8F2",
    primary:              dark ? "#D0BCFF" : "#6750A4",
    onPrimary:            dark ? "#381E72" : "#FFFFFF",
    text:                 dark ? "#E6E1E5" : "#1C1B1F",
    subtext:              dark ? "#CAC4D0" : "#49454F",
    separator:            dark ? "#49454F" : "#CAC4D0",
    errorContainer:       dark ? "#8C1D18" : "#F9DEDC",
    onErrorContainer:     dark ? "#F2B8B5" : "#410E0B",
    danger:               dark ? "#F2B8B5" : "#B3261E",
  };
}

function styles(colors: ReturnType<typeof makeColors>) {
  return StyleSheet.create({
    container: { flex: 1, backgroundColor: colors.bg },
    sectionHeader: {
      fontSize: 11,
      fontWeight: "600",
      color: colors.subtext,
      marginTop: 24,
      marginBottom: 8,
      marginLeft: 20,
      letterSpacing: 0.5,
      textTransform: "uppercase",
    },
    card: {
      backgroundColor: colors.surfaceContainer,
      borderRadius: 16,
      overflow: "hidden",
      marginHorizontal: 16,
    },
    emptyText: {
      fontSize: 14,
      color: colors.subtext,
      padding: 20,
      textAlign: "center",
    },
    // M3 Search Bar
    search: {
      marginHorizontal: 16,
      marginBottom: 8,
      paddingHorizontal: 16,
      paddingVertical: 12,
      backgroundColor: colors.surfaceContainerHigh,
      borderRadius: 28,
      fontSize: 14,
      color: colors.text,
    },
    row: {
      flexDirection: "row",
      alignItems: "center",
      paddingHorizontal: 16,
      height: ROW_HEIGHT,
      backgroundColor: colors.surfaceContainer,
      gap: 12,
    },
    rowActive: { backgroundColor: colors.surfaceContainerHigh },
    icon: { width: 48, height: 48, borderRadius: 12 },
    iconPlaceholder: { backgroundColor: colors.separator },
    appName: { flex: 1, fontSize: 15, color: colors.text },
    removeBtn: {
      width: 32,
      height: 32,
      borderRadius: 16,
      backgroundColor: colors.errorContainer,
      alignItems: "center",
      justifyContent: "center",
    },
    removeBtnText: { fontSize: 14, color: colors.onErrorContainer, fontWeight: "600" },
    dragHandle: {
      width: 32,
      height: 32,
      alignItems: "center",
      justifyContent: "center",
    },
    dragHandleText: { fontSize: 20, color: colors.subtext },
    addBtn: {
      fontSize: 24,
      color: colors.primary,
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
