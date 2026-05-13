import { Ionicons } from "@expo/vector-icons";
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
  Switch,
  Text,
  TextInput,
  View,
} from "react-native";
import DraggableFlatList, {
  RenderItemParams,
  ScaleDecorator,
} from "react-native-draggable-flatlist";
import ColorPicker, { HueCircular, Panel1 } from "reanimated-color-picker";
import Sidebar, { InstalledApp, OverlaySettings, PillSettings } from "../modules/sidebar";
import { SliderRow } from "../src/components/SliderRow";
import { makeColors, makeStyles } from "../src/theme";
import { useAppTheme } from "../src/ThemeContext";

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
  { key: "#073276" },
  { key: "#3C0C94" },
  { key: "#65055C" },
  { key: "#084F36" },
  { key: "#9ABAE9" },
  { key: "#C2EEA9" },
  { key: "#EDE1A6" },
];
const DEFAULT_OVERLAY: OverlaySettings = {
  autoHideFullscreen: false, showLabels: true, vibration: true, sensitivity: 16,
  quickControlsEnabled: true, showTorch: true, showAutoRotate: true, showAutoBrightness: true, showRingerMode: true,
  showAllApps: true, showEdit: true,
  gesturesEnabled: true, gestureSwipeIn: 'panel',
  gestureSwipeUp: 'none', gestureSwipeDown: 'notifications', gestureDoubleTap: 'none',
  showBrightnessSlider: true, showVolumeSlider: true, showQuickShare: true,
  showPower: true, showQr: true, showDnd: true,
};

export default function Index() {
  const { appTheme, themeChoice, setThemeChoice } = useAppTheme();
  const [activeTab, setActiveTab] = useState<Tab>("handle");
  const activeTabRef = useRef<Tab>("handle");

  const slideAnim = useRef(new Animated.Value(0)).current;

  const changeTab = useCallback((tab: Tab) => {
    const tabOrder: Tab[] = ["handle", "behavior", "control", "apps"];
    const fromIdx = tabOrder.indexOf(activeTabRef.current);
    const toIdx = tabOrder.indexOf(tab);
    if (fromIdx === toIdx) return;
    const fwd = toIdx > fromIdx;
    Animated.timing(slideAnim, {
      toValue: fwd ? -60 : 60, duration: 55,
      easing: Easing.in(Easing.cubic), useNativeDriver: true,
    }).start(() => {
      slideAnim.setValue(fwd ? 180 : -180);
      activeTabRef.current = tab;
      setActiveTab(tab);
      Animated.timing(slideAnim, {
        toValue: 0, duration: 200,
        easing: Easing.out(Easing.back(1.4)), useNativeDriver: true,
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
  const [gestureMenuOpen, setGestureMenuOpen] = useState<string | null>(null);
  const [quickControlsExpanded, setQuickControlsExpanded] = useState(false);
  const expandAnim = useRef(new Animated.Value(0)).current;

  const toggleQuickControls = useCallback(() => {
    setQuickControlsExpanded(prev => {
      Animated.timing(expandAnim, {
        toValue: prev ? 0 : 1,
        duration: 200,
        easing: Easing.out(Easing.quad),
        useNativeDriver: false,
      }).start();
      return !prev;
    });
  }, [expandAnim]);

  const applyHex = useCallback(() => {
    const hex = hexInput.startsWith("#") ? hexInput : "#" + hexInput;
    if (/^#[0-9A-Fa-f]{6}$/.test(hex)) {
      setPill(p => ({ ...p, panelColor: hex.toUpperCase() }));
      setShowHexInput(false);
    }
  }, [hexInput]);

  const [allApps, setAllApps] = useState<InstalledApp[]>([]);
  const [listData, setListData] = useState<FavItem[]>([]);
  const [query, setQuery] = useState("");
  const [appsLoading, setAppsLoading] = useState(false);
  const [appsSaving, setAppsSaving] = useState(false);
  const appsTabMountedRef = useRef(false);
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
      setThemeChoice(pillSettings.themeChoice ?? pillSettings.theme);
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
    if (activeTab === "apps") appsTabMountedRef.current = true;
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
    try { await Sidebar.savePillSettings({ ...pill, themeChoice }); }
    catch { Alert.alert("Error", "Failed to save handle settings."); }
    finally { setSaving(false); }
  }

  async function saveOverlay() {
    setSavingOverlay(true);
    try { await Sidebar.saveOverlaySettings(overlay); }
    catch { Alert.alert("Error", "Failed to save behavior settings."); }
    finally { setSavingOverlay(false); }
  }

  const toggleFav = useCallback((app: InstalledApp) => {
    setListData(prev =>
      prev.some(i => i.key === app.packageName)
        ? prev.filter(i => i.key !== app.packageName)
        : [...prev, { key: app.packageName, name: app.name, icon: app.icon }]
    );
  }, []);

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

  const colors = useMemo(() => makeColors(appTheme), [appTheme]);

  const s = useMemo(() => makeStyles(colors), [colors]);

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
    [favSet, s, toggleFav]
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
                  {(["dark", "light", "system"] as const).map(t => (
                    <Pressable
                      key={t}
                      style={[s.segBtn, themeChoice === t && s.segBtnActive]}
                      onPress={() => {
                        setThemeChoice(t);
                        const resolved = t === 'system' ? appTheme : t;
                        setPill(p => ({ ...p, theme: resolved }));
                      }}
                    >
                      <Text style={[s.segBtnText, themeChoice === t && s.segBtnTextActive]}>
                        {t === 'system' ? 'System' : t.charAt(0).toUpperCase() + t.slice(1)}
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
                          onSubmitEditing={applyHex}
                        />
                        <Pressable
                          style={s.grantBtn}
                          onPress={applyHex}
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
              <View style={s.row}>
                <Text style={[s.rowLabel, { flex: 1 }]}>Pill gestures</Text>
                <Switch
                  value={overlay.gesturesEnabled}
                  onValueChange={v => setOverlay(p => ({ ...p, gesturesEnabled: v }))}
                  trackColor={{ true: colors.tint }}
                />
              </View>
              {overlay.gesturesEnabled && (
                ([
                  { key: 'gestureSwipeIn',   label: 'Swipe in'   },
                  { key: 'gestureSwipeUp',   label: 'Swipe up'   },
                  { key: 'gestureSwipeDown', label: 'Swipe down' },
                  { key: 'gestureDoubleTap', label: 'Double tap' },
                ] as const).map(({ key, label }, i, arr) => {
                  const ACTIONS = [
                    { value: 'none',          label: 'Off'           },
                    { value: 'panel',         label: 'Panel'         },
                    { value: 'notifications', label: 'Notifications' },
                    { value: 'quick_settings',label: 'Quick Settings'},
                    { value: 'all_apps',      label: 'All Apps'      },
                  ] as const;
                  const current = ACTIONS.find(a => a.value === overlay[key])?.label ?? 'Off';
                  const isOpen = gestureMenuOpen === key;
                  return (
                    <View key={key}>
                      <View style={s.separator} />
                      <Pressable style={s.row} onPress={() => setGestureMenuOpen(isOpen ? null : key)}>
                        <Text style={[s.rowLabel, { flex: 1, paddingStart: 16 }]}>{label}</Text>
                        <Text style={[s.rowSub, { marginRight: 6 }]}>{current}</Text>
                        <Ionicons
                          name={isOpen ? 'chevron-up' : 'chevron-down'}
                          size={16} color={colors.subtext}
                        />
                      </Pressable>
                      {isOpen && (
                        <View style={{
                          marginHorizontal: 16, marginBottom: 8,
                          backgroundColor: colors.surfaceContainerHigh,
                          borderRadius: 12, overflow: 'hidden',
                        }}>
                          {ACTIONS.map((a, ai) => (
                            <View key={a.value}>
                              {ai > 0 && <View style={[s.separator, { marginLeft: 0 }]} />}
                              <Pressable
                                style={[s.row, overlay[key] === a.value && { backgroundColor: colors.secondaryContainer }]}
                                onPress={() => { setOverlay(p => ({ ...p, [key]: a.value })); setGestureMenuOpen(null); }}
                              >
                                <Text style={[s.rowLabel, { flex: 1 }]}>{a.label}</Text>
                                {overlay[key] === a.value && (
                                  <Ionicons name="checkmark" size={18} color={colors.primary} />
                                )}
                              </Pressable>
                            </View>
                          ))}
                        </View>
                      )}
                    </View>
                  );
                })
              )}
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
              {/* Quick controls header — enable toggle + expand/collapse */}
              <Pressable style={s.row} onPress={toggleQuickControls}>
                <Text style={[s.rowLabel, { flex: 1 }]}>Quick controls</Text>
                <View onStartShouldSetResponder={() => true}>
                  <Switch
                    value={overlay.quickControlsEnabled}
                    onValueChange={v => setOverlay(p => ({ ...p, quickControlsEnabled: v }))}
                    trackColor={{ true: colors.tint }}
                  />
                </View>
                <Animated.View style={{
                  marginLeft: 8,
                  transform: [{ rotate: expandAnim.interpolate({ inputRange: [0, 1], outputRange: ['0deg', '180deg'] }) }],
                }}>
                  <Ionicons name="chevron-down" size={18} color={colors.subtext} />
                </Animated.View>
              </Pressable>
              {/* Animated accordion body */}
              <Animated.View style={{ overflow: 'hidden', maxHeight: expandAnim.interpolate({ inputRange: [0, 1], outputRange: [0, 2000] }) }}>
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
              </Animated.View>
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

