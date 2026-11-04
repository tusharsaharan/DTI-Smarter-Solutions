import { useEffect, useState } from 'react';
import { View, useWindowDimensions, type LayoutChangeEvent } from 'react-native';
import type { BottomTabBarProps } from 'expo-router/tabs';
import Animated, { useAnimatedStyle, useSharedValue, withSpring } from 'react-native-reanimated';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { CalendarDays, CircleDot, UserRound, type LucideIcon } from 'lucide-react-native';
import { control, layout, motion, radius, space, useTheme } from '@/theme';
import { PressableScale, Text } from '@/ui';

const icons: Record<string, LucideIcon> = { index: CircleDot, week: CalendarDays, you: UserRound };
const shortLabels: Record<string, string> = { index: 'Today', week: 'Week', you: 'You' };

/** Floating pill tab bar with a spring-sliding active indicator. */
export function TabBar({ state, descriptors, emitter, navigateToTab }: BottomTabBarProps) {
  const { c } = useTheme();
  const { width } = useWindowDimensions();
  const insets = useSafeAreaInsets();
  const [w, setW] = useState(0);
  const slot = w / state.routes.length;
  const x = useSharedValue(0);

  useEffect(() => {
    x.value = withSpring(state.index * slot, motion.spring.smooth);
  }, [state.index, slot, x]);

  const indicator = useAnimatedStyle(() => ({ transform: [{ translateX: x.value }] }));

  return (
    <View
      pointerEvents="box-none"
      style={{ position: 'absolute', left: 0, right: 0, bottom: insets.bottom + space[3], alignItems: 'center' }}
    >
      <View
        onLayout={(e: LayoutChangeEvent) => setW(e.nativeEvent.layout.width - control.tab.inset * 2 - control.hairline * 2)}
        style={{
          flexDirection: 'row',
          width: Math.min(control.tab.width, width - layout.gutter * 2),
          minHeight: layout.tabBarHeight,
          padding: control.tab.inset,
          borderRadius: radius.pill,
          overflow: 'hidden',
          borderWidth: control.hairline,
          borderColor: c.borderStrong,
          backgroundColor: c.surfaceRaised,
        }}
      >
        {slot > 0 && (
          <Animated.View
            style={[
              { position: 'absolute', top: control.tab.inset, bottom: control.tab.inset, left: control.tab.inset, width: slot, borderRadius: radius.pill, backgroundColor: c.accent },
              indicator,
            ]}
          />
        )}
        {state.routes.map((route, i) => {
          const focused = state.index === i;
          const Icon = icons[route.name] ?? CircleDot;
          const label = descriptors[route.key].options.title ?? route.name;
          return (
            <PressableScale
              key={route.key}
              accessibilityRole="tab"
              accessibilityState={{ selected: focused }}
              accessibilityLabel={label}
              onPress={() => {
                const event = emitter.emit({ type: 'tabPress', target: route.key, canPreventDefault: true });
                if (!focused && !event.defaultPrevented) navigateToTab(route.key);
              }}
              style={{ flex: 1, minWidth: 0, minHeight: control.tab.itemHeight, paddingVertical: space[3], paddingHorizontal: space[1], flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: space[1] }}
            >
              <Icon size={control.tab.icon} color={focused ? c.onAccent : c.textSecondary} strokeWidth={control.stroke} />
              {focused && (
                <Text variant="calloutMedium" color={c.onAccent} numberOfLines={1} style={{ flexShrink: 1 }}>
                  {shortLabels[route.name] ?? label}
                </Text>
              )}
            </PressableScale>
          );
        })}
      </View>
    </View>
  );
}

/** Space screens must leave at the bottom so content clears the floating bar. */
export const TAB_BAR_SPACE = layout.tabBarHeight + space[6];
