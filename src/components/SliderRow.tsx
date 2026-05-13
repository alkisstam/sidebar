import Slider from "@react-native-community/slider";
import { useState } from "react";
import { StyleSheet, Text, View } from "react-native";
import { AppColors, AppStyles } from "../theme";

export function SliderRow({
  label, min, max, step, value, display, leftEdge, rightEdge, onChange, colors, s,
}: {
  label: string; min: number; max: number; step: number; value: number;
  display: string; leftEdge?: string; rightEdge?: string;
  onChange: (v: number) => void;
  colors: AppColors;
  s: AppStyles;
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
