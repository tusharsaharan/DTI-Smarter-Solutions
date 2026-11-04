import { useMemo, useState } from 'react';
import { View } from 'react-native';
import { Canvas, Path, Skia } from '@shopify/react-native-skia';
import type { SharedValue } from 'react-native-reanimated';
import { control, useTheme } from '@/theme';

/** A fine registration rule, drawn with Skia. Decorative only; text carries every state. */
export function EditorialRule({ progress, emphasis }: { progress?: SharedValue<number>; emphasis?: SharedValue<number> }) {
  const { c } = useTheme();
  const [width, setWidth] = useState(0);
  const path = useMemo(() => {
    const builder = Skia.PathBuilder.Make();
    builder.moveTo(0, control.editorial.ruleHeight / 2);
    builder.lineTo(width, control.editorial.ruleHeight / 2);
    return builder.detach();
  }, [width]);

  return (
    <View onLayout={(e) => setWidth(e.nativeEvent.layout.width)} style={{ height: control.editorial.ruleHeight }}
      pointerEvents="none" accessible={false} accessibilityElementsHidden importantForAccessibility="no-hide-descendants">
      {width > 0 && (
        <Canvas style={{ width, height: control.editorial.ruleHeight }}>
          <Path path={path} style="stroke" strokeWidth={control.hairline} color={c.borderStrong} end={progress ?? 1} />
          {emphasis && <Path path={path} style="stroke" strokeWidth={control.hairline} color={c.focus} end={emphasis} />}
        </Canvas>
      )}
    </View>
  );
}
