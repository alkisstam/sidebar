import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  ActivityIndicator,
  Alert,
  AppState,
  AppStateStatus,
  FlatList,
  Pressable,
  ScrollView,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  useColorScheme,
  View,
} from "react-native";
import { Image } from "expo-image";
import Slider from "@react-native-community/slider";
import { Ionicons } from "@expo/vector-icons";
import { Stack } from "expo-router";
import DraggableFlatList, {
  RenderItemParams,
  ScaleDecorator,
} from "react-native-draggable-flatlist";
import Sidebar, { InstalledApp, OverlaySettings, PillSettings } from "../modules/sidebar";

type Tab = "handle" | "behavior" | "control" | "apps";
type FavItem = { key: string; name: string; icon: string | null };

const TABS: { id: Tab; label: string; icon: string }[] = [
  { id: "handle",   label: "Handle",   icon: "options-outline"  },
  { id: "behavior", label: "Behavior", icon: "settings-outline" },
  { id: "control",  label: "Control",  icon: "grid-outline"     },
  { id: "apps",     label: "Apps",     icon: "apps-outline"     },
];

const DEFAULT_PILL: PillSettings = {
  height: 80, width: 36, position: 0.5, side: "left", opacity: 1.0, theme: "dark",
};
const DEFAULT_OVERLAY: OverlaySettings = {
  autoHideFullscreen: false, showLabels: true, vibration: true, sensitivity: 16,
};

export default function Index() {
  const scheme = useColorScheme();
  const colors = makeColors(scheme);

  const [activeTab, setActiveTab] = useState<Tab>("handle");

  const [permissionGranted, setPermissionGranted] = useState<boolean | null>(null);
  const [serviceEnabled, setServiceEnabled] = useState(false);
  const [dndPerm, setDndPerm] = useState(true);
  const [writePerm, setWritePerm] = useState(true);

  const [pill, setPill] = useState<PillSettings>(DEFAULT_PILL);
  const [overlay, setOverlay] = useState<OverlaySettings>(DEFAULT_OVERLAY);
  const [saving, setSaving] = useState(false);
  const [savingOverlay, setSavingOverlay] = useState(false);

  const [allApps, setAllApps] = useState<InstalledApp[]>([]);
  const [favPkgs, setFavPkgs] = useState<string[]>([]);
  const [query, setQuery] = useState("");
  const [appsLoading, setAppsLoading] = useState(false);
  const [appsSaving, setAppsSaving] = useState(false);
  const appsLoadedRef = useRef(false);

  const appState = useRef<AppStateStatus>(AppState.currentState);

  useEffect(() => {
    Promise.all([
      Sidebar.hasOverlayPermission(),
      Sidebar.isServiceEnabled(),
      Sidebar.getPillSettings(),
      Sidebar.getOverlaySettings(),
      Sidebar.hasDndPermission(),
      Sidebar.hasWriteSettingsPermission(),
      Sidebar.getFavorites(),
    ]).then(([perm, svc, pillSettings, overlaySettings, dnd, write, favs]) => {
      setPermissionGranted(perm);
      setServiceEnabled(svc);
      setPill(pillSettings);
      setOverlay(overlaySettings);
      setDndPerm(dnd);
      setWritePerm(write);
      setFavPkgs(favs);
    });

    const sub = AppState.addEventListener("change", (next) => {
      if (appState.current !== "active" && next === "active") {
        Promise.all([
          Sidebar.hasOverlayPermission(),
          Sidebar.hasDndPermission(),
          Sidebar.hasWriteSettingsPermission(),
        ]).then(([perm, dnd, write]) => {
          setPermissionGranted(perm);
          setDndPerm(dnd);
          setWritePerm(write);
        });
      }
      appState.current = next;
    });
    return () => sub.remove();
  }, []);

  useEffect(() => {
    if (activeTab === "apps" && !appsLoadedRef.current) {
      appsLoadedRef.current = true;
      setAppsLoading(true);
      Sidebar.getInstalledApps()
        .then(setAllApps)
        .catch(() => Alert.alert("Error", "Failed to load installed apps."))
        .finally(() => setAppsLoading(false));
    }
  }, [activeTab]);

  async function toggleService(value: boolean) {
    setServiceEnabled(value);
    try {
      value ? await Sidebar.startService() : await Sidebar.stopService();
    } catch {
      setServiceEnabled(!value);
      Alert.alert("Error", `Failed to ${value ? "start" : "stop"} the sidebar service.`);
    }
  }

  async function saveHandle() {
    setSaving(true);
    try { await Sidebar.savePillSettings(pill); }
    catch { Alert.alert("Error", "Failed to save handle settings."); }
    finally { setSaving(false); }
  }

  async function saveOverlay() {
    setSavingOverlay(true);
    try { await Sidebar.saveOverlaySettings(overlay); }
    catch { Alert.alert("Error", "Failed to save behavior settings."); }
    finally { setSavingOverlay(false); }
  }

  function toggleFav(pkg: string) {
    setFavPkgs(prev =>
      prev.includes(pkg) ? prev.filter(p => p !== pkg) : [...prev, pkg]
    );
  }

  async function saveFavs() {
    setAppsSaving(true);
    try { await Sidebar.saveFavorites(favPkgs); }
    catch { Alert.alert("Error", "Failed to save favorites."); }
    finally { setAppsSaving(false); }
  }

  const appMap = useMemo(
    () => new Map(allApps.map(a => [a.packageName, a])),
    [allApps]
  );

  const filteredApps = useMemo(() => {
    const q = query.toLowerCase();
    return q ? allApps.filter(a => a.name.toLowerCase().includes(q)) : allApps;
  }, [allApps, query]);

  const favItems = useMemo<FavItem[]>(
    () => favPkgs.map(pkg => {
      const app = appMap.get(pkg);
      return { key: pkg, name: app?.name ?? pkg.split(".").pop() ?? pkg, icon: app?.icon ?? null };
    }),
    [favPkgs, appMap]
  );

  const favSet = useMemo(() => new Set(favPkgs), [favPkgs]);

  const s = styles(colors);

  const renderFavItem = useCallback(
    ({ item, drag, isActive }: RenderItemParams<FavItem>) => (
      <ScaleDecorator>
        <View style={[s.favRow, isActive && s.favRowActive]}>
          {item.icon
            ? <Image source={{ uri: `data:image/png;base64,${item.icon}` }} style={s.favIcon} contentFit="contain" />
            : <View style={[s.favIcon, s.iconPlaceholder]} />
          }
          <Text style={s.favName} numberOfLines={1}>{item.name}</Text>
          <Pressable onLongPress={drag} delayLongPress={150} style={s.dragHandle} hitSlop={8}>
            <Ionicons name="reorder-three-outline" size={20} color={colors.subtext} />
          </Pressable>
        </View>
      </ScaleDecorator>
    ),
    [s, colors]
  );

  const renderAppCell = useCallback(
    ({ item }: { item: InstalledApp }) => {
      const selected = favSet.has(item.packageName);
      return (
        <Pressable style={s.appCell} onPress={() => toggleFav(item.packageName)}>
          <View style={s.appIconWrap}>
            <Image
              source={{ uri: `data:image/png;base64,${item.icon}` }}
              style={s.appIcon}
              contentFit="contain"
            />
            {selected && (
              <View style={s.checkBadge}>
                <Ionicons name="checkmark" size={9} color="#fff" />
              </View>
            )}
          </View>
          <Text style={[s.appCellName, selected && s.appCellNameSelected]} numberOfLines={2}>
            {item.name}
          </Text>
        </Pressable>
      );
    },
    [favSet, s]
  );

  return (
    <View style={s.root}>
      <Stack.Screen options={{ title: "Sidebar" }} />

      {permissionGranted === false && (
        <View style={s.banner}>
          <Text style={s.bannerTitle}>Overlay permission required</Text>
          <Text style={s.bannerBody}>
            This app needs "Display over other apps" permission to show the sidebar handle.
          </Text>
          <Pressable style={s.bannerBtn} onPress={Sidebar.requestOverlayPermission}>
            <Text style={s.bannerBtnText}>Grant Permission</Text>
          </Pressable>
        </View>
      )}

      <View style={s.serviceCard}>
        <View style={{ flex: 1 }}>
          <Text style={s.rowLabel}>Sidebar Overlay</Text>
          <Text style={s.rowSub}>Show pull-tab on screen edge</Text>
        </View>
        <Switch
          value={serviceEnabled}
          onValueChange={toggleService}
          disabled={!permissionGranted}
          trackColor={{ true: colors.tint }}
        />
      </View>

      {/* Handle tab */}
      <View style={{ display: activeTab === "handle" ? "flex" : "none", flex: 1 }}>
        <ScrollView style={s.scroll} contentContainerStyle={s.scrollContent}>
          <View style={s.section}>
            <View style={s.settingRow}>
              <Text style={s.settingLabel}>Side</Text>
              <View style={s.segmented}>
                {(["left", "right"] as const).map(side => (
                  <Pressable
                    key={side}
                    style={[s.segBtn, pill.side === side && s.segBtnActive]}
                    onPress={() => setPill(p => ({ ...p, side }))}
                  >
                    <Text style={[s.segBtnText, pill.side === side && s.segBtnTextActive]}>
                      {side.charAt(0).toUpperCase() + side.slice(1)}
                    </Text>
                  </Pressable>
                ))}
              </View>
            </View>
            <View style={s.separator} />
            <View style={s.settingRow}>
              <Text style={s.settingLabel}>Theme</Text>
              <View style={s.segmented}>
                {(["dark", "light"] as const).map(t => (
                  <Pressable
                    key={t}
                    style={[s.segBtn, pill.theme === t && s.segBtnActive]}
                    onPress={() => setPill(p => ({ ...p, theme: t }))}
                  >
                    <Text style={[s.segBtnText, pill.theme === t && s.segBtnTextActive]}>
                      {t.charAt(0).toUpperCase() + t.slice(1)}
                    </Text>
                  </Pressable>
                ))}
              </View>
            </View>
            <View style={s.separator} />
            <SliderRow label="Position" min={0} max={1} step={0.01}
              value={pill.position} display={pill.position.toFixed(2)}
              leftEdge="Top" rightEdge="Bot"
              onChange={v => setPill(p => ({ ...p, position: v }))}
              colors={colors} s={s} />
            <View style={s.separator} />
            <SliderRow label="Height" min={40} max={200} step={1}
              value={pill.height} display={`${Math.round(pill.height)}`}
              onChange={v => setPill(p => ({ ...p, height: Math.round(v) }))}
              colors={colors} s={s} />
            <View style={s.separator} />
            <SliderRow label="Width" min={6} max={40} step={1}
              value={pill.width} display={`${Math.round(pill.width)}`}
              onChange={v => setPill(p => ({ ...p, width: Math.round(v) }))}
              colors={colors} s={s} />
            <View style={s.separator} />
            <SliderRow label="Opacity" min={0.1} max={1} step={0.05}
              value={pill.opacity} display={pill.opacity.toFixed(2)}
              onChange={v => setPill(p => ({ ...p, opacity: parseFloat(v.toFixed(2)) }))}
              colors={colors} s={s} />
            <Pressable style={[s.saveBtn, saving && s.saveBtnDisabled]} onPress={saveHandle} disabled={saving}>
              <Text style={s.saveBtnText}>{saving ? "Saving…" : "Save Handle Settings"}</Text>
            </Pressable>
          </View>
        </ScrollView>
      </View>

      {/* Behavior tab */}
      <View style={{ display: activeTab === "behavior" ? "flex" : "none", flex: 1 }}>
        <ScrollView style={s.scroll} contentContainerStyle={s.scrollContent}>
          <View style={s.section}>
            <View style={s.row}>
              <View style={{ flex: 1 }}>
                <Text style={s.rowLabel}>Auto-hide in fullscreen</Text>
                <Text style={s.rowSub}>Hide pull-tab when an app is fullscreen</Text>
              </View>
              <Switch
                value={overlay.autoHideFullscreen}
                onValueChange={v => setOverlay(p => ({ ...p, autoHideFullscreen: v }))}
                trackColor={{ true: colors.tint }}
              />
            </View>
            <View style={s.separator} />
            <View style={s.row}>
              <Text style={[s.rowLabel, { flex: 1 }]}>Show app labels</Text>
              <Switch
                value={overlay.showLabels}
                onValueChange={v => setOverlay(p => ({ ...p, showLabels: v }))}
                trackColor={{ true: colors.tint }}
              />
            </View>
            <View style={s.separator} />
            <View style={s.row}>
              <Text style={[s.rowLabel, { flex: 1 }]}>Vibration feedback</Text>
              <Switch
                value={overlay.vibration}
                onValueChange={v => setOverlay(p => ({ ...p, vibration: v }))}
                trackColor={{ true: colors.tint }}
              />
            </View>
            <View style={s.separator} />
            <SliderRow label="Sensitivity" min={8} max={48} step={1}
              value={overlay.sensitivity} display={`${overlay.sensitivity}`}
              leftEdge="High" rightEdge="Low"
              onChange={v => setOverlay(p => ({ ...p, sensitivity: Math.round(v) }))}
              colors={colors} s={s} />
            <Pressable style={[s.saveBtn, savingOverlay && s.saveBtnDisabled]} onPress={saveOverlay} disabled={savingOverlay}>
              <Text style={s.saveBtnText}>{savingOverlay ? "Saving…" : "Save Behavior Settings"}</Text>
            </Pressable>
          </View>
        </ScrollView>
      </View>

      {/* Control tab */}
      <View style={{ display: activeTab === "control" ? "flex" : "none", flex: 1 }}>
        <ScrollView style={s.scroll} contentContainerStyle={s.scrollContent}>
          <View style={s.section}>
            <View style={s.row}>
              <View style={{ flex: 1 }}>
                <Text style={s.rowLabel}>Do Not Disturb</Text>
                <Text style={s.rowSub}>{dndPerm ? "Permission granted" : "Swipe to control panel tile to grant"}</Text>
              </View>
              {dndPerm
                ? <Ionicons name="checkmark-circle" size={20} color="#34C759" />
                : <Pressable style={s.grantBtn} onPress={Sidebar.requestDndPermission}>
                    <Text style={s.grantBtnText}>Grant</Text>
                  </Pressable>
              }
            </View>
            <View style={s.separator} />
            <View style={s.row}>
              <View style={{ flex: 1 }}>
                <Text style={s.rowLabel}>Auto-rotate & Brightness</Text>
                <Text style={s.rowSub}>{writePerm ? "Permission granted" : "Required to modify system settings"}</Text>
              </View>
              {writePerm
                ? <Ionicons name="checkmark-circle" size={20} color="#34C759" />
                : <Pressable style={s.grantBtn} onPress={Sidebar.requestWriteSettingsPermission}>
                    <Text style={s.grantBtnText}>Grant</Text>
                  </Pressable>
              }
            </View>
          </View>
        </ScrollView>
      </View>

      {/* Apps tab */}
      <View style={{ display: activeTab === "apps" ? "flex" : "none", flex: 1 }}>
        <View style={s.appsPane}>
          {/* Left: all apps grid */}
          <View style={s.appsLeft}>
            <TextInput
              style={s.search}
              placeholder="Search apps…"
              placeholderTextColor={colors.subtext}
              value={query}
              onChangeText={setQuery}
              clearButtonMode="while-editing"
            />
            {appsLoading ? (
              <ActivityIndicator style={{ marginTop: 32 }} color={colors.tint} />
            ) : (
              <FlatList
                data={filteredApps}
                keyExtractor={item => item.packageName}
                renderItem={renderAppCell}
                numColumns={3}
                contentContainerStyle={s.appGrid}
                showsVerticalScrollIndicator={false}
              />
            )}
          </View>

          <View style={s.paneDiv} />

          {/* Right: favorites */}
          <View style={s.appsRight}>
            <Text style={s.favHeader}>Favorites</Text>
            {favItems.length === 0 ? (
              <Text style={s.favEmpty}>Tap apps on the left to add.</Text>
            ) : (
              <DraggableFlatList
                data={favItems}
                keyExtractor={item => item.key}
                renderItem={renderFavItem}
                onDragEnd={({ data }) => setFavPkgs(data.map(i => i.key))}
                style={{ flex: 1 }}
                showsVerticalScrollIndicator={false}
              />
            )}
            <Pressable
              style={[s.saveBtn, s.saveBtnCompact, appsSaving && s.saveBtnDisabled]}
              onPress={saveFavs}
              disabled={appsSaving}
            >
              <Text style={s.saveBtnText}>{appsSaving ? "Saving…" : "Save"}</Text>
            </Pressable>
          </View>
        </View>
      </View>

      {/* Bottom tab bar */}
      <View style={s.tabBar}>
        {TABS.map(tab => (
          <Pressable key={tab.id} style={s.tabItem} onPress={() => setActiveTab(tab.id)}>
            <Ionicons
              name={tab.icon as any}
              size={22}
              color={activeTab === tab.id ? colors.tint : colors.subtext}
            />
            <Text style={[s.tabLabel, activeTab === tab.id && s.tabLabelActive]}>
              {tab.label}
            </Text>
          </Pressable>
        ))}
      </View>
    </View>
  );
}

function SliderRow({
  label, min, max, step, value, display, leftEdge, rightEdge, onChange, colors, s,
}: {
  label: string; min: number; max: number; step: number; value: number;
  display: string; leftEdge?: string; rightEdge?: string;
  onChange: (v: number) => void;
  colors: ReturnType<typeof makeColors>;
  s: ReturnType<typeof styles>;
}) {
  return (
    <View style={s.settingRow}>
      <Text style={s.settingLabel}>{label}</Text>
      <View style={s.sliderWrap}>
        {leftEdge && <Text style={s.sliderEdge}>{leftEdge}</Text>}
        <Slider
          style={{ flex: 1 }}
          minimumValue={min}
          maximumValue={max}
          step={step}
          value={value}
          onValueChange={onChange}
          minimumTrackTintColor={colors.tint}
          maximumTrackTintColor={colors.separator}
          thumbTintColor={colors.tint}
        />
        {rightEdge && <Text style={s.sliderEdge}>{rightEdge}</Text>}
        <Text style={[s.sliderEdge, { width: 36, textAlign: "right" }]}>{display}</Text>
      </View>
    </View>
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
    bannerBg: dark ? "#3a2000" : "#fff3cd",
    bannerText: dark ? "#ffcc00" : "#7a5200",
  };
}

function styles(colors: ReturnType<typeof makeColors>) {
  return StyleSheet.create({
    root: { flex: 1, backgroundColor: colors.bg },

    // Service card
    serviceCard: {
      flexDirection: "row",
      alignItems: "center",
      backgroundColor: colors.card,
      borderRadius: 12,
      paddingHorizontal: 16,
      paddingVertical: 14,
      margin: 12,
      marginBottom: 8,
    },

    // Tab content scrollable areas
    scroll: { flex: 1 },
    scrollContent: { padding: 12, paddingBottom: 24 },

    // Section card
    section: { backgroundColor: colors.card, borderRadius: 12, overflow: "hidden" },
    separator: { height: StyleSheet.hairlineWidth, backgroundColor: colors.separator, marginLeft: 16 },

    row: {
      flexDirection: "row", alignItems: "center",
      paddingHorizontal: 16, paddingVertical: 14,
    },
    rowLabel: { fontSize: 16, color: colors.text },
    rowSub: { fontSize: 13, color: colors.subtext, marginTop: 2 },

    settingRow: {
      flexDirection: "row", alignItems: "center",
      paddingHorizontal: 16, paddingVertical: 12, gap: 12,
    },
    settingLabel: { fontSize: 16, color: colors.text, width: 72 },

    segmented: { flexDirection: "row", gap: 8 },
    segBtn: { paddingHorizontal: 18, paddingVertical: 7, borderRadius: 8, borderWidth: 1, borderColor: colors.tint },
    segBtnActive: { backgroundColor: colors.tint },
    segBtnText: { fontSize: 14, color: colors.tint },
    segBtnTextActive: { color: "#fff" },

    sliderWrap: { flex: 1, flexDirection: "row", alignItems: "center", gap: 6 },
    sliderEdge: { fontSize: 12, color: colors.subtext },

    saveBtn: {
      margin: 12, backgroundColor: colors.tint,
      borderRadius: 10, paddingVertical: 12, alignItems: "center",
    },
    saveBtnCompact: { margin: 8, paddingVertical: 10 },
    saveBtnDisabled: { opacity: 0.5 },
    saveBtnText: { color: "#fff", fontSize: 15, fontWeight: "600" },

    grantBtn: { backgroundColor: colors.tint, borderRadius: 8, paddingHorizontal: 14, paddingVertical: 7 },
    grantBtnText: { color: "#fff", fontSize: 13, fontWeight: "600" },

    // Banner
    banner: { backgroundColor: colors.bannerBg, borderRadius: 12, padding: 16, gap: 8, margin: 12, marginBottom: 0 },
    bannerTitle: { fontSize: 15, fontWeight: "700", color: colors.bannerText },
    bannerBody: { fontSize: 14, color: colors.bannerText, lineHeight: 20 },
    bannerBtn: { alignSelf: "flex-start", backgroundColor: colors.tint, borderRadius: 8, paddingHorizontal: 16, paddingVertical: 8, marginTop: 4 },
    bannerBtnText: { color: "#fff", fontWeight: "600", fontSize: 14 },

    // Apps tab split pane
    appsPane: { flex: 1, flexDirection: "row" },
    appsLeft: { flex: 6, paddingTop: 8 },
    paneDiv: { width: StyleSheet.hairlineWidth, backgroundColor: colors.separator },
    appsRight: { flex: 4, paddingTop: 8 },

    search: {
      marginHorizontal: 8, marginBottom: 6,
      paddingHorizontal: 12, paddingVertical: 8,
      backgroundColor: colors.card, borderRadius: 10,
      fontSize: 13, color: colors.text,
    },

    appGrid: { paddingHorizontal: 4, paddingBottom: 16 },
    appCell: {
      flex: 1, alignItems: "center",
      paddingVertical: 8, paddingHorizontal: 2,
    },
    appIconWrap: { position: "relative", width: 48, height: 48 },
    appIcon: { width: 48, height: 48, borderRadius: 10 },
    checkBadge: {
      position: "absolute", bottom: -2, right: -2,
      width: 16, height: 16, borderRadius: 8,
      backgroundColor: colors.tint, alignItems: "center", justifyContent: "center",
    },
    appCellName: {
      fontSize: 10, color: colors.subtext, textAlign: "center",
      marginTop: 4, lineHeight: 13,
    },
    appCellNameSelected: { color: colors.tint },

    favHeader: {
      fontSize: 12, fontWeight: "600", color: colors.subtext,
      marginBottom: 6, marginHorizontal: 10, letterSpacing: 0.5,
      textTransform: "uppercase",
    },
    favEmpty: {
      fontSize: 12, color: colors.subtext,
      marginHorizontal: 10, marginTop: 8, lineHeight: 18,
    },
    favRow: {
      flexDirection: "row", alignItems: "center",
      paddingHorizontal: 8, height: 58,
      backgroundColor: colors.card, gap: 8,
    },
    favRowActive: { opacity: 0.8, backgroundColor: colors.bg },
    favIcon: { width: 36, height: 36, borderRadius: 8 },
    iconPlaceholder: { backgroundColor: colors.separator },
    favName: { flex: 1, fontSize: 12, color: colors.text },
    dragHandle: { width: 28, height: 28, alignItems: "center", justifyContent: "center" },

    // Tab bar
    tabBar: {
      flexDirection: "row",
      backgroundColor: colors.card,
      borderTopWidth: StyleSheet.hairlineWidth,
      borderTopColor: colors.separator,
    },
    tabItem: { flex: 1, alignItems: "center", paddingVertical: 8, gap: 2 },
    tabLabel: { fontSize: 10, color: colors.subtext },
    tabLabelActive: { color: colors.tint },
  });
}
