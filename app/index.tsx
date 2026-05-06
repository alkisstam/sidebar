import { useEffect, useRef, useState } from "react";
import {
  AppState,
  AppStateStatus,
  Pressable,
  ScrollView,
  StyleSheet,
  Switch,
  Text,
  useColorScheme,
  View,
} from "react-native";
import Slider from "@react-native-community/slider";
import { Ionicons } from "@expo/vector-icons";
import { useRouter } from "expo-router";
import Sidebar, { PillSettings } from "../modules/sidebar";

const DEFAULT_PILL: PillSettings = {
  height: 80,
  width: 36,
  position: 0.5,
  side: "left",
  opacity: 1.0,
  theme: "dark",
};

export default function Index() {
  const scheme = useColorScheme();
  const router = useRouter();
  const colors = makeColors(scheme);

  const [permissionGranted, setPermissionGranted] = useState<boolean | null>(null);
  const [serviceEnabled, setServiceEnabled] = useState(false);
  const [pill, setPill] = useState<PillSettings>(DEFAULT_PILL);
  const [saving, setSaving] = useState(false);

  const appState = useRef<AppStateStatus>(AppState.currentState);

  useEffect(() => {
    Promise.all([
      Sidebar.hasOverlayPermission(),
      Sidebar.isServiceEnabled(),
      Sidebar.getPillSettings(),
    ]).then(([perm, svc, pillSettings]) => {
      setPermissionGranted(perm);
      setServiceEnabled(svc);
      setPill(pillSettings);
    });

    const sub = AppState.addEventListener("change", (next) => {
      if (appState.current !== "active" && next === "active") {
        Sidebar.hasOverlayPermission().then(setPermissionGranted);
      }
      appState.current = next;
    });
    return () => sub.remove();
  }, []);

  async function toggleService(value: boolean) {
    setServiceEnabled(value);
    if (value) {
      await Sidebar.startService();
    } else {
      await Sidebar.stopService();
    }
  }

  async function saveHandle() {
    setSaving(true);
    await Sidebar.savePillSettings(pill);
    setSaving(false);
  }

  const s = styles(colors);

  return (
    <ScrollView style={s.scroll} contentContainerStyle={s.content}>
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

      <View style={s.section}>
        <View style={s.row}>
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
      </View>

      <Text style={s.sectionHeader}>HANDLE</Text>
      <View style={s.section}>
        <View style={s.settingRow}>
          <Text style={s.settingLabel}>Side</Text>
          <View style={s.segmented}>
            {(["left", "right"] as const).map((side) => (
              <Pressable
                key={side}
                style={[s.segBtn, pill.side === side && s.segBtnActive]}
                onPress={() => setPill((p) => ({ ...p, side }))}
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
            {(["dark", "light"] as const).map((t) => (
              <Pressable
                key={t}
                style={[s.segBtn, pill.theme === t && s.segBtnActive]}
                onPress={() => setPill((p) => ({ ...p, theme: t }))}
              >
                <Text style={[s.segBtnText, pill.theme === t && s.segBtnTextActive]}>
                  {t.charAt(0).toUpperCase() + t.slice(1)}
                </Text>
              </Pressable>
            ))}
          </View>
        </View>

        <View style={s.separator} />

        <SliderRow
          label="Position"
          min={0} max={1} step={0.01}
          value={pill.position}
          display={pill.position.toFixed(2)}
          leftEdge="Top" rightEdge="Bot"
          onChange={(v) => setPill((p) => ({ ...p, position: v }))}
          colors={colors}
          s={s}
        />

        <View style={s.separator} />

        <SliderRow
          label="Height"
          min={40} max={200} step={1}
          value={pill.height}
          display={`${Math.round(pill.height)}`}
          onChange={(v) => setPill((p) => ({ ...p, height: Math.round(v) }))}
          colors={colors}
          s={s}
        />

        <View style={s.separator} />

        <SliderRow
          label="Width"
          min={6} max={40} step={1}
          value={pill.width}
          display={`${Math.round(pill.width)}`}
          onChange={(v) => setPill((p) => ({ ...p, width: Math.round(v) }))}
          colors={colors}
          s={s}
        />

        <View style={s.separator} />

        <SliderRow
          label="Opacity"
          min={0.1} max={1} step={0.05}
          value={pill.opacity}
          display={pill.opacity.toFixed(2)}
          onChange={(v) => setPill((p) => ({ ...p, opacity: parseFloat(v.toFixed(2)) }))}
          colors={colors}
          s={s}
        />

        <Pressable
          style={[s.saveBtn, saving && s.saveBtnDisabled]}
          onPress={saveHandle}
          disabled={saving}
        >
          <Text style={s.saveBtnText}>{saving ? "Saving…" : "Save Handle Settings"}</Text>
        </Pressable>
      </View>

      <Text style={s.sectionHeader}>APPS</Text>
      <View style={s.section}>
        <Pressable style={s.row} onPress={() => router.push("/favorites")}>
          <Text style={[s.rowLabel, { flex: 1 }]}>Manage Favorites</Text>
          <Ionicons name="chevron-forward" size={18} color={colors.subtext} />
        </Pressable>
      </View>
    </ScrollView>
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
    scroll: { flex: 1, backgroundColor: colors.bg },
    content: { paddingVertical: 24, paddingHorizontal: 16, gap: 0 },
    sectionHeader: {
      fontSize: 12,
      fontWeight: "600",
      color: colors.subtext,
      marginTop: 24,
      marginBottom: 6,
      marginLeft: 4,
      letterSpacing: 0.5,
    },
    section: {
      backgroundColor: colors.card,
      borderRadius: 12,
      overflow: "hidden",
    },
    separator: { height: StyleSheet.hairlineWidth, backgroundColor: colors.separator, marginLeft: 16 },
    row: {
      flexDirection: "row",
      alignItems: "center",
      paddingHorizontal: 16,
      paddingVertical: 14,
    },
    rowLabel: { fontSize: 16, color: colors.text },
    rowSub: { fontSize: 13, color: colors.subtext, marginTop: 2 },
    settingRow: {
      flexDirection: "row",
      alignItems: "center",
      paddingHorizontal: 16,
      paddingVertical: 12,
      gap: 12,
    },
    settingLabel: { fontSize: 16, color: colors.text, width: 72 },
    segmented: { flexDirection: "row", gap: 8 },
    segBtn: {
      paddingHorizontal: 18,
      paddingVertical: 7,
      borderRadius: 8,
      borderWidth: 1,
      borderColor: colors.tint,
    },
    segBtnActive: { backgroundColor: colors.tint },
    segBtnText: { fontSize: 14, color: colors.tint },
    segBtnTextActive: { color: "#fff" },
    sliderWrap: { flex: 1, flexDirection: "row", alignItems: "center", gap: 6 },
    sliderEdge: { fontSize: 12, color: colors.subtext },
    saveBtn: {
      margin: 12,
      backgroundColor: colors.tint,
      borderRadius: 10,
      paddingVertical: 12,
      alignItems: "center",
    },
    saveBtnDisabled: { opacity: 0.5 },
    saveBtnText: { color: "#fff", fontSize: 15, fontWeight: "600" },
    banner: {
      backgroundColor: colors.bannerBg,
      borderRadius: 12,
      padding: 16,
      gap: 8,
    },
    bannerTitle: { fontSize: 15, fontWeight: "700", color: colors.bannerText },
    bannerBody: { fontSize: 14, color: colors.bannerText, lineHeight: 20 },
    bannerBtn: {
      alignSelf: "flex-start",
      backgroundColor: colors.tint,
      borderRadius: 8,
      paddingHorizontal: 16,
      paddingVertical: 8,
      marginTop: 4,
    },
    bannerBtnText: { color: "#fff", fontWeight: "600", fontSize: 14 },
  });
}
