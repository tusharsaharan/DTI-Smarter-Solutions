import { useEffect, useMemo, useState } from 'react';
import { AppState, View } from 'react-native';
import { useIsFocused } from 'expo-router';
import { Canvas, Circle, Path, Skia } from '@shopify/react-native-skia';
import { useDerivedValue, useFrameCallback, useSharedValue } from 'react-native-reanimated';
import { control, useTheme } from '@/theme';
import { advanceFigureEight, canAnimateFigureEight, figureEightPoint } from './figureEightMotion';
import { useMotionPreference } from './useMotionPreference';

/** Quiet visual echo of Sense → Understand → Adapt → Protect → Care. No background animation. */
export function FigureEight({ visible }: { visible: boolean }) {
  const { c } = useTheme();
  const focused = useIsFocused();
  const reduced = useMotionPreference();
  const [foreground, setForeground] = useState(AppState.currentState === 'active');
  const phase = useSharedValue(0);
  const frame = useFrameCallback((info) => {
    phase.value = advanceFigureEight(phase.value, info.timeSincePreviousFrame);
  }, false);

  useEffect(() => {
    const subscription = AppState.addEventListener('change', (state) => setForeground(state === 'active'));
    return () => subscription.remove();
  }, []);

  useEffect(() => {
    frame.setActive(canAnimateFigureEight(visible, focused, foreground, reduced));
    return () => frame.setActive(false);
  }, [visible, focused, foreground, reduced, frame]);

  const path = useMemo(() => {
    const builder = Skia.PathBuilder.Make();
    const start = figureEightPoint(0);
    builder.moveTo(start.x, start.y);
    for (let i = 1; i <= control.intro.pathSegments; i++) {
      const point = figureEightPoint(i / control.intro.pathSegments);
      builder.lineTo(point.x, point.y);
    }
    builder.close();
    return builder.detach();
  }, []);
  const dotX = useDerivedValue(() => figureEightPoint(phase.value).x);
  const dotY = useDerivedValue(() => figureEightPoint(phase.value).y);

  return (
    <View accessible={false} accessibilityElementsHidden importantForAccessibility="no-hide-descendants" pointerEvents="none">
      <Canvas style={{ width: control.intro.pathWidth, height: control.intro.pathHeight }}>
        <Path path={path} color={c.decorative} style="stroke" strokeWidth={control.intro.trackWidth} />
        <Circle cx={dotX} cy={dotY} r={control.intro.dot / 2} color={c.focus} />
      </Canvas>
    </View>
  );
}
