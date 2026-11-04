import { useState } from 'react';
import { View, useWindowDimensions } from 'react-native';
import { router } from 'expo-router';
import { ChevronDown } from 'lucide-react-native';
import Animated, { useAnimatedStyle } from 'react-native-reanimated';
import { control, layout, radius, space, useTheme } from '@/theme';
import { useAttune } from '@/state/store';
import { clock } from '@/lib/format';
import { EditorialLink, EditorialRule, EditorialTitle, PressableScale, Text } from '@/ui';
import { useEditorialMotion } from '@/ui/useEditorialMotion';
import { editorialIndexWidth } from '@/ui/editorialMotion';
import { contextPresentation } from './contextPresentation';

/** Context folio. The forecast is explicit, and explanations open inline rather than in a modal. */
export function ContextCard() {
  const { c } = useTheme();
  const ctx = useAttune((s) => s.context);
  const read = contextPresentation(ctx);
  const [expanded, setExpanded] = useState(false);

  return (
    <View style={{ backgroundColor: c.surface, borderRadius: radius.lg, padding: layout.cardPadding, gap: space[5] }}>
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between', gap: space[2] }}>
        <Text variant="overline" uppercase tone="secondary">{ctx ? 'Last context read' : 'Context, not connected'}</Text>
        {ctx && <Text variant="caption" tone="tertiary" style={{ flexShrink: 1 }}>{read.capturedAt !== null
          ? `${new Date(read.capturedAt).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' })} · ${clock(read.capturedAt)}` : 'Capture time unknown'}</Text>}
      </View>

      <EditorialTitle variant="story">{read.heading}</EditorialTitle>
      <Text variant="callout" tone="secondary">{read.summary}</Text>

      {ctx ? (
        <>
          <EditorialRule />
          <View style={{ flexDirection: 'row', flexWrap: 'wrap', alignItems: 'baseline', justifyContent: 'space-between', gap: space[3] }}>
            <Text variant="calloutMedium" tone="secondary">Estimate at that read</Text>
            <Text variant="numeral" tabular accessibilityLabel={read.score === null ? 'Estimate unavailable' : `Interruption estimate ${read.score} out of 100`}>
              {read.score === null ? 'Unavailable' : `${read.score} / 100`}
            </Text>
          </View>
          <Text variant="caption" tone="tertiary">This saved estimate is not live status or your DND setting.</Text>
          {read.reasons.length > 0 && (
            <>
              <PressableScale onPress={() => setExpanded((value) => !value)}
                accessibilityLabel="Why this estimate" accessibilityState={{ expanded }}
                style={{ minHeight: control.touchTarget, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: space[3] }}>
                <Text variant="calloutMedium" style={{ flex: 1, minWidth: 0 }}>Why this estimate</Text>
                <View accessible={false} accessibilityElementsHidden importantForAccessibility="no-hide-descendants">
                  <ChevronDown size={control.iconSmall} color={c.text} strokeWidth={control.stroke} style={{ transform: [{ rotate: expanded ? '180deg' : '0deg' }] }} />
                </View>
              </PressableScale>
              {expanded && <ContextReasons reasons={read.reasons} />}
            </>
          )}
          <EditorialLink onPress={() => router.push('/feature/focus')} accessibilityLabel="Open Smart Focus" accessibilityHint="View the real DND setting and context controls.">
            <Text variant="calloutMedium" style={{ paddingTop: space[3] }}>Open Smart Focus</Text>
          </EditorialLink>
        </>
      ) : (
        <EditorialLink onPress={() => router.push('/onboarding')} accessibilityLabel="Review setup">
          <Text variant="calloutMedium" style={{ paddingTop: space[3] }}>Review setup</Text>
        </EditorialLink>
      )}
    </View>
  );
}

function ContextReasons({ reasons }: { reasons: string[] }) {
  const { fontScale } = useWindowDimensions();
  const { progress } = useEditorialMotion();
  const entrance = useAnimatedStyle(() => ({
    opacity: progress.value, transform: [{ translateY: (1 - progress.value) * space[2] }],
  }));
  return (
    <Animated.View style={[{ gap: space[3] }, entrance]}>
      {reasons.map((reason, index) => (
        <View key={reason} style={{ flexDirection: 'row', gap: space[3], alignItems: 'flex-start' }}>
          <Text variant="numeral" tone="tertiary" style={{ minWidth: editorialIndexWidth(String(index + 1).padStart(2, '0'), fontScale) }}>{String(index + 1).padStart(2, '0')}</Text>
          <Text variant="callout" tone="secondary" style={{ flex: 1, minWidth: 0 }}>{reason}</Text>
        </View>
      ))}
    </Animated.View>
  );
}
