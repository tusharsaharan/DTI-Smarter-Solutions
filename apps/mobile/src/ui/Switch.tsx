import { useEffect } from 'react';
import { Pressable } from 'react-native';
import Animated, { interpolateColor, useAnimatedStyle, useSharedValue, withSpring } from 'react-native-reanimated';
import { control, motion, useTheme } from '@/theme';
import { haptic } from '@/lib/haptics';

export interface SwitchProps {
  value: boolean;
  onChange: (v: boolean) => void;
  color?: string;
  disabled?: boolean;
  accessibilityLabel?: string;
}

const W = control.switch.width;
const H = control.switch.height;
const KNOB = control.switch.knob;

export function Switch({ value, onChange, disabled, accessibilityLabel }: SwitchProps) {
  const { c } = useTheme();
  const on = c.text;
  const p = useSharedValue(value ? 1 : 0);
  useEffect(() => {
    p.value = withSpring(value ? 1 : 0, motion.spring.snappy);
  }, [value, p]);

  const track = useAnimatedStyle(() => ({
    backgroundColor: interpolateColor(p.value, [0, 1], [c.surfacePressed, on]),
  }));
  const knob = useAnimatedStyle(() => ({
    backgroundColor: interpolateColor(p.value, [0, 1], [c.text, c.onAccent]),
    transform: [{ translateX: p.value * (W - KNOB - (control.switch.inset + control.hairline) * 2) }],
  }));

  return (
    <Pressable
      accessibilityRole="switch"
      accessibilityState={{ checked: value, disabled }}
      accessibilityLabel={accessibilityLabel}
      disabled={disabled}
      hitSlop={control.hitSlop}
      onPress={() => {
        haptic.toggle();
        onChange(!value);
      }}
      style={{ opacity: disabled ? 0.4 : 1 }}
    >
      <Animated.View style={[{ width: W, height: H, borderRadius: H / 2, padding: control.switch.inset, justifyContent: 'center', borderWidth: control.hairline, borderColor: c.textSecondary }, track]}>
        <Animated.View
          style={[
            { width: KNOB, height: KNOB, borderRadius: KNOB / 2 },
            knob,
          ]}
        />
      </Animated.View>
    </Pressable>
  );
}
