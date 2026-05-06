import { useCallback, useEffect, useState } from 'react';
import { AppState, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import Slider from '@react-native-community/slider';
import Sidebar, { PillSettings } from '@/modules/sidebar';

const DEFAULT_PILL: PillSettings = { height: 80, width: 36, position: 0.5, side: 'right' };

export default function HomeScreen() {
  const [hasPermission, setHasPermission] = useState(false);
  const [running, setRunning] = useState(false);
  const [pill, setPill] = useState<PillSettings>(DEFAULT_PILL);

  const refresh = useCallback(async () => {
    const [perm, enabled, pillSettings] = await Promise.all([
      Sidebar.hasOverlayPermission(),
      Sidebar.isServiceEnabled(),
      Sidebar.getPillSettings(),
    ]);
    setHasPermission(perm);
    setRunning(enabled);
    setPill(pillSettings);
  }, []);

  useEffect(() => {
    refresh();
    const sub = AppState.addEventListener('change', s => {
      if (s === 'active') refresh();
    });
    return () => sub.remove();
  }, [refresh]);

  const toggleService = async () => {
    if (running) {
      await Sidebar.stopService();
      setRunning(false);
    } else {
      await Sidebar.startService();
      setRunning(true);
    }
  };

  const updatePill = async (patch: Partial<PillSettings>) => {
    const next = { ...pill, ...patch };
    setPill(next);
    await Sidebar.savePillSettings(next);
  };

  return (
    <View style={s.root}>
      <Text style={s.title}>Sidebar</Text>

      <View style={s.card}>
        <Text style={s.label}>OVERLAY PERMISSION</Text>
        <View style={[s.pill, hasPermission ? s.pillOk : s.pillWarn]}>
          <Text style={s.pillText}>{hasPermission ? 'Granted' : 'Not granted'}</Text>
        </View>
        {!hasPermission && (
          <TouchableOpacity style={s.btn} onPress={() => Sidebar.requestOverlayPermission()}>
            <Text style={s.btnText}>Grant permission</Text>
          </TouchableOpacity>
        )}
      </View>

      <View style={s.card}>
        <Text style={s.label}>SIDEBAR SERVICE</Text>
        <TouchableOpacity
          style={[s.toggle, running ? s.toggleOn : s.toggleOff, !hasPermission && s.toggleDisabled]}
          onPress={hasPermission ? toggleService : undefined}
          activeOpacity={hasPermission ? 0.75 : 1}
        >
          <Text style={s.toggleText}>{running ? 'Running — tap to stop' : 'Stopped — tap to start'}</Text>
        </TouchableOpacity>
        {!hasPermission && <Text style={s.hint}>Grant overlay permission first</Text>}
      </View>

      <View style={s.card}>
        <Text style={s.label}>PILL SETTINGS</Text>

        <Text style={s.settingLabel}>Height  <Text style={s.settingValue}>{pill.height} dp</Text></Text>
        <Slider
          style={s.slider}
          value={pill.height}
          minimumValue={40}
          maximumValue={140}
          step={5}
          minimumTrackTintColor={ACCENT}
          maximumTrackTintColor="#333"
          thumbTintColor={ACCENT}
          onSlidingComplete={v => updatePill({ height: v })}
        />

        <Text style={s.settingLabel}>Width  <Text style={s.settingValue}>{pill.width} dp</Text></Text>
        <Slider
          style={s.slider}
          value={pill.width}
          minimumValue={16}
          maximumValue={60}
          step={2}
          minimumTrackTintColor={ACCENT}
          maximumTrackTintColor="#333"
          thumbTintColor={ACCENT}
          onSlidingComplete={v => updatePill({ width: v })}
        />

        <Text style={s.settingLabel}>Vertical position</Text>
        <Slider
          style={s.slider}
          value={pill.position}
          minimumValue={0.05}
          maximumValue={0.95}
          minimumTrackTintColor={ACCENT}
          maximumTrackTintColor="#333"
          thumbTintColor={ACCENT}
          onSlidingComplete={v => updatePill({ position: v })}
        />

        <Text style={s.settingLabel}>Side</Text>
        <View style={s.sideRow}>
          {(['left', 'right'] as const).map(side => (
            <TouchableOpacity
              key={side}
              style={[s.sideBtn, pill.side === side && s.sideBtnActive]}
              onPress={() => updatePill({ side })}
            >
              <Text style={[s.sideBtnText, pill.side === side && s.sideBtnTextActive]}>
                {side.charAt(0).toUpperCase() + side.slice(1)}
              </Text>
            </TouchableOpacity>
          ))}
        </View>
      </View>

      <Text style={s.footer}>
        Once started, a pull-tab appears on the screen edge. Tap it to open the sidebar from any app.
      </Text>
    </View>
  );
}

const ROOT = '#0c0c18';
const CARD = '#16162a';
const ACCENT = '#5050cc';

const s = StyleSheet.create({
  root: { flex: 1, backgroundColor: ROOT, padding: 24, paddingTop: 64 },
  title: { fontSize: 34, fontWeight: '700', color: '#fff', marginBottom: 32 },
  card: { backgroundColor: CARD, borderRadius: 16, padding: 20, marginBottom: 14 },
  label: { color: '#888', fontSize: 11, letterSpacing: 1.2, marginBottom: 12 },
  pill: { alignSelf: 'flex-start', borderRadius: 8, paddingHorizontal: 12, paddingVertical: 5, marginBottom: 14 },
  pillOk: { backgroundColor: '#1a4a2a' },
  pillWarn: { backgroundColor: '#4a1a1a' },
  pillText: { color: '#fff', fontSize: 13 },
  btn: { backgroundColor: ACCENT, borderRadius: 10, padding: 14, alignItems: 'center' },
  btnText: { color: '#fff', fontWeight: '600', fontSize: 15 },
  toggle: { borderRadius: 10, padding: 14, alignItems: 'center' },
  toggleOn: { backgroundColor: '#1e5c3a' },
  toggleOff: { backgroundColor: '#2a2a3a' },
  toggleDisabled: { opacity: 0.4 },
  toggleText: { color: '#fff', fontWeight: '600', fontSize: 15 },
  hint: { color: '#666', fontSize: 12, marginTop: 10, textAlign: 'center' },
  settingLabel: { color: '#aaa', fontSize: 13, marginBottom: 4, marginTop: 12 },
  settingValue: { color: '#fff' },
  slider: { width: '100%', height: 36, marginBottom: 4 },
  sideRow: { flexDirection: 'row', gap: 10, marginTop: 8 },
  sideBtn: {
    flex: 1, borderRadius: 10, padding: 12, alignItems: 'center',
    backgroundColor: '#2a2a3a',
  },
  sideBtnActive: { backgroundColor: ACCENT },
  sideBtnText: { color: '#666', fontWeight: '600' },
  sideBtnTextActive: { color: '#fff' },
  footer: { color: '#4a4a5a', fontSize: 13, lineHeight: 20, textAlign: 'center', marginTop: 24 },
});
