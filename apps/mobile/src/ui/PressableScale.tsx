import { useState } from 'react';
import { Pressable, type PressableProps, type StyleProp, type ViewStyle } from 'react-native';
import Animated, { useAnimatedStyle, useSharedValue, withSpring } from 'react-native-reanimated';
import { control, motion, useTheme } from '@/theme';
import { haptic } from '@/lib/haptics';
import { useMotionPreference } from './useMotionPreference';

const AnimatedPressable = Animated.createAnimatedComponent(Pressable);

export interface PressableScaleProps extends Omit<PressableProps, 'style'> {
  style?: StyleProp<ViewStyle>;
  scaleTo?: number;
  haptics?: 'tap' | 'press' | 'toggle' | false;
}

/** Every tappable surface: springs down on press, light haptic on release. */
export function PressableScale({ style, scaleTo = motion.pressScale, haptics = 'tap', onPressIn, onPressOut, onPress, onFocus, onBlur, onHoverIn, onHoverOut, ...rest }: PressableScaleProps) {
  const { c } = useTheme();
  const reduce = useMotionPreference();
  const [focused, setFocused] = useState(false);
  const [hovered, setHovered] = useState(false);
  const scale = useSharedValue(1);
  const aStyle = useAnimatedStyle(() => ({ transform: [{ scale: scale.value }] }));
  return (
    <AnimatedPressable
      accessibilityRole="button"
      {...rest}
      onFocus={(e) => { setFocused(true); onFocus?.(e); }}
      onBlur={(e) => { setFocused(false); onBlur?.(e); }}
      onHoverIn={(e) => { setHovered(true); onHoverIn?.(e); }}
      onHoverOut={(e) => { setHovered(false); onHoverOut?.(e); }}
      onPressIn={(e) => {
        // Reanimated SharedValue mutation is intentional inside the press callback.
        // eslint-disable-next-line react-hooks/immutability
        scale.value = reduce ? 1 : withSpring(scaleTo, motion.spring.snappy);
        onPressIn?.(e);
      }}
      onPressOut={(e) => {
        // eslint-disable-next-line react-hooks/immutability
        scale.value = reduce ? 1 : withSpring(1, motion.spring.smooth);
        onPressOut?.(e);
      }}
      onPress={(e) => {
        if (haptics) haptic[haptics]();
        onPress?.(e);
      }}
      style={[
        style,
        aStyle,
        hovered && !rest.disabled && { opacity: motion.hoverOpacity },
        focused && { outlineColor: c.focus, outlineWidth: control.focusWidth, outlineStyle: 'solid', outlineOffset: control.focusWidth },
      ]}
    />
  );
}
