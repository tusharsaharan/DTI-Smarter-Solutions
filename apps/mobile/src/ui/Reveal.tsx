import type { ReactNode } from 'react';
import type { StyleProp, ViewStyle } from 'react-native';
import Animated, { Easing, FadeIn } from 'react-native-reanimated';
import { motion } from '@/theme';
import { useMotionPreference } from './useMotionPreference';

/** Quiet, bounded crossfade. Content never waits for a long entrance sequence. */
export function Reveal({ index = 0, children, style }: { index?: number; children: ReactNode; style?: StyleProp<ViewStyle> }) {
  const reduce = useMotionPreference();
  return (
    <Animated.View
      style={style}
      entering={reduce ? undefined : FadeIn.delay(Math.min(index * motion.stagger, motion.maxStagger))
        .duration(motion.duration.base).easing(Easing.out(Easing.quad))}
    >
      {children}
    </Animated.View>
  );
}
