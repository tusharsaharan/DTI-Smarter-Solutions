import { View } from 'react-native';
import { router } from 'expo-router';
import { ArrowUpRight } from 'lucide-react-native';
import { control, features, layout, motion, radius, space, useTheme, type FeatureKey } from '@/theme';
import { useAttune } from '@/state/store';
import type { FeatureMode } from '@/state/types';
import { Text } from '@/ui/Text';
import { PressableScale } from '@/ui/PressableScale';

const modeLabel: Record<FeatureMode, string> = {
  full: 'Full',
  reduced: 'Sensors only',
  minimal: 'Minimal',
  off: 'Off',
};

export interface FeatureTileProps {
  feature: FeatureKey;
  variant?: 'wide' | 'compact';
  /** Extra line under the headline (wide only), e.g. top priority message. */
  detail?: string | null;
}

export function FeatureTile({ feature: key, variant = 'compact', detail }: FeatureTileProps) {
  const { c, feature } = useTheme();
  const status = useAttune((s) => s.features[key]);
  const accent = feature(key);
  const meta = features[key];
  const index = String(meta.index).padStart(2, '0');
  const wide = variant === 'wide';
  const live = status.enabled && status.mode !== 'off';

  return (
    <PressableScale
      accessibilityLabel={`${meta.name}. ${status.headline}. ${modeLabel[status.mode]}. Opens ${meta.name}.`}
      onPress={() => router.push(`/feature/${key}` as never)}
      scaleTo={motion.rowPressScale}
      style={{
        minHeight: wide ? control.feature.wideHeight : control.touchTarget,
        borderRadius: wide ? radius.lg : 0,
        backgroundColor: wide ? c.surface : 'transparent',
        borderWidth: wide ? control.hairline : 0,
        borderBottomWidth: control.hairline,
        borderColor: wide ? c.border : c.borderStrong,
        padding: wide ? layout.cardPadding : 0,
        paddingVertical: wide ? layout.cardPadding : space[6],
        gap: space[5],
      }}
    >
      <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' }}>
        <View style={{ flexDirection: 'row', flex: 1, alignItems: 'center', gap: space[3] }}>
          <Text variant="numeral" tone="tertiary">{index}</Text>
          <Text variant="overline" uppercase color={live ? accent.text : c.textTertiary} style={{ flexShrink: 1 }}>{meta.short}</Text>
        </View>
        <ArrowUpRight size={control.iconSmall} color={c.textSecondary} strokeWidth={control.stroke} />
      </View>

      <View style={{ gap: space[2] }}>
        <Text variant={wide ? 'title1' : 'title2'} tone={live ? 'primary' : 'secondary'}>
          {status.headline}
        </Text>
        {wide && detail ? (
          <Text variant="callout" tone="secondary" numberOfLines={2}>
            {detail}
          </Text>
        ) : null}
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: space[2], marginTop: space[2] }}>
          <View
            style={{
              width: control.dot,
              height: control.dot,
              borderRadius: radius.pill,
              backgroundColor: live ? accent.text : c.textTertiary,
              opacity: status.mode === 'reduced' ? 0.55 : 1,
            }}
          />
          <Text variant="caption" tone="tertiary">
            {modeLabel[status.mode]}
          </Text>
        </View>
      </View>
    </PressableScale>
  );
}
