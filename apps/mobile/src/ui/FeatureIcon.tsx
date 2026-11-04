import { View } from 'react-native';
import { BellRing, Eye, Keyboard, Moon, Volume2, type LucideIcon } from 'lucide-react-native';
import { control, radius, useTheme, type FeatureKey } from '@/theme';

export const featureIcons: Record<FeatureKey, LucideIcon> = {
  notify: BellRing,
  keyboard: Keyboard,
  focus: Moon,
  attention: Eye,
  volume: Volume2,
};

/** Feature glyph on its soft accent tile. */
export function FeatureIcon({ feature, size = control.feature.icon }: { feature: FeatureKey; size?: number }) {
  const { feature: f } = useTheme();
  const accent = f(feature);
  const Icon = featureIcons[feature];
  return (
    <View
      style={{
        width: size,
        height: size,
        borderRadius: size >= control.touchTarget ? radius.md : radius.sm,
        backgroundColor: accent.soft,
        alignItems: 'center',
        justifyContent: 'center',
      }}
    >
      <Icon size={size * 0.5} color={accent.text} strokeWidth={control.stroke} />
    </View>
  );
}
