import { View } from 'react-native';
import { ShieldCheck } from 'lucide-react-native';
import { control, space, useTheme } from '@/theme';
import { Card } from './Card';
import { Text } from './Text';

export interface PrivacyCardProps {
  senses: string;
  stays: string;
  never: string;
  onWipe?: () => void;
}

/** Per-feature "Protect" card: what is sensed, where it stays, what never leaves. */
export function PrivacyCard({ senses, stays, never, onWipe }: PrivacyCardProps) {
  const { c } = useTheme();
  const rows: [string, string][] = [
    ['Senses', senses],
    ['Stays', stays],
    ['Never', never],
  ];
  return (
    <Card>
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: space[2], marginBottom: space[4] }}>
        <ShieldCheck size={control.iconSmall} color={c.success} strokeWidth={control.stroke} />
        <Text variant="calloutMedium">Private by design</Text>
      </View>
      <View style={{ gap: space[4] }}>
        {rows.map(([k, v]) => (
          <View key={k} style={{ gap: space[1] }}>
            <Text variant="caption" tone="tertiary" uppercase>
              {k}
            </Text>
            <Text variant="callout" tone="secondary">
              {v}
            </Text>
          </View>
        ))}
      </View>
      {onWipe && (
        <Text variant="calloutMedium" tone="danger" onPress={onWipe} style={{ marginTop: space[4] }} accessibilityRole="button">
          Delete everything this feature learned
        </Text>
      )}
    </Card>
  );
}
