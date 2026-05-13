import { StyleSheet } from "react-native";

export function makeColors(scheme: 'light' | 'dark') {
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

export type AppColors = ReturnType<typeof makeColors>;

export function makeStyles(colors: AppColors) {
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

export type AppStyles = ReturnType<typeof makeStyles>;
