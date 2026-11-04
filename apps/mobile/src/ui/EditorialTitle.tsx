import { View } from 'react-native';
import Animated, { useAnimatedStyle } from 'react-native-reanimated';
import { control } from '@/theme';
import { Text, type AppTextProps } from './Text';
import { useEditorialMotion } from './useEditorialMotion';

/** One semantic heading, naturally wrapped. The mask reserves padding for font ascenders/descenders. */
export function EditorialTitle({ variant = 'display', ...props }: AppTextProps) {
  const { progress } = useEditorialMotion();
  const entrance = useAnimatedStyle(() => ({
    opacity: progress.value,
    transform: [{ translateY: (1 - progress.value) * control.editorial.titleTravel }],
  }));

  return (
    <View style={{ overflow: 'hidden', paddingVertical: control.editorial.maskPadding, marginVertical: -control.editorial.maskPadding }}>
      <Animated.View style={entrance}>
        <Text variant={variant} accessibilityRole="header" textBreakStrategy="balanced" {...props} />
      </Animated.View>
    </View>
  );
}
