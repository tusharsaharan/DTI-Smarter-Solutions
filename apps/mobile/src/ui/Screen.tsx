import type { ReactNode } from 'react';
import { View, useWindowDimensions } from 'react-native';
import Animated, {
  Extrapolation,
  interpolate,
  useAnimatedScrollHandler,
  useAnimatedStyle,
  useSharedValue,
} from 'react-native-reanimated';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { router } from 'expo-router';
import { ChevronLeft } from 'lucide-react-native';
import { control, layout, radius, space, useTheme } from '@/theme';
import { PressableScale } from './PressableScale';
import { Text } from './Text';
import { useMotionPreference } from './useMotionPreference';
import { EditorialTitle } from './EditorialTitle';
import { editorialCollapseDistance } from './editorialMotion';

export interface ScreenProps {
  title: string;
  /** Small line above the large title (e.g. "Idea 01"). */
  eyebrow?: string;
  eyebrowColor?: string;
  back?: boolean;
  /** Right-side header slot (icon button). */
  action?: ReactNode;
  /** Extra space at the bottom (e.g. above the tab bar). */
  bottomInset?: number;
  /** Rendered between the large title and the content, scrolls with it (hero visuals). */
  hero?: ReactNode;
  children: ReactNode;
}

const COLLAPSE = layout.headerCollapse;
const contentWidth = layout.maxContent + layout.gutter * 2;

/**
 * Standard page: an editorial title with a quiet, opaque compact header on scroll.
 */
export function Screen({ title, eyebrow, eyebrowColor, back, action, bottomInset = 0, hero, children }: ScreenProps) {
  const { c } = useTheme();
  const { width, fontScale } = useWindowDimensions();
  const insets = useSafeAreaInsets();
  const reduce = useMotionPreference();
  const y = useSharedValue(0);
  const collapse = useSharedValue<number>(COLLAPSE);
  const onScroll = useAnimatedScrollHandler((e) => {
    y.value = e.contentOffset.y;
  });

  const compactTitle = useAnimatedStyle(() => ({
    opacity: interpolate(y.value, [collapse.value - space[4], collapse.value + space[2]], [0, 1], Extrapolation.CLAMP),
    transform: [{ translateY: reduce ? 0 : interpolate(y.value, [collapse.value - space[4], collapse.value + space[2]], [space[2], 0], Extrapolation.CLAMP) }],
  }));
  const headerBg = useAnimatedStyle(() => ({
    opacity: interpolate(y.value, [0, collapse.value], [0, 1], Extrapolation.CLAMP),
  }));
  const largeTitle = useAnimatedStyle(() => ({
    opacity: interpolate(y.value, [Math.max(0, collapse.value - space[7]), collapse.value], [1, 0], Extrapolation.CLAMP),
    transform: [
      { translateY: reduce ? 0 : interpolate(y.value, [-space[10], 0], [space[4], 0], Extrapolation.CLAMP) },
    ],
  }));

  const headerH = insets.top + layout.headerHeight;

  return (
    <View style={{ flex: 1, backgroundColor: c.bg }}>
      <Animated.ScrollView
        onScroll={onScroll}
        scrollEventThrottle={16}
        showsVerticalScrollIndicator={false}
        contentContainerStyle={{
          paddingTop: headerH + space[4],
          paddingBottom: insets.bottom + space[9] + bottomInset,
        }}
      >
        <Animated.View onLayout={(event) => {
          // Reanimated's layout-dependent value is intentionally written from the layout callback.
          // eslint-disable-next-line react-hooks/immutability
          collapse.value = editorialCollapseDistance(event.nativeEvent.layout.height);
        }} style={[{ width: '100%', maxWidth: contentWidth, alignSelf: 'center', paddingHorizontal: layout.gutter, marginBottom: space[7] }, largeTitle]}>
          {eyebrow && (
            <Text variant="overline" uppercase color={eyebrowColor} tone="tertiary" style={{ marginBottom: space[3] }}>
              {eyebrow}
            </Text>
          )}
          <EditorialTitle>
            {title}
          </EditorialTitle>
        </Animated.View>
        {hero && <View style={{ width: '100%', maxWidth: contentWidth, alignSelf: 'center' }}>{hero}</View>}
        <View style={{ width: '100%', maxWidth: contentWidth, alignSelf: 'center', paddingHorizontal: layout.gutter, gap: layout.sectionGap }}>{children}</View>
      </Animated.ScrollView>

      {/* Compact header */}
      <View pointerEvents="box-none" style={{ position: 'absolute', top: 0, left: 0, right: 0, height: headerH }}>
        <Animated.View
          style={[
            { position: 'absolute', inset: 0, backgroundColor: c.bg, borderBottomWidth: control.hairline, borderBottomColor: c.border },
            headerBg,
          ]}
        />
        <View
          pointerEvents="box-none"
          style={{
            marginTop: insets.top,
            height: layout.headerHeight,
            flexDirection: 'row',
            alignItems: 'center',
            width: '100%', maxWidth: contentWidth, alignSelf: 'center',
            paddingHorizontal: layout.gutter,
          }}
        >
          {back && (
            <View style={{ width: control.touchTarget }}>
              <PressableScale
                accessibilityLabel="Back"
                onPress={() => router.back()}
                style={{ width: control.touchTarget, height: control.touchTarget, borderRadius: radius.pill, alignItems: 'flex-start', justifyContent: 'center' }}
              >
                <ChevronLeft size={control.iconLarge} color={c.text} strokeWidth={control.stroke} />
              </PressableScale>
            </View>
          )}
          <Animated.View style={[{ flex: 1, minWidth: 0, alignItems: back && !action ? 'flex-start' : 'center' }, compactTitle]}
            accessible={false} accessibilityElementsHidden importantForAccessibility="no-hide-descendants">
            <Text variant={width < layout.compactWidth || fontScale > 1 ? 'bodyMedium' : 'title3'} numberOfLines={1}>
              {title}
            </Text>
          </Animated.View>
          {action && (
            <View style={{ width: control.touchTarget, alignItems: 'flex-end' }}>{action}</View>
          )}
        </View>
      </View>
    </View>
  );
}
