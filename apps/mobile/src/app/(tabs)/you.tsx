import { useState } from 'react';
import { Alert, View } from 'react-native';
import { ChevronRight, ShieldCheck, Trash2 } from 'lucide-react-native';
import { control, featureOrder, features, space, useTheme } from '@/theme';
import { Card, Chip, FeatureIcon, Reveal, Row, Screen, SectionHeader, Switch, Text } from '@/ui';
import { TAB_BAR_SPACE } from '@/components/TabBar';
import { useAttune } from '@/state/store';
import { toggleFeature } from '@/state/nativeActions';
import { openPermissionSettings, permissionList } from '@/state/permissionInfo';
import { clearDesignPreview, loadDesignPreview } from '@/state/preview';
import { wipeAll } from '@/state/persist';
import { refreshFocusState } from '@/state/focusActions';
import { haptic } from '@/lib/haptics';
import type { PermissionStatus } from '@/state/types';

const STATUS: Record<PermissionStatus, string> = { granted: 'Granted', denied: 'Denied', unknown: 'Not checked' };

const TEAM = ['Aman', 'Prince', 'Riya', 'Shreya', 'Rahul', 'Yash', 'Tushar', 'Rakesh', 'Omprakash', 'Varshith'];

export default function You() {
  const { c } = useTheme();
  const permissions = useAttune((s) => s.permissions);
  const enabled = useAttune((s) => s.features);
  const [preview, setPreview] = useState(false);
  const [deleting, setDeleting] = useState(false);

  const confirmWipe = () => {
    if (deleting) return;
    haptic.warn();
    Alert.alert('Delete all data?', 'This clears your stored data and resets settings. Automatic Focus stops; manual DND stays unchanged. It cannot be undone.', [
      { text: 'Cancel', style: 'cancel' },
      { text: 'Delete', style: 'destructive', onPress: () => {
        setDeleting(true);
        void wipeAll().catch(async (error: unknown) => {
          await refreshFocusState().catch(() => {});
          Alert.alert('Deletion not complete', error instanceof Error ? error.message : 'Some data could not be cleared. Reopen Attune and try again.');
        }).finally(() => setDeleting(false));
      } },
    ]);
  };

  const dot = (s: PermissionStatus) => (s === 'granted' ? c.success : s === 'denied' ? c.danger : c.textTertiary);

  return (
    <Screen title="You" eyebrow="Attune" bottomInset={TAB_BAR_SPACE}>
      <Reveal index={0}>
        <Text variant="body" tone="secondary">
          No account, no profile. Attune works for you on this phone and nowhere else.
        </Text>
      </Reveal>

      <SectionHeader title="Features" />
      <Reveal index={1}>
        <Card>
          {featureOrder.map((k, n) => (
            <Row
              key={k}
              last={n === featureOrder.length - 1}
              icon={<FeatureIcon feature={k} size={control.feature.icon} />}
              title={features[k].name}
              subtitle={enabled[k].enabled ? enabled[k].headline : 'Off'}
              trailing={
                <Switch
                  value={enabled[k].enabled}
                   onChange={(v) => void toggleFeature(k, v)}
                  color={getFeatureColor(k, c)}
                  accessibilityLabel={`${features[k].name} on or off`}
                />
              }
            />
          ))}
        </Card>
      </Reveal>

      <SectionHeader title="Permissions" />
      <Reveal index={2}>
        <Card>
          {permissionList.map((p, n) => {
            const st = permissions[p.key];
            return (
              <Row
                key={p.key}
                last={n === permissionList.length - 1}
                title={p.title}
                subtitle={`${p.optional ? 'Optional · ' : ''}${STATUS[st]}`}
                onPress={() => openPermissionSettings(p)}
                trailing={
                  <View style={{ flexDirection: 'row', alignItems: 'center', gap: space[2] }}>
                    <View style={{ width: space[2], height: space[2], borderRadius: space[2], backgroundColor: dot(st) }} />
                    <ChevronRight size={control.iconSmall} color={c.textTertiary} strokeWidth={control.stroke} />
                  </View>
                }
              />
            );
          })}
        </Card>
        <Text variant="callout" tone="tertiary" style={{ marginTop: space[4] }}>
          Tap a row to open the matching Android settings screen.
        </Text>
      </Reveal>

      <SectionHeader title="Privacy" />
      <Reveal index={3}>
        <Card>
          <View style={{ flexDirection: 'row', alignItems: 'flex-start', gap: space[3], marginBottom: space[4] }}>
            <ShieldCheck size={control.icon} color={c.success} strokeWidth={control.stroke} />
            <Text variant="title3" style={{ flex: 1, minWidth: 0 }}>Your personal data stays here</Text>
          </View>
          <Text variant="callout" tone="secondary">
            Messages, typing, audio and camera frames are processed on the device and never uploaded. Only anonymous model updates may be shared, and only if you join.
          </Text>
          <Row
            last
            onPress={confirmWipe}
            icon={<Trash2 size={control.iconSmall} color={c.danger} strokeWidth={control.stroke} />}
            title={deleting ? 'Deleting on this phone…' : 'Delete all data'}
            subtitle={deleting ? 'Waiting for storage and Android confirmation' : 'Clear everything Attune stored here'}
          />
        </Card>
      </Reveal>

      <SectionHeader title="About" />
      <Reveal index={4}>
        <Card>
          <Text variant="heroCompact">Attune</Text>
          <Text variant="callout" tone="secondary" style={{ marginTop: space[3] }}>
            DTI Project, Design Thinking. Same phone, just a bit smarter.
          </Text>
          <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space[2], marginTop: space[5] }}>
            {TEAM.map((n) => (
              <Chip key={n} label={n} />
            ))}
          </View>
        </Card>
      </Reveal>

      {__DEV__ && (
        <Reveal index={5}>
          <Card>
            <Row
              last
              title="Design preview"
              subtitle="Fill screens with sample data. Dev builds only."
              trailing={
                <Switch
                  value={preview}
                  onChange={(v) => {
                    setPreview(v);
                    if (v) loadDesignPreview();
                    else clearDesignPreview();
                  }}
                  accessibilityLabel="Design preview data"
                />
              }
            />
          </Card>
        </Reveal>
      )}
    </Screen>
  );
}

function getFeatureColor(k: (typeof featureOrder)[number], c: ReturnType<typeof useTheme>['c']) {
  return c.feature[k].base;
}
