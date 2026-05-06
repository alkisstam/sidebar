import { useCallback, useEffect, useState } from 'react';
import {
  ActivityIndicator,
  FlatList,
  Image,
  StyleSheet,
  Text,
  TextInput,
  TouchableOpacity,
  View,
} from 'react-native';
import Sidebar, { InstalledApp } from '@/modules/sidebar';

export default function AppsScreen() {
  const [apps, setApps] = useState<InstalledApp[]>([]);
  const [favorites, setFavorites] = useState<Set<string>>(new Set());
  const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    const [appList, favs] = await Promise.all([
      Sidebar.getInstalledApps(),
      Sidebar.getFavorites(),
    ]);
    setApps(appList);
    setFavorites(new Set(favs));
    setLoading(false);
  }, []);

  useEffect(() => { load(); }, [load]);

  const toggle = (pkg: string) => {
    setFavorites(prev => {
      const next = new Set(prev);
      next.has(pkg) ? next.delete(pkg) : next.add(pkg);
      return next;
    });
  };

  const save = async () => {
    setSaving(true);
    await Sidebar.saveFavorites([...favorites]);
    setSaving(false);
  };

  const filtered = query
    ? apps.filter(a => a.name.toLowerCase().includes(query.toLowerCase()))
    : apps;

  if (loading) {
    return (
      <View style={[s.root, s.center]}>
        <ActivityIndicator color="#5050cc" size="large" />
      </View>
    );
  }

  return (
    <View style={s.root}>
      <View style={s.header}>
        <Text style={s.title}>Favorites</Text>
        <TouchableOpacity
          style={[s.saveBtn, saving && s.saveBtnDim]}
          onPress={save}
          disabled={saving}
        >
          <Text style={s.saveBtnText}>{saving ? 'Saving…' : 'Save'}</Text>
        </TouchableOpacity>
      </View>
      <TextInput
        style={s.search}
        value={query}
        onChangeText={setQuery}
        placeholder="Search apps…"
        placeholderTextColor="#555"
      />
      <FlatList
        data={filtered}
        keyExtractor={item => item.packageName}
        renderItem={({ item }) => (
          <TouchableOpacity style={s.row} onPress={() => toggle(item.packageName)}>
            <Image
              source={{ uri: `data:image/png;base64,${item.icon}` }}
              style={s.icon}
            />
            <Text style={s.appName} numberOfLines={1}>{item.name}</Text>
            <View style={[s.check, favorites.has(item.packageName) && s.checkOn]}>
              {favorites.has(item.packageName) && <Text style={s.tick}>✓</Text>}
            </View>
          </TouchableOpacity>
        )}
        ItemSeparatorComponent={() => <View style={s.sep} />}
      />
    </View>
  );
}

const ROOT = '#0c0c18';
const CARD = '#16162a';
const ACCENT = '#5050cc';

const s = StyleSheet.create({
  root: { flex: 1, backgroundColor: ROOT },
  center: { justifyContent: 'center', alignItems: 'center' },
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: 24,
    paddingTop: 64,
    paddingBottom: 16,
  },
  title: { fontSize: 28, fontWeight: '700', color: '#fff' },
  saveBtn: { backgroundColor: ACCENT, borderRadius: 8, paddingHorizontal: 16, paddingVertical: 8 },
  saveBtnDim: { opacity: 0.6 },
  saveBtnText: { color: '#fff', fontWeight: '600' },
  search: {
    backgroundColor: CARD,
    color: '#fff',
    marginHorizontal: 16,
    marginBottom: 8,
    borderRadius: 10,
    paddingHorizontal: 16,
    paddingVertical: 10,
    fontSize: 15,
  },
  row: { flexDirection: 'row', alignItems: 'center', paddingHorizontal: 16, paddingVertical: 12 },
  icon: { width: 44, height: 44, borderRadius: 10 },
  appName: { flex: 1, color: '#fff', fontSize: 15, marginLeft: 14 },
  check: {
    width: 24,
    height: 24,
    borderRadius: 12,
    borderWidth: 2,
    borderColor: '#444',
    justifyContent: 'center',
    alignItems: 'center',
  },
  checkOn: { backgroundColor: ACCENT, borderColor: ACCENT },
  tick: { color: '#fff', fontSize: 13, fontWeight: '700' },
  sep: { height: 1, backgroundColor: CARD, marginLeft: 74 },
});
