import { useRef, useState } from 'react';
import { ScrollView, View, useWindowDimensions } from 'react-native';
import Animated, {
  Extrapolation,
  interpolate,
  useAnimatedScrollHandler,
  useAnimatedStyle,
  useSharedValue,
  type SharedValue,
} from 'react-native-reanimated';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { router } from 'expo-router';
import { ArrowRight } from 'lucide-react-native';
import { control, features, layout, radius, space, useTheme, type FeatureKey } from '@/theme';
import { Button, FeatureIcon, PressableScale, Text } from '@/ui';
import { FigureEight } from '@/ui/FigureEight';
import { useMotionPreference } from '@/ui/useMotionPreference';
import { haptic } from '@/lib/haptics';

interface Story {
  feature: FeatureKey;
  problem: string;
  answer: string;
}

// One-line problems come straight from the deck personas.
const stories: Story[] = [
  {
    feature: 'notify',
    problem: 'Varun missed the exam-hall change. It sat in a muted group chat.',
    answer: 'Attune reads who is asking and what it says, then surfaces the one message that matters.',
  },
  {
    feature: 'keyboard',
    problem: 'Aryan types in Hinglish. His keyboard keeps fixing words that were never wrong.',
    answer: 'A keyboard that learns how you really write, on your phone, and never sends your words anywhere.',
  },
  {
    feature: 'focus',
    problem: "Lakshya's Do Not Disturb silenced his sister's calls, but not a sale alert in the middle of his viva.",
    answer: 'Focus follows your moment, not the clock: family gets through, the sale alert waits.',
  },
  {
    feature: 'attention',
    problem: "Vivek's report blamed him for seven hours, including every time he glanced at the clock.",
    answer: 'Screen time that counts the time you actually looked, and says so honestly.',
  },
  {
    feature: 'volume',
    problem: "Shivam's phone rang at full volume in a silent library.",
    answer: 'Your ringer follows the room: audible on the street, quiet where it should be.',
  },
];

const loop = ['Sense', 'Understand', 'Adapt', 'Protect', 'Care'];
const PAGES = stories.length + 1;

export default function Onboarding() {
  const { width } = useWindowDimensions();
  const insets = useSafeAreaInsets();
  const { c } = useTheme();
  const reduced = useMotionPreference();
  const x = useSharedValue(0);
  const page = useSharedValue(0);
  const ref = useRef<Animated.ScrollView>(null);
  const [idx, setIdx] = useState(0);

  const onScroll = useAnimatedScrollHandler((e) => {
    x.value = e.contentOffset.x;
    page.value = e.contentOffset.x / width;
  });

  const last = idx === PAGES - 1;
  const goNext = () => {
    if (last) {
      router.push('/onboarding/permissions');
      return;
    }
    ref.current?.scrollTo({ x: (idx + 1) * width, animated: !reduced });
    if (reduced) setIdx(idx + 1);
  };

  return (
    <View style={{ flex: 1, backgroundColor: c.bg }}>
      <Animated.ScrollView
        ref={ref}
        horizontal
        style={{ flex: 1 }}
        pagingEnabled
        showsHorizontalScrollIndicator={false}
        onScroll={onScroll}
        scrollEventThrottle={16}
        onMomentumScrollEnd={(e) => {
          const n = Math.round(e.nativeEvent.contentOffset.x / width);
          if (n !== idx) {
            setIdx(n);
            haptic.tap();
          }
        }}
      >
        <IntroPage index={0} width={width} x={x} top={insets.top} active={idx === 0} reduced={reduced} />
        {stories.map((s, i) => (
          <StoryPage key={s.feature} story={s} index={i + 1} width={width} x={x} top={insets.top} reduced={reduced} />
        ))}
      </Animated.ScrollView>

      <View style={{ width: '100%', maxWidth: layout.maxContent + layout.gutter * 2, alignSelf: 'center', paddingHorizontal: layout.gutter, paddingTop: space[4], paddingBottom: insets.bottom + layout.introBottom, gap: space[5] }}>
        <Dots count={PAGES} page={page} />
        <View style={{ flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between', gap: space[4] }}>
          <PressableScale accessibilityLabel="Skip to setup" onPress={() => router.push('/onboarding/permissions')} hitSlop={control.hitSlop} style={{ minHeight: control.touchTarget, justifyContent: 'center' }}>
            <Text variant="bodyMedium" tone="tertiary">
              Skip
            </Text>
          </PressableScale>
          <Button
            label={last ? 'Set up Attune' : 'Continue'}
            onPress={goNext}
            icon={<ArrowRight size={control.iconSmall} color={c.onAccent} strokeWidth={control.stroke} />}
          />
        </View>
      </View>
    </View>
  );
}

function useParallax(x: SharedValue<number>, index: number, width: number, factor: number, reduced: boolean) {
  return useAnimatedStyle(() => ({
    transform: [
      {
        translateX: reduced ? 0 : interpolate(
          x.value,
          [(index - 1) * width, index * width, (index + 1) * width],
          [width * factor, 0, -width * factor],
          Extrapolation.CLAMP,
        ),
      },
    ],
    opacity: interpolate(x.value, [(index - 0.7) * width, index * width, (index + 0.7) * width], [0, 1, 0], Extrapolation.CLAMP),
  }));
}

function IntroPage({ index, width, x, top, active, reduced }: { index: number; width: number; x: SharedValue<number>; top: number; active: boolean; reduced: boolean }) {
  const { c } = useTheme();
  const big = useParallax(x, index, width, 0.08, reduced);
  const small = useParallax(x, index, width, 0.04, reduced);
  return (
    <ScrollView style={{ width, flexShrink: 0 }} nestedScrollEnabled showsVerticalScrollIndicator={false} contentContainerStyle={{ flexGrow: 1, paddingTop: top + layout.introTop, paddingBottom: layout.introBottom, justifyContent: 'center' }}>
      <View style={{ width: '100%', maxWidth: layout.maxContent + layout.gutter * 2, alignSelf: 'center', paddingHorizontal: layout.gutter }}>
        <Animated.View style={big}>
          <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: space[6] }}>
            <Text variant="overline" tone="tertiary" uppercase>Attune</Text>
            <FigureEight visible={active} />
          </View>
          <Text variant={width < layout.compactWidth ? 'heroCompact' : 'hero'} accessibilityRole="header">
            Same phone, just a bit smarter.
          </Text>
        </Animated.View>
        <Animated.View style={[{ marginTop: space[7], gap: space[3] }, small]}>
          {loop.map((w, i) => (
            <View key={w} style={{ flexDirection: 'row', alignItems: 'center', gap: space[3] }}>
              <Text variant="numeral" tone="tertiary">
                {String(i + 1).padStart(2, '0')}
              </Text>
              <View style={{ height: control.hairline, width: space[5], backgroundColor: c.borderStrong }} />
              <Text variant="bodyMedium">{w}</Text>
            </View>
          ))}
        </Animated.View>
      </View>
    </ScrollView>
  );
}

function StoryPage({ story, index, width, x, top, reduced }: { story: Story; index: number; width: number; x: SharedValue<number>; top: number; reduced: boolean }) {
  const { c, feature } = useTheme();
  const f = features[story.feature];
  const accent = feature(story.feature);
  const big = useParallax(x, index, width, 0.08, reduced);
  const mid = useParallax(x, index, width, 0.04, reduced);
  const small = useParallax(x, index, width, 0.02, reduced);
  return (
    <ScrollView style={{ width, flexShrink: 0 }} nestedScrollEnabled showsVerticalScrollIndicator={false} contentContainerStyle={{ flexGrow: 1, paddingTop: top + layout.introTop, paddingBottom: layout.introBottom, justifyContent: 'center' }}>
      <View style={{ width: '100%', maxWidth: layout.maxContent + layout.gutter * 2, alignSelf: 'center', paddingHorizontal: layout.gutter }}>
        <Animated.View style={[{ flexDirection: 'row', alignItems: 'center', gap: space[3], marginBottom: space[6] }, small]}>
          <FeatureIcon feature={story.feature} size={control.feature.storyIcon} />
          <View style={{ flex: 1, gap: space[1] }}>
            <Text variant="overline" color={accent.text} uppercase>
              {String(f.index).padStart(2, '0')} · {f.persona}
            </Text>
            <Text variant="title3">{f.name}</Text>
          </View>
        </Animated.View>
        <Animated.View style={big}>
          <Text variant="story" accessibilityRole="header">
            {story.problem}
          </Text>
        </Animated.View>
        <Animated.View style={[{ marginTop: space[6], borderTopWidth: control.hairline, borderTopColor: c.borderStrong, paddingTop: space[6] }, mid]}>
          <Text variant="body" tone="secondary">
            {story.answer}
          </Text>
        </Animated.View>
      </View>
    </ScrollView>
  );
}

function Dots({ count, page }: { count: number; page: SharedValue<number> }) {
  return (
    <View style={{ flexDirection: 'row', gap: space[2], alignItems: 'center' }} accessibilityElementsHidden importantForAccessibility="no">
      {Array.from({ length: count }, (_, i) => (
        <Dot key={i} i={i} page={page} />
      ))}
    </View>
  );
}

function Dot({ i, page }: { i: number; page: SharedValue<number> }) {
  const { c } = useTheme();
  const style = useAnimatedStyle(() => {
    const d = Math.abs(page.value - i);
    return {
      width: control.intro.pageDot,
      transform: [{ scaleX: interpolate(d, [0, 1], [1, 0.7], Extrapolation.CLAMP) }],
      opacity: interpolate(d, [0, 1], [1, control.intro.inactiveOpacity], Extrapolation.CLAMP),
    };
  });
  return <Animated.View style={[{ height: control.intro.pageDot, borderRadius: radius.pill, backgroundColor: c.text }, style]} />;
}
