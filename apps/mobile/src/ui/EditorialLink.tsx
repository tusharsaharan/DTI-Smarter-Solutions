import { useEffect, type ReactNode } from 'react';
import { View, type StyleProp, type ViewStyle } from 'react-native';
import { ArrowUpRight } from 'lucide-react-native';
import Animated, { cancelAnimation, Easing, useAnimatedStyle, useSharedValue, withTiming } from 'react-native-reanimated';
import { control, motion, space, useTheme } from '@/theme';
import { EditorialRule } from './EditorialRule';
import { PressableScale } from './PressableScale';
import { useEditorialMotion } from './useEditorialMotion';

interface EditorialLinkProps {
  children: ReactNode;
  onPress: () => void;
  accessibilityLabel: string;
  accessibilityHint?: string;
  style?: StyleProp<ViewStyle>;
  testID?: string;
}

/** A mobile link, not a hover-only effect: a pressed rule advances and the arrow turns toward the destination. */
export function EditorialLink({ children, style, ...props }: EditorialLinkProps) {
  const { c } = useTheme();
  const { progress, active, reduced } = useEditorialMotion();
  const pressed = useSharedValue(0);
  const titleStyle = useAnimatedStyle(() => ({ transform: [{ translateX: pressed.value * space[1] }] }));
  const arrowStyle = useAnimatedStyle(() => ({
    transform: [{ rotate: `${pressed.value * control.editorial.arrowRotation}deg` }],
  }));

  useEffect(() => {
    if (!active || reduced) {
      cancelAnimation(pressed);
      pressed.value = 0;
    }
    return () => cancelAnimation(pressed);
  }, [active, reduced, pressed]);

  const engage = (value: number) => {
    // SharedValue writes are intentional in native interaction callbacks, not React state mutations.
    // eslint-disable-next-line react-hooks/immutability
    pressed.value = reduced || !active ? value : withTiming(value, {
      duration: motion.duration.fast, easing: Easing.out(Easing.cubic),
    });
  };

  return (
    <PressableScale {...props} scaleTo={1} style={style}
      onPressIn={() => engage(1)} onPressOut={() => engage(0)}
      onFocus={() => engage(1)} onBlur={() => engage(0)}>
      <View style={{ flexDirection: 'row', alignItems: 'flex-start', gap: space[2], minHeight: control.touchTarget }}>
        <Animated.View style={[{ flex: 1, minWidth: 0, paddingRight: space[1] }, titleStyle]}>{children}</Animated.View>
        <Animated.View style={[{ width: control.touchTarget, height: control.touchTarget, alignItems: 'center', justifyContent: 'center' }, arrowStyle]}
          accessible={false} accessibilityElementsHidden importantForAccessibility="no-hide-descendants">
          <ArrowUpRight size={control.icon} color={c.text} strokeWidth={control.stroke} />
        </Animated.View>
      </View>
      <EditorialRule progress={progress} emphasis={pressed} />
    </PressableScale>
  );
}
