import { View, useWindowDimensions } from 'react-native';
import { router } from 'expo-router';
import { control, featureOrder, features, space, useTheme, type FeatureKey } from '@/theme';
import { useAttune } from '@/state/store';
import { EditorialLink, EditorialTitle, Text } from '@/ui';
import { editorialIndexWidth } from '@/ui/editorialMotion';
import { featureIndexPresentation, latestPriority } from './featureIndexPresentation';

/** A numbered editorial index. Identity stays legible even when every native feature is off. */
export function FeatureIndex() {
  return (
    <View style={{ gap: space[5], marginTop: space[2] }}>
      <Text variant="overline" uppercase tone="tertiary">On this phone</Text>
      <EditorialTitle variant="story">Five ways to adapt.</EditorialTitle>
      <View>
        {featureOrder.map((key) => <FeatureIndexRow key={key} featureKey={key} />)}
      </View>
    </View>
  );
}

function FeatureIndexRow({ featureKey }: { featureKey: FeatureKey }) {
  const { c } = useTheme();
  const { fontScale } = useWindowDimensions();
  const status = useAttune((s) => s.features[featureKey]);
  const top = useAttune((s) => featureKey === 'notify' ? latestPriority(s.notifications) : null);
  const meta = features[featureKey];
  const { enabled, mode, preview, index, accessibilityLabel } = featureIndexPresentation(featureKey, status, top);

  return (
    <EditorialLink onPress={() => router.push(`/feature/${featureKey}` as never)}
      accessibilityLabel={accessibilityLabel}
      accessibilityHint={`Opens ${meta.name}.`}
      testID={`feature-index-${featureKey}`}
      style={{ paddingTop: space[5], gap: space[4] }}>
      <View style={{ flexDirection: 'row', alignItems: 'flex-start', gap: space[3], paddingTop: space[2] }}>
        <Text variant="numeral" tone="tertiary" style={{ minWidth: editorialIndexWidth(index, fontScale), paddingTop: space[2] }}>
          {index}
        </Text>
        <View style={{ flex: 1, minWidth: 0, gap: space[3] }}>
          <Text variant="indexTitle">{meta.name}</Text>
          <Text variant="callout" tone="secondary">{status.headline}</Text>
          {preview && <Text variant="callout" tone="secondary" numberOfLines={2}>{preview}</Text>}
          <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space[2], alignItems: 'center', paddingBottom: space[2] }}>
            <View style={{ width: control.dot, height: control.dot, borderRadius: control.dot, backgroundColor: enabled ? c.success : c.textTertiary }} />
            <Text variant="caption" tone="tertiary">{mode}</Text>
          </View>
        </View>
      </View>
    </EditorialLink>
  );
}
