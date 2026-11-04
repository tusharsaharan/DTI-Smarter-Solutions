/**
 * FeatureHero — shared top block for all 5 feature pages. Pass it as <Screen hero={...}>.
 * (Screen supplies the eyebrow + title; this adds tagline, persona, master switch, status, permission banner.)
 *
 * Props:
 *  - feature: FeatureKey            which feature (reads tokens + store `features[feature]`)
 *  - onGrant?: () => void           called by the "Grant" CTA of the missing-permissions banner
 *  - personaNote?: string           tail of the persona line, default "and everyone who lives in a group chat that never sleeps"
 *  - children?: ReactNode           optional extra hero content (big stat, ring) rendered under the status row
 *
 * Helper: `featureEyebrow(key)` returns "Idea 01" for Screen's `eyebrow`.
 * The switch writes store.features[feature].enabled. The mode chip reflects store.features[feature].mode.
 */
import type { ReactNode } from 'react';
import { View } from 'react-native';
import { Lock } from 'lucide-react-native';
import { control, features, layout, radius, space, useTheme, type FeatureKey } from '@/theme';
import { Chip, PressableScale, Reveal, Switch, Text } from '@/ui';
import { useAttune } from '@/state/store';
import { toggleFeature } from '@/state/nativeActions';
import type { FeatureMode, PermissionKey } from '@/state/types';

export const featureEyebrow = (key: FeatureKey) => `Idea ${String(features[key].index).padStart(2, '0')}`;

const MODE_LABEL: Record<FeatureMode, string> = {
  full: 'Full mode',
  reduced: 'Reduced mode',
  minimal: 'Minimal mode',
  off: 'Not running',
};

const PERMISSION_LABEL: Record<PermissionKey, string> = {
  notificationAccess: 'Notification access',
  postNotifications: 'Post notifications',
  dndPolicy: 'Do Not Disturb access',
  usageAccess: 'Usage access',
  activityRecognition: 'Activity recognition',
  calendar: 'Calendar',
  location: 'Location',
  camera: 'Camera',
  microphone: 'Microphone',
  batteryExemption: 'Battery exemption',
  keyboardEnabled: 'Attune as your keyboard',
};

export interface FeatureHeroProps {
  feature: FeatureKey;
  onGrant?: () => void;
  personaNote?: string;
  children?: ReactNode;
}

export function FeatureHero({
  feature,
  onGrant,
  personaNote = 'and everyone who lives in a group chat that never sleeps',
  children,
}: FeatureHeroProps) {
  const { c, feature: f } = useTheme();
  const accent = f(feature);
  const meta = features[feature];
  const status = useAttune((s) => s.features[feature]);

  const toggle = (enabled: boolean) => {
    void toggleFeature(feature, enabled);
  };

  return (
    <View style={{ paddingHorizontal: layout.gutter, gap: space[6], marginBottom: space[7] }}>
      <Reveal index={0} style={{ gap: space[4] }}>
        <Text variant="title2" tone="secondary">
          {meta.tagline}
        </Text>
        <Text variant="callout" tone="tertiary">
          Built for {meta.persona}, {personaNote}
        </Text>
      </Reveal>

      <Reveal index={1}>
        <View
          style={{
            flexDirection: 'row',
            alignItems: 'center',
            gap: space[4],
            paddingVertical: space[6],
            borderTopWidth: control.hairline,
            borderBottomWidth: control.hairline,
            borderColor: c.border,
          }}
        >
          <View style={{ flex: 1, minWidth: 0, gap: space[3] }}>
            <Text variant="bodyMedium">{status.enabled ? 'Enabled' : 'Off'}</Text>
            {status.enabled && <View style={{ flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space[2] }}>
              <Chip label={MODE_LABEL[status.mode]} color={accent.text} soft={accent.soft} dot />
            </View>}
            <Text variant="callout" tone="secondary">
              {status.headline}
            </Text>
          </View>
          <Switch value={status.enabled} onChange={toggle} color={accent.base} accessibilityLabel={`Enable ${meta.name}`} />
        </View>
      </Reveal>

      {status.missing.length > 0 && (
        <Reveal index={2}>
          <View
            accessibilityRole="alert"
            style={{
              gap: space[4],
              padding: layout.cardPadding,
              borderRadius: radius.lg,
              backgroundColor: accent.soft,
            }}
          >
            <View style={{ flexDirection: 'row', alignItems: 'flex-start', gap: space[3] }}>
              <Lock size={control.icon} color={accent.text} strokeWidth={control.stroke} />
              <View style={{ flex: 1, minWidth: 0, gap: space[2] }}>
                <Text variant="calloutMedium">Needs {status.missing.length === 1 ? 'one permission' : `${status.missing.length} permissions`}</Text>
                <Text variant="callout" tone="secondary">
                  {status.missing.map((m) => PERMISSION_LABEL[m]).join(' · ')}
                </Text>
              </View>
            </View>
            {onGrant && (
              <PressableScale
                onPress={onGrant}
                accessibilityLabel="Grant permissions"
                style={{ alignSelf: 'flex-start', paddingHorizontal: space[6], paddingVertical: space[3], minHeight: control.touchTarget, borderRadius: radius.pill, backgroundColor: c.accent, justifyContent: 'center' }}
              >
                <Text variant="calloutMedium" color={c.onAccent}>
                  Grant
                </Text>
              </PressableScale>
            )}
          </View>
        </Reveal>
      )}

      {children}
    </View>
  );
}
