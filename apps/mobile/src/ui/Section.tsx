import type { ReactNode } from 'react';
import { View } from 'react-native';
import { control, motion, radius, space, useTheme } from '@/theme';
import { PressableScale } from './PressableScale';
import { Text } from './Text';

/** Section label row: overline title + optional trailing action. */
export function SectionHeader({ title, action, onAction }: { title: string; action?: string; onAction?: () => void }) {
  return (
    <View style={{ flexDirection: 'row', flexWrap: 'wrap', alignItems: 'baseline', justifyContent: 'space-between', gap: space[3], marginTop: space[6] }}>
      <Text variant="bodyMedium" tone="secondary" style={{ flexShrink: 1 }}>
        {title}
      </Text>
      {action && onAction && (
        <PressableScale onPress={onAction} hitSlop={control.hitSlop} accessibilityLabel={action}>
          <Text variant="calloutMedium" tone="secondary">
            {action}
          </Text>
        </PressableScale>
      )}
    </View>
  );
}

/** Settings-style row inside a Card. */
export function Row({
  icon,
  title,
  subtitle,
  trailing,
  onPress,
  last,
}: {
  icon?: ReactNode;
  title: string;
  subtitle?: string;
  trailing?: ReactNode;
  onPress?: () => void;
  last?: boolean;
}) {
  const { c } = useTheme();
  const body = (
    <View
      style={{
        flexDirection: 'row',
        alignItems: 'center',
        gap: space[3],
        minHeight: control.touchTarget,
        paddingVertical: space[4],
        borderBottomWidth: last ? 0 : control.hairline,
        borderBottomColor: c.border,
      }}
    >
      {icon && (
        <View style={{ width: control.feature.icon, height: control.feature.icon, borderRadius: radius.sm, alignItems: 'center', justifyContent: 'center' }}>
          {icon}
        </View>
      )}
      <View style={{ flex: 1, minWidth: 0, gap: space[1] }}>
        <Text variant="bodyMedium">{title}</Text>
        {subtitle && (
          <Text variant="callout" tone="secondary">
            {subtitle}
          </Text>
        )}
      </View>
      {trailing}
    </View>
  );
  // Let Android read all child text, including trailing permission status.
  return onPress ? <PressableScale onPress={onPress} scaleTo={motion.rowPressScale}>{body}</PressableScale> : body;
}

/** Designed empty state: explains what will appear and what's needed. Never fake data. */
export function EmptyState({ icon, title, body, action }: { icon?: ReactNode; title: string; body: string; action?: ReactNode }) {
  return (
    <View style={{ alignItems: 'flex-start', gap: space[3], paddingVertical: space[2] }}>
      {icon}
      <Text variant="title3">{title}</Text>
      <Text variant="callout" tone="secondary">
        {body}
      </Text>
      {action}
    </View>
  );
}
