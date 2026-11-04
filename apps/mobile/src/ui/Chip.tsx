import { View } from 'react-native';
import { control, radius, space, useTheme } from '@/theme';
import { Text } from './Text';

export interface ChipProps {
  label: string;
  /** Accent colour; the chip uses its soft wash as background. */
  color?: string;
  soft?: string;
  dot?: boolean;
  size?: 'sm' | 'md';
}

export function Chip({ label, color, soft, dot, size = 'sm' }: ChipProps) {
  const { c } = useTheme();
  return (
    <View
      style={{
        flexDirection: 'row',
        alignItems: 'center',
        alignSelf: 'flex-start',
        maxWidth: '100%',
        gap: space[2],
        paddingHorizontal: space[3],
        paddingVertical: size === 'sm' ? space[1] : space[2],
        borderRadius: radius.pill,
        backgroundColor: soft ?? c.surfaceRaised,
        borderWidth: soft ? 0 : control.hairline,
        borderColor: c.border,
      }}
    >
      {dot && <View style={{ width: control.dot, height: control.dot, borderRadius: radius.pill, backgroundColor: color ?? c.text }} />}
      <Text variant="caption" color={color ?? c.textSecondary} style={{ flexShrink: 1 }}>
        {label}
      </Text>
    </View>
  );
}
