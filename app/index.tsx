import { Ionicons } from "@expo/vector-icons";
import Slider from "@react-native-community/slider";
import { Image } from "expo-image";
import { Stack } from "expo-router";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  ActivityIndicator,
  Alert,
  Animated,
  AppState,
  AppStateStatus,
  Easing,
  FlatList,
  Linking,
  PanResponder,
  Pressable,
  ScrollView,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  useColorScheme,
  View,
} from "react-native";
import DraggableFlatList, {
  RenderItemParams,
  ScaleDecorator,
} from "react-native-draggable-flatlist";
import ColorPicker, { HueCircular, Panel1 } from "reanimated-color-picker";
import Sidebar, { InstalledApp, OverlaySettings, PillSettings } from "../modules/sidebar";

type Tab = "handle" | "behavior" | "control" | "apps";
type FavItem = { key: string; name: string; icon: string | null };

const TABS: { id: Tab; label: string; icon: string }[] = [
  { id: "handle", label: "Handle", icon: "options-outline" },
  { id: "behavior", label: "Behavior", icon: "settings-outline" },
  { id: "control", label: "Control", icon: "grid-outline" },
  { id: "apps", label: "Apps", icon: "apps-outline" },
];

const DEFAULT_PILL: PillSettings = {
  height: 80, width: 10, position: 0.5, side: "right", opacity: 0.5, theme: "dark", panelColor: "",
};

const PANEL_COLORS: { key: string }[] = [
  { key: "" },
  { key: "#073276ff" },
  { key: "#3c0c94ff" },
  { key: "#65055cff" },
  { key: "#36043fff" },
  { key: "#9abae9ff" },
  { key: "#a9eedeff" },
  { key: "#ede1a6ff" },
];
const DEFAULT_OVERLAY: OverlaySettings = {
  autoHideFullscreen: false, showLabels: true, vibration: true, sensitivity: 16,
  quickControlsEnabled: true, showTorch: true, showAutoRotate: true, showAutoBrightness: true, showRingerMode: true,
  showAllApps: true, showEdit: true,
  gestureSwipeUp: 'none', gestureSwipeDown: 'notifications', gestureDoubleTap: 'none',
  showBrightnessSlider: true, showVolumeSlider: true, showQuickShare: true,
  showPower: true, showQr: true, showDnd: true,
};

export default function Index() {
  const systemScheme = useColorScheme();
  const [appTheme, setAppTheme] = useState<'light' | 'dark'>(systemScheme ?? 'dark');
  const colors = makeColors(appTheme);

  const [activeTab, setActiveTab] = useState<Tab>("handle");
  const activeTabRef = useRef<Tab>("handle");
  useEffect(() => { activeTabRef.current = activeTab; }, [activeTab]);

  const slideAnim = useRef(new Animated.Value(0)).current;

  const changeTab = useCallback((tab: Tab) => {
    const tabOrder: Tab[] = ["handle", "behavior", "control", "apps"];
    const fromIdx = tabOrder.indexOf(activeTabRef.current);
    const toIdx = tabOrder.indexOf(tab);
    if (fromIdx === toIdx) return;
    const fwd = toIdx > fromIdx;
    Animated.timing(slideAnim, {
      toValue: fwd ? -100 : 100, duration: 80,
      easing: Easing.in(Easing.quad), useNativeDriver: true,
    }).start(() => {
      slideAnim.setValue(fwd ? 240 : -240);
      setActiveTab(tab);
      Animated.timing(slideAnim, {
        toValue: 0, duration: 160,
        easing: Easing.out(Easing.cubic), useNativeDriver: true,
      }).start();
    });
  }, [slideAnim]);

  const swipeResponder = useMemo(() =>
    PanResponder.create({
      // Run in bubbling phase so native components (Slider/SeekBar) can claim first.
      onMoveShouldSetPanResponder: (_evt, gs) =>
        Math.abs(gs.dx) > 20 && Math.abs(gs.dx) > Math.abs(gs.dy) * 1.5,
      onPanResponderRelease: (_evt, gs) => {
        if (Math.abs(gs.dx) < 40) return;
        const tabOrder: Tab[] = ["handle", "behavior", "control", "apps"];
        const idx = tabOrder.indexOf(activeTabRef.current);
        if (gs.dx < 0 && idx < tabOrder.length - 1) changeTab(tabOrder[idx + 1]);
        else if (gs.dx > 0 && idx > 0) changeTab(tabOrder[idx - 1]);
      },
    }),
    [changeTab]
  );

  const [permissionGranted, setPermissionGranted] = useState<boolean | null>(null);
  const [serviceEnabled, setServiceEnabled] = useState(false);
  const [dndPerm, setDndPerm] = useState(true);
  const [writePerm, setWritePerm] = useState(true);

  const [pill, setPill] = useState<PillSettings>(DEFAULT_PILL);
  const [overlay, setOverlay] = useState<OverlaySettings>(DEFAULT_OVERLAY);
  const [saving, setSaving] = useState(false);
  const [hexInput, setHexInput] = useState("");
  const [showHexInput, setShowHexInput] = useState(false);
  const [savingOverlay, setSavingOverlay] = useState(false);

  const [allApps, setAllApps] = useState<InstalledApp[]>([]);
  const [listData, setListData] = useState<FavItem[]>([]);
  const [query, setQuery] = useState("");
  const [appsLoading, setAppsLoading] = useState(false);
  const [appsSaving, setAppsSaving] = useState(false);
  const appsTabMountedRef = useRef(false);
  if (activeTab === "apps") appsTabMountedRef.current = true;
  const appsLoadedRef = useRef(false);
  // Holds the saved package list until apps finish loading so we can seed listData.
  const pendingFavsRef = useRef<string[]>([]);

  const appState = useRef<AppStateStatus>(AppState.currentState);

  // Initial data fetch — runs once on mount.
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
      setAppTheme(pillSettings.theme);
      setOverlay(overlaySettings);
      setDndPerm(dnd);
      setWritePerm(write);
      pendingFavsRef.current = favs;
    });
  }, []);

  // AppState listener — re-registered whenever changeTab changes (it's memoised, so rarely).
  useEffect(() => {
    const sub = AppState.addEventListener("change", (next) => {
      if (appState.current !== "active" && next === "active") {
        Promise.all([
          Sidebar.hasOverlayPermission(),
          Sidebar.hasDndPermission(),
          Sidebar.hasWriteSettingsPermission(),
          Sidebar.getLaunchTab(),
        ]).then(([perm, dnd, write, launchTab]) => {
          setPermissionGranted(perm);
          setDndPerm(dnd);
          setWritePerm(write);
          if (launchTab) changeTab(launchTab as Tab);
        });
      }
      appState.current = next;
    });
    return () => sub.remove();
  }, [changeTab]);

  useEffect(() => {
    if (activeTab === "apps" && !appsLoadedRef.current) {
      appsLoadedRef.current = true;
      setAppsLoading(true);
      Sidebar.getInstalledApps()
        .then(apps => {
          setAllApps(apps);
          // Seed the favorites list from saved packages now that we have app data.
          const map = new Map(apps.map(a => [a.packageName, a]));
          setListData(
            pendingFavsRef.current.map(pkg => {
              const app = map.get(pkg);
              return { key: pkg, name: app?.name ?? pkg.split(".").pop() ?? pkg, icon: app?.icon ?? null };
            })
          );
        })
        .catch(() => Alert.alert("Error", "Failed to load installed apps."))
        .finally(() => setAppsLoading(false));
    }
  }, [activeTab]);

  async function toggleService(value: boolean) {
    setServiceEnabled(value);
    try {
      if (value) {
        await Sidebar.startService();
      } else {
        await Sidebar.stopService();
      }
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

  function toggleFav(app: InstalledApp) {
    setListData(prev =>
      prev.some(i => i.key === app.packageName)
        ? prev.filter(i => i.key !== app.packageName)
        : [...prev, { key: app.packageName, name: app.name, icon: app.icon }]
    );
  }

  async function saveFavs() {
    setAppsSaving(true);
    try { await Sidebar.saveFavorites(listData.map(i => i.key)); }
    catch { Alert.alert("Error", "Failed to save favorites."); }
    finally { setAppsSaving(false); }
  }

  const filteredApps = useMemo(() => {
    const q = query.toLowerCase();
    return q ? allApps.filter(a => a.name.toLowerCase().includes(q)) : allApps;
  }, [allApps, query]);

  const favSet = useMemo(() => new Set(listData.map(i => i.key)), [listData]);

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
        <Pressable style={s.appCell} onPress={() => toggleFav(item)}>
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
            This app needs {'"'}Display over other apps{'"'} permission to show the sidebar handle.
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

      <Animated.View style={{ flex: 1, transform: [{ translateX: slideAnim }] }} {...swipeResponder.panHandlers}>

        {/* Handle tab */}
        <View style={{ display: activeTab === "handle" ? "flex" : "none", flex: 1 }}>
          <ScrollView style={s.scroll} contentContainerStyle={s.scrollContent}>
            <View style={s.section}>
              <View style={s.settingRow}>
                <Text style={s.settingLabel}>Side</Text>
                <View style={s.segmented}>
                  {(["left", "right", "both"] as const).map(side => (
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
                      onPress={() => { setPill(p => ({ ...p, theme: t })); setAppTheme(t); }}
                    >
                      <Text style={[s.segBtnText, pill.theme === t && s.segBtnTextActive]}>
                        {t.charAt(0).toUpperCase() + t.slice(1)}
                      </Text>
                    </Pressable>
                  ))}
                </View>
              </View>
              <View style={s.separator} />
              <View style={[s.settingRow, { alignItems: "flex-start", paddingTop: 14, paddingBottom: 14 }]}>
                <Text style={[s.settingLabel, { paddingTop: 5 }]}>Color</Text>
                <View style={{ flex: 1 }}>
                  <View style={{ flexDirection: "row", flexWrap: "wrap", gap: 10 }}>
                    {PANEL_COLORS.map(c => {
                      const sel = pill.panelColor === c.key;
                      return (
                        <Pressable key={c.key} onPress={() => { setPill(p => ({ ...p, panelColor: c.key })); setShowHexInput(false); }}>
                          <View style={{
                            width: 34, height: 34, borderRadius: 17,
                            borderWidth: 2.5,
                            borderColor: sel ? colors.primary : "transparent",
                            justifyContent: "center", alignItems: "center",
                          }}>
                            {c.key === "" ? (
                              <View style={{ width: 26, height: 26, borderRadius: 13, overflow: "hidden", flexDirection: "row" }}>
                                <View style={{ flex: 1, backgroundColor: "#1C1B1F" }} />
                                <View style={{ flex: 1, backgroundColor: "#FFFBFE" }} />
                              </View>
                            ) : (
                              <View style={{ width: 26, height: 26, borderRadius: 13, backgroundColor: c.key }} />
                            )}
                          </View>
                        </Pressable>
                      );
                    })}
                    {/* Custom swatch */}
                    {(() => {
                      const isCustom = pill.panelColor !== "" && !PANEL_COLORS.some(c => c.key === pill.panelColor);
                      return (
                        <Pressable onPress={() => { setHexInput(isCustom ? pill.panelColor : ""); setShowHexInput(v => !v); }}>
                          <View style={{
                            width: 34, height: 34, borderRadius: 17,
                            borderWidth: 2.5,
                            borderColor: isCustom ? colors.primary : "transparent",
                            justifyContent: "center", alignItems: "center",
                          }}>
                            {isCustom ? (
                              <View style={{ width: 26, height: 26, borderRadius: 13, backgroundColor: pill.panelColor }} />
                            ) : (
                              <View style={{ width: 26, height: 26, borderRadius: 13, backgroundColor: colors.surfaceContainerHigh, justifyContent: "center", alignItems: "center" }}>
                                <Ionicons name="pencil-outline" size={13} color={colors.subtext} />
                              </View>
                            )}
                          </View>
                        </Pressable>
                      );
                    })()}
                  </View>
                  {showHexInput && (
                    <View style={{ marginTop: 12 }}>
                      <ColorPicker
                        value={/^#[0-9A-Fa-f]{6}$/.test(pill.panelColor) ? pill.panelColor : '#6750A4'}
                        onChangeJS={({ hex }) => setHexInput(hex.toUpperCase())}
                        onCompleteJS={({ hex }) => {
                          const h = hex.toUpperCase();
                          setPill(p => ({ ...p, panelColor: h }));
                          setHexInput(h);
                        }}
                      >
                        <HueCircular style={{ alignSelf: "center", height: 220 }} containerStyle={{ backgroundColor: colors.surfaceContainer, justifyContent: "center", alignItems: "center" }}>
                          <Panel1 style={{ height: 114, width: 114 }} />
                        </HueCircular>
                      </ColorPicker>
                      <View style={{ flexDirection: "row", alignItems: "center", marginTop: 10, gap: 8 }}>
                        <TextInput
                          value={hexInput}
                          onChangeText={setHexInput}
                          placeholder="#RRGGBB"
                          placeholderTextColor={colors.subtext}
                          autoCapitalize="characters"
                          maxLength={7}
                          style={[s.search, { flex: 1, marginHorizontal: 0, marginBottom: 0, paddingVertical: 8 }]}
                          onSubmitEditing={() => {
                            const hex = hexInput.startsWith("#") ? hexInput : "#" + hexInput;
                            if (/^#[0-9A-Fa-f]{6}$/.test(hex)) {
                              setPill(p => ({ ...p, panelColor: hex.toUpperCase() }));
                              setShowHexInput(false);
                            }
                          }}
                        />
                        <Pressable
                          style={s.grantBtn}
                          onPress={() => {
                            const hex = hexInput.startsWith("#") ? hexInput : "#" + hexInput;
                            if (/^#[0-9A-Fa-f]{6}$/.test(hex)) {
                              setPill(p => ({ ...p, panelColor: hex.toUpperCase() }));
                              setShowHexInput(false);
                            }
                          }}
                        >
                          <Text style={s.grantBtnText}>Apply</Text>
                        </Pressable>
                      </View>
                    </View>
                  )}
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
              <SliderRow label="Width" min={2} max={40} step={1}
                value={pill.width} display={`${Math.round(pill.width)}`}
                onChange={v => setPill(p => ({ ...p, width: Math.round(v) }))}
                colors={colors} s={s} />
              <View style={s.separator} />
              <SliderRow label="Opacity" min={0} max={1} step={0.05}
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
              <View style={s.separator} />
              <Text style={[s.rowLabel, { marginBottom: 10 }]}>Pill gestures</Text>
              {(['gestureSwipeUp', 'gestureSwipeDown', 'gestureDoubleTap'] as const).map((key, i) => {
                const labels = ['Swipe up', 'Swipe down', 'Double tap'];
                const actions = [
                  { value: 'none', label: 'Off' },
                  { value: 'panel', label: 'Panel' },
                  { value: 'notifications', label: 'Notifs' },
                  { value: 'quick_settings', label: 'QS' },
                  { value: 'all_apps', label: 'Apps' },
                ] as const;
                return (
                  <View key={key} style={{ marginBottom: i < 2 ? 10 : 0 }}>
                    <Text style={[s.rowSub, { marginBottom: 6 }]}>{labels[i]}</Text>
                    <View style={s.segmented}>
                      {actions.map(a => (
                        <Pressable
                          key={a.value}
                          style={[s.segBtnSm, overlay[key] === a.value && s.segBtnActive]}
                          onPress={() => setOverlay(p => ({ ...p, [key]: a.value }))}
                        >
                          <Text style={[s.segBtnSmText, overlay[key] === a.value && s.segBtnTextActive]}>
                            {a.label}
                          </Text>
                        </Pressable>
                      ))}
                    </View>
                  </View>
                );
              })}
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
                <Text style={[s.rowLabel, { flex: 1 }]}>Quick controls</Text>
                <Switch
                  value={overlay.quickControlsEnabled}
                  onValueChange={v => setOverlay(p => ({ ...p, quickControlsEnabled: v }))}
                  trackColor={{ true: colors.tint }}
                />
              </View>
              {overlay.quickControlsEnabled && <>
                <View style={s.separator} />
                <View style={s.row}>
                  <Text style={[s.rowLabel, { flex: 1, paddingStart: 16 }]}>Torch</Text>
                  <Switch
                    value={overlay.showTorch}
                    onValueChange={v => setOverlay(p => ({ ...p, showTorch: v }))}
                    trackColor={{ true: colors.tint }}
                  />
                </View>
                <View style={s.separator} />
                <View style={s.row}>
                  <Text style={[s.rowLabel, { flex: 1, paddingStart: 16 }]}>Auto-rotate</Text>
                  <Switch
                    value={overlay.showAutoRotate}
                    onValueChange={v => setOverlay(p => ({ ...p, showAutoRotate: v }))}
                    trackColor={{ true: colors.tint }}
                  />
                </View>
                <View style={s.separator} />
                <View style={s.row}>
                  <Text style={[s.rowLabel, { flex: 1, paddingStart: 16 }]}>Auto-brightness</Text>
                  <Switch
                    value={overlay.showAutoBrightness}
                    onValueChange={v => setOverlay(p => ({ ...p, showAutoBrightness: v }))}
                    trackColor={{ true: colors.tint }}
                  />
                </View>
                <View style={s.separator} />
                <View style={s.row}>
                  <Text style={[s.rowLabel, { flex: 1, paddingStart: 16 }]}>Ringer mode</Text>
                  <Switch
                    value={overlay.showRingerMode}
                    onValueChange={v => setOverlay(p => ({ ...p, showRingerMode: v }))}
                    trackColor={{ true: colors.tint }}
                  />
                </View>
                <View style={s.separator} />
                <View style={s.row}>
                  <Text style={[s.rowLabel, { flex: 1, paddingStart: 16 }]}>Brightness slider</Text>
                  <Switch
                    value={overlay.showBrightnessSlider}
                    onValueChange={v => setOverlay(p => ({ ...p, showBrightnessSlider: v }))}
                    trackColor={{ true: colors.tint }}
                  />
                </View>
                <View style={s.separator} />
                <View style={s.row}>
                  <Text style={[s.rowLabel, { flex: 1, paddingStart: 16 }]}>Volume slider</Text>
                  <Switch
                    value={overlay.showVolumeSlider}
                    onValueChange={v => setOverlay(p => ({ ...p, showVolumeSlider: v }))}
                    trackColor={{ true: colors.tint }}
                  />
                </View>
                <View style={s.separator} />
                <View style={s.row}>
                  <Text style={[s.rowLabel, { flex: 1, paddingStart: 16 }]}>Quick Share</Text>
                  <Switch
                    value={overlay.showQuickShare}
                    onValueChange={v => setOverlay(p => ({ ...p, showQuickShare: v }))}
                    trackColor={{ true: colors.tint }}
                  />
                </View>
                <View style={s.separator} />
                <View style={s.row}>
                  <Text style={[s.rowLabel, { flex: 1, paddingStart: 16 }]}>Power menu</Text>
                  <Switch
                    value={overlay.showPower}
                    onValueChange={v => setOverlay(p => ({ ...p, showPower: v }))}
                    trackColor={{ true: colors.tint }}
                  />
                </View>
                <View style={s.separator} />
                <View style={s.row}>
                  <Text style={[s.rowLabel, { flex: 1, paddingStart: 16 }]}>QR scanner</Text>
                  <Switch
                    value={overlay.showQr}
                    onValueChange={v => setOverlay(p => ({ ...p, showQr: v }))}
                    trackColor={{ true: colors.tint }}
                  />
                </View>
                <View style={s.separator} />
                <View style={s.row}>
                  <Text style={[s.rowLabel, { flex: 1, paddingStart: 16 }]}>Do Not Disturb</Text>
                  <Switch
                    value={overlay.showDnd}
                    onValueChange={v => setOverlay(p => ({ ...p, showDnd: v }))}
                    trackColor={{ true: colors.tint }}
                  />
                </View>
                <View style={s.separator} />
                <View style={s.row}>
                  <Text style={[s.rowLabel, { flex: 1, paddingStart: 16 }]}>All Apps</Text>
                  <Switch
                    value={overlay.showAllApps}
                    onValueChange={v => setOverlay(p => ({ ...p, showAllApps: v }))}
                    trackColor={{ true: colors.tint }}
                  />
                </View>
                <View style={s.separator} />
                <View style={s.row}>
                  <Text style={[s.rowLabel, { flex: 1, paddingStart: 16 }]}>Edit</Text>
                  <Switch
                    value={overlay.showEdit}
                    onValueChange={v => setOverlay(p => ({ ...p, showEdit: v }))}
                    trackColor={{ true: colors.tint }}
                  />
                </View>
              </>}
            </View>
            <Pressable style={[s.saveBtn, savingOverlay && s.saveBtnDisabled]} onPress={saveOverlay} disabled={savingOverlay}>
              <Text style={s.saveBtnText}>{savingOverlay ? "Saving…" : "Save"}</Text>
            </Pressable>
            {(() => {
              const granted = [permissionGranted, dndPerm, writePerm].filter(Boolean).length;
              return (
                <Text style={s.permCount}>{granted} / 3 permissions granted</Text>
              );
            })()}
            <View style={s.section}>
              <View style={s.row}>
                <View style={{ flex: 1 }}>
                  <Text style={s.rowLabel}>Display over other apps</Text>
                  <Text style={s.rowSub}>{permissionGranted ? "Permission granted" : "Required to show the sidebar handle"}</Text>
                </View>
                {permissionGranted
                  ? <Ionicons name="checkmark-circle" size={20} color={colors.primary} />
                  : <Pressable style={s.grantBtn} onPress={Sidebar.requestOverlayPermission}>
                    <Text style={s.grantBtnText}>Grant</Text>
                  </Pressable>
                }
              </View>
              <View style={s.separator} />
              <View style={s.row}>
                <View style={{ flex: 1 }}>
                  <Text style={s.rowLabel}>Do Not Disturb</Text>
                  <Text style={s.rowSub}>{dndPerm ? "Permission granted" : "Required for DND control tile"}</Text>
                </View>
                {dndPerm
                  ? <Ionicons name="checkmark-circle" size={20} color={colors.primary} />
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
                  ? <Ionicons name="checkmark-circle" size={20} color={colors.primary} />
                  : <Pressable style={s.grantBtn} onPress={Sidebar.requestWriteSettingsPermission}>
                    <Text style={s.grantBtnText}>Grant</Text>
                  </Pressable>
                }
              </View>
            </View>
            <Pressable style={[s.saveBtn, s.openSettingsBtn]} onPress={() => Linking.openSettings()}>
              <Ionicons name="settings-outline" size={16} color={colors.onPrimary} style={{ marginRight: 8 }} />
              <Text style={s.saveBtnText}>Open App Settings</Text>
            </Pressable>
          </ScrollView>
        </View>

        {/* Apps tab — lazy-mounted so DraggableFlatList first renders while visible */}
        {appsTabMountedRef.current && <View style={{ display: activeTab === "apps" ? "flex" : "none", flex: 1 }}>
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
                  extraData={favSet}
                  contentContainerStyle={s.appGrid}
                  showsVerticalScrollIndicator={false}
                />
              )}
            </View>

            <View style={s.paneDiv} />

            {/* Right: favorites */}
            <View style={s.appsRight}>
              <Text style={s.favHeader}>Favorites</Text>
              <DraggableFlatList
                data={listData}
                keyExtractor={item => item.key}
                renderItem={renderFavItem}
                onDragEnd={({ data }) => setListData(data)}
                containerStyle={{ flex: 1 }}
                showsVerticalScrollIndicator={false}
                ListEmptyComponent={
                  <Text style={s.favEmpty}>Tap apps on the left to add.</Text>
                }
              />
              <Pressable
                style={[s.saveBtn, s.saveBtnCompact, appsSaving && s.saveBtnDisabled]}
                onPress={saveFavs}
                disabled={appsSaving}
              >
                <Text style={s.saveBtnText}>{appsSaving ? "Saving…" : "Save"}</Text>
              </Pressable>
            </View>
          </View>
        </View>}

      </Animated.View>

      {/* M3 Navigation Bar */}
      <View style={s.tabBar}>
        {TABS.map(tab => (
          <Pressable key={tab.id} style={s.tabItem} onPress={() => changeTab(tab.id)}>
            <View style={[s.navIndicator, activeTab === tab.id && s.navIndicatorActive]}>
              <Ionicons
                name={tab.icon as any}
                size={22}
                color={activeTab === tab.id ? colors.onPrimaryContainer : colors.subtext}
              />
              <Text style={[s.tabLabel, activeTab === tab.id && s.tabLabelActive]}>
                {tab.label}
              </Text>
            </View>
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
  const [trackWidth, setTrackWidth] = useState(0);
  const THUMB_D = 28;
  const ratio = (value - min) / (max - min);
  const fillWidth = trackWidth > 0 ? THUMB_D + ratio * (trackWidth - THUMB_D) : 0;
  return (
    <View style={s.settingRow}>
      <Text style={s.settingLabel}>{label}</Text>
      <View style={s.sliderWrap}>
        {leftEdge && <Text style={s.sliderEdge}>{leftEdge}</Text>}
        <View style={s.sliderTrackWrap} onLayout={e => setTrackWidth(e.nativeEvent.layout.width)}>
          <View pointerEvents="none" style={s.sliderTrackBg} />
          <View pointerEvents="none" style={[s.sliderTrackFill, { width: fillWidth }]} />
          <Slider
            style={StyleSheet.absoluteFillObject}
            minimumValue={min}
            maximumValue={max}
            step={step}
            value={value}
            onValueChange={onChange}
            minimumTrackTintColor="transparent"
            maximumTrackTintColor="transparent"
            thumbTintColor="#FFFFFF"
          />
        </View>
        {rightEdge && <Text style={s.sliderEdge}>{rightEdge}</Text>}
        <Text style={[s.sliderEdge, { width: 36, textAlign: "right" }]}>{display}</Text>
      </View>
    </View>
  );
}

function makeColors(scheme: ReturnType<typeof useColorScheme>) {
  const dark = scheme === "dark";
  return {
    bg: dark ? "#141218" : "#FFFBFE",
    surfaceContainer: dark ? "#211F26" : "#F3EFF7",
    surfaceContainerHigh: dark ? "#2B2930" : "#EDE8F2",
    card: dark ? "#211F26" : "#F3EFF7",
    primary: dark ? "#D0BCFF" : "#6750A4",
    onPrimary: dark ? "#381E72" : "#FFFFFF",
    primaryContainer: dark ? "#4F378B" : "#EADDFF",
    onPrimaryContainer: dark ? "#EADDFF" : "#21005D",
    secondaryContainer: dark ? "#4A4458" : "#E8DEF8",
    onSecondaryContainer: dark ? "#E8DEF8" : "#1D192B",
    text: dark ? "#E6E1E5" : "#1C1B1F",
    subtext: dark ? "#CAC4D0" : "#49454F",
    outline: dark ? "#938F99" : "#79747E",
    separator: dark ? "#49454F" : "#CAC4D0",
    tint: dark ? "#D0BCFF" : "#6750A4",
    errorContainer: dark ? "#8C1D18" : "#F9DEDC",
    onErrorContainer: dark ? "#F2B8B5" : "#410E0B",
  };
}

function styles(colors: ReturnType<typeof makeColors>) {
  return StyleSheet.create({
    root: { flex: 1, backgroundColor: colors.bg },

    serviceCard: {
      flexDirection: "row",
      alignItems: "center",
      backgroundColor: colors.surfaceContainerHigh,
      borderRadius: 20,
      paddingHorizontal: 20,
      paddingVertical: 18,
      margin: 16,
      marginBottom: 8,
    },

    scroll: { flex: 1 },
    scrollContent: { padding: 16, paddingBottom: 32 },

    section: { backgroundColor: colors.surfaceContainer, borderRadius: 16, overflow: "hidden" },
    separator: { height: StyleSheet.hairlineWidth, backgroundColor: colors.separator, marginLeft: 72 },

    row: {
      flexDirection: "row", alignItems: "center",
      paddingHorizontal: 20, paddingVertical: 16,
    },
    rowLabel: { fontSize: 16, color: colors.text },
    rowSub: { fontSize: 13, color: colors.subtext, marginTop: 2 },

    settingRow: {
      flexDirection: "row", alignItems: "center",
      paddingHorizontal: 20, paddingVertical: 14, gap: 16,
    },
    settingLabel: { fontSize: 16, color: colors.text, width: 80 },

    // M3 Segmented Button
    segmented: {
      flex: 1,
      flexDirection: "row",
      borderRadius: 20,
      borderWidth: 1,
      borderColor: colors.outline,
      overflow: "hidden",
    },
    segBtn: { paddingHorizontal: 20, paddingVertical: 9, flex: 1, alignItems: "center" },
    segBtnSm: { paddingHorizontal: 6, paddingVertical: 7, flex: 1, alignItems: "center" },
    segBtnActive: { backgroundColor: colors.secondaryContainer },
    segBtnText: { fontSize: 14, color: colors.outline, letterSpacing: 0.1 },
    segBtnSmText: { fontSize: 12, color: colors.outline },
    segBtnTextActive: { color: colors.onSecondaryContainer, fontWeight: "500" },

    sliderWrap: { flex: 1, flexDirection: "row", alignItems: "center", gap: 6 },
    sliderEdge: { fontSize: 12, color: colors.subtext },
    sliderTrackWrap: { flex: 1, height: 44, justifyContent: "center" },
    sliderTrackBg: {
      position: "absolute", left: 0, right: 0,
      height: 28, borderRadius: 14, backgroundColor: colors.separator,
    },
    sliderTrackFill: {
      position: "absolute", left: 0,
      height: 28, borderRadius: 14, backgroundColor: colors.primary,
    },

    permCount: {
      fontSize: 13, color: colors.subtext, marginBottom: 12,
      textAlign: "center", letterSpacing: 0.3,
    },

    // M3 Filled Button
    saveBtn: {
      margin: 16, backgroundColor: colors.primary,
      borderRadius: 20, paddingVertical: 14, alignItems: "center",
    },
    openSettingsBtn: { marginTop: 8, flexDirection: "row", justifyContent: "center" },
    saveBtnCompact: { margin: 10, paddingVertical: 12 },
    saveBtnDisabled: { opacity: 0.38 },
    saveBtnText: { color: colors.onPrimary, fontSize: 14, fontWeight: "500", letterSpacing: 0.1 },

    // M3 Tonal Button
    grantBtn: {
      backgroundColor: colors.primaryContainer,
      borderRadius: 20, paddingHorizontal: 16, paddingVertical: 9,
    },
    grantBtnText: { color: colors.onPrimaryContainer, fontSize: 14, fontWeight: "500" },

    // M3 Error Banner
    banner: {
      backgroundColor: colors.errorContainer,
      borderRadius: 16, padding: 16, gap: 8, margin: 16, marginBottom: 0,
    },
    bannerTitle: { fontSize: 14, fontWeight: "700", color: colors.onErrorContainer },
    bannerBody: { fontSize: 13, color: colors.onErrorContainer, lineHeight: 20 },
    bannerBtn: {
      alignSelf: "flex-start", backgroundColor: colors.primary,
      borderRadius: 20, paddingHorizontal: 16, paddingVertical: 10, marginTop: 4,
    },
    bannerBtnText: { color: colors.onPrimary, fontWeight: "500", fontSize: 14 },

    appsPane: { flex: 1, flexDirection: "row" },
    appsLeft: { flex: 6, paddingTop: 8 },
    paneDiv: { width: StyleSheet.hairlineWidth, backgroundColor: colors.separator },
    appsRight: { flex: 4, paddingTop: 8 },

    // M3 Search Bar
    search: {
      marginHorizontal: 8, marginBottom: 8,
      paddingHorizontal: 16, paddingVertical: 12,
      backgroundColor: colors.surfaceContainerHigh, borderRadius: 28,
      fontSize: 14, color: colors.text,
    },

    appGrid: { paddingHorizontal: 4, paddingBottom: 16 },
    appCell: {
      flex: 1, alignItems: "center",
      paddingVertical: 10, paddingHorizontal: 2,
    },
    appIconWrap: { position: "relative", width: 52, height: 52 },
    appIcon: { width: 52, height: 52, borderRadius: 12 },
    checkBadge: {
      position: "absolute", bottom: -2, right: -2,
      width: 18, height: 18, borderRadius: 9,
      backgroundColor: colors.primary, alignItems: "center", justifyContent: "center",
    },
    appCellName: {
      fontSize: 10, color: colors.subtext, textAlign: "center",
      marginTop: 4, lineHeight: 13,
    },
    appCellNameSelected: { color: colors.primary },

    favHeader: {
      fontSize: 11, fontWeight: "600", color: colors.subtext,
      marginBottom: 6, marginHorizontal: 12, letterSpacing: 0.5,
      textTransform: "uppercase",
    },
    favEmpty: {
      fontSize: 13, color: colors.subtext,
      marginHorizontal: 12, marginTop: 12, lineHeight: 18,
    },
    favRow: {
      flexDirection: "row", alignItems: "center",
      paddingHorizontal: 12, height: 64,
      backgroundColor: colors.surfaceContainer, gap: 12,
    },
    favRowActive: { backgroundColor: colors.surfaceContainerHigh },
    favIcon: { width: 40, height: 40, borderRadius: 10 },
    iconPlaceholder: { backgroundColor: colors.separator },
    favName: { flex: 1, fontSize: 13, color: colors.text },
    dragHandle: { width: 28, height: 28, alignItems: "center", justifyContent: "center" },

    // M3 Navigation Bar
    tabBar: {
      flexDirection: "row",
      backgroundColor: colors.surfaceContainer,
      paddingBottom: 14,
      paddingTop: 8,
    },
    tabItem: { flex: 1, alignItems: "center" },
    navIndicator: {
      width: 72, height: 52, borderRadius: 26,
      alignItems: "center", justifyContent: "center",
      flexDirection: "column", gap: 2,
    },
    navIndicatorActive: { backgroundColor: colors.primaryContainer },
    tabLabel: { fontSize: 11, color: colors.subtext, letterSpacing: 0.4 },
    tabLabelActive: { color: colors.onPrimaryContainer, fontWeight: "500" },
  });
}
