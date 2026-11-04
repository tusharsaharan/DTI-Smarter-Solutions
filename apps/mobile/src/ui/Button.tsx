import type { ReactNode } from 'react';
import { ActivityIndicator, View } from 'react-native';
import { control, radius, space, useTheme } from '@/theme';
import { PressableScale } from './PressableScale';
import { Text } from './Text';

export interface ButtonProps {
  label: string;
  onPress: () => void;
  variant?: 'primary' | 'secondary' | 'ghost';
  /** Retained for source compatibility; primary actions share the charcoal treatment. */
  color?: string;
  icon?: ReactNode;
  loading?: boolean;
  disabled?: boolean;
  full?: boolean;
}

export function Button({ label, onPress, variant = 'primary', icon, loading, disabled, full }: ButtonProps) {
  const { c } = useTheme();
  const bg = variant === 'primary' ? c.accent : variant === 'secondary' ? c.surfaceRaised : 'transparent';
  const fg = variant === 'primary' ? c.onAccent : c.text;
  return (
    <PressableScale
      onPress={onPress}
      disabled={disabled || loading}
      haptics="press"
      accessibilityLabel={label}
      accessibilityState={{ disabled: disabled || loading, busy: loading }}
      style={{
        minHeight: control.buttonHeight,
        maxWidth: '100%',
        paddingHorizontal: space[6],
        paddingVertical: space[4],
        borderRadius: radius.pill,
        backgroundColor: bg,
        borderWidth: variant === 'secondary' ? control.hairline : 0,
        borderColor: c.border,
        alignItems: 'center',
        justifyContent: 'center',
        alignSelf: full ? 'stretch' : 'flex-start',
        opacity: disabled ? 0.4 : 1,
      }}
    >
      <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: space[2], flexShrink: 1 }}>
        {loading ? <ActivityIndicator color={fg} /> : icon}
        <Text variant="bodyMedium" color={fg} align="center" style={{ flexShrink: 1 }}>
          {label}
        </Text>
      </View>
    </PressableScale>
  );
}
