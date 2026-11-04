import { View } from 'react-native';
import { Camera, Check, Eye } from 'lucide-react-native';
import { control, layout, radius, space, useTheme } from '@/theme';
import { Button, Card, EmptyState, PrivacyCard, Reveal, Screen, SectionHeader, Text } from '@/ui';
import { FeatureHero, featureEyebrow } from '@/components/FeatureHero';
import { Ring } from '@/ui/Ring';
import { GazeCalibration } from '@/components/GazeCalibration';
import { useAttune } from '@/state/store';
import { minutes } from '@/lib/format';
import { appDisplayName } from '@/lib/appDisplayName';
import { openPermissionSettings, permissionList } from '@/state/permissionInfo';
import type { AttentionSource } from '@/state/types';

const APPROACHES: { source: AttentionSource; name: string; line: string }[] = [
  { source: 'camera', name: 'On-device gaze', line: 'Both irises, eyelids and head direction are checked across consecutive frames. Blinks, poor tracking and boundary cases stay uncertain. Accuracy varies with position and lighting.' },
  { source: 'hybrid', name: 'Hybrid calibration', line: 'Planned next: short opt-in camera calibration followed by an on-device sensor model.' },
  { source: 'sensors', name: 'Sensors only', line: 'Automatic without camera access: accelerometer, available gyroscope and proximity, plus recent Android interactions. Motion estimates use; it cannot measure where you look.' },
  { source: 'mixed', name: 'Combined observations', line: 'Today includes camera observations and sensor estimates. Unobserved foreground time stays unclassified.' },
  { source: 'usage', name: 'Usage baseline', line: 'Available now. Android foreground time is shown with low confidence and no gaze claim.' },
];

const camera = permissionList.find((p) => p.key === 'camera')!;

export default function AttentionScreen() {
  const { c, feature } = useTheme();
  const accent = feature('attention');
  const day = useAttune((s) => s.attention[0] ?? null);
  const missing = useAttune((s) => s.features.attention.missing);
  const live = useAttune((s) => s.attentionLive);
  const enabled = useAttune((s) => s.features.attention.enabled);

  const grant = () => {
    const first = permissionList.find((p) => missing.includes(p.key)) ?? camera;
    openPermissionSettings(first);
  };

  const hasData = !!day && day.screenOnMin > 0;
  const hasAttentionData = !!day && day.source !== 'usage';
  const maxHour = day ? Math.max(1, ...day.hourly) : 1;
  const barW = (min: number, total: number) => `${Math.max(0, Math.min(100, (min / Math.max(total, 1)) * 100))}%` as const;

  return (
    <Screen
      title="True Screen Time"
      eyebrow={featureEyebrow('attention')}
      eyebrowColor={accent.text}
      back
      hero={
        <FeatureHero feature="attention" onGrant={grant} personaNote="whose report counted every glance at the clock">
          {hasData ? (
            <View style={{ flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space[6], marginTop: space[4] }}>
              <Ring
                size={space[10] * 2}
                stroke={space[3]}
                progress={(hasAttentionData ? day.attendedMin : day.screenOnMin) / Math.max(day.screenOnMin, 1)}
                ghost={1}
                color={c.chart.primary}
              >
                <Eye size={control.iconLarge} color={accent.text} strokeWidth={control.stroke} />
              </Ring>
              <View style={{ flex: 1, minWidth: space[9] * 4, gap: space[5] }}>
                <View style={{ gap: space[2] }}>
                  <Text variant="overline" tone="tertiary" uppercase>
                    Android says
                  </Text>
                  <Text variant="title2" tone="secondary" tabular>
                    {minutes(day.screenOnMin)}
                  </Text>
                </View>
                <View style={{ gap: space[2] }}>
                  <Text variant="overline" color={accent.text} uppercase>
                    {hasAttentionData ? 'Attention estimate' : 'Foreground time'}
                  </Text>
                  <Text
                    variant="stat"
                    tabular
                    accessibilityLabel={hasAttentionData ? `Observed or estimated attention ${minutes(day.attendedMin)}` : `Android foreground time ${minutes(day.screenOnMin)}`}
                  >
                    {minutes(hasAttentionData ? day.attendedMin : day.screenOnMin)}
                  </Text>
                </View>
              </View>
            </View>
          ) : null}
        </FeatureHero>
      }
      bottomInset={space[4]}
    >
      {enabled && live && (
        <Card style={{ gap: space[3] }}>
          <Text variant="bodyMedium">{live.label}</Text>
          <Text variant="callout" tone="secondary">
            {live.source === 'camera' ? 'On-device gaze estimate' : live.source === 'sensors' ? 'Sensor estimate · cannot measure gaze' : 'Android usage / screen state'} · signal confidence {Math.round(live.confidence * 100)}%
          </Text>
          <Text variant="caption" tone="tertiary">{live.reason}. Confidence describes signal strength, not measured accuracy.</Text>
        </Card>
      )}
      {hasAttentionData && (
        <Text variant="callout" tone="secondary">
          {minutes(day.classifiedMin ?? 0)} classified today. Remaining foreground time is unclassified; no attention is inferred for earlier hours.
        </Text>
      )}
      <GazeCalibration enabled={enabled} />
      {!hasData && (
        <Reveal index={1}>
          <Card>
            <EmptyState
              icon={<Eye size={control.iconLarge} color={accent.text} strokeWidth={control.stroke} />}
              title="Nothing measured yet"
              body="Enable Usage access and True Screen Time to compare Android foreground time with attention observations or sensor estimates."
            />
          </Card>
        </Reveal>
      )}

      <SectionHeader title="Through the day" />
      <Reveal index={2}>
        <Card>
          {day && day.hourly.some((v) => v > 0) ? (
            <View accessible accessibilityLabel="Attended minutes per hour across the day">
              <View style={{ flexDirection: 'row', alignItems: 'flex-end', height: control.chart.hourly.compactHeight, gap: control.chart.hourly.gap }}>
                {day.hourly.map((v, h) => (
                  <View key={h} style={{ flex: 1, height: '100%', justifyContent: 'flex-end' }}>
                    <View
                      style={{
                        height: v > 0 ? Math.max(control.chart.hourly.minHeight, (v / maxHour) * control.chart.hourly.compactHeight) : control.chart.hourly.emptyHeight,
                        borderRadius: control.chart.hourly.radius,
                        backgroundColor: v > 0 ? c.chart.primary : c.surfacePressed,
                      }}
                    />
                  </View>
                ))}
              </View>
              <View style={{ flexDirection: 'row', justifyContent: 'space-between', marginTop: space[2] }}>
                {['12a', '6a', '12p', '6p', '11p'].map((t) => (
                  <Text key={t} variant="caption" tone="tertiary">
                    {t}
                  </Text>
                ))}
              </View>
            </View>
          ) : (
            <EmptyState title="No hours yet" body="Attended minutes appear here hour by hour as the day goes on." />
          )}
        </Card>
      </Reveal>

      <SectionHeader title="By app" />
      <Reveal index={3}>
        <Card>
          {day && day.apps.length > 0 ? (
            <View style={{ gap: space[5] }}>
              <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space[4] }}>
                <Legend color={c.chart.primary} label="Attended" />
                <Legend color={c.chart.secondary} label="Glance" />
                <Legend color={c.chart.tertiary} label="Passive" />
              </View>
              {day.apps.map((a) => (
                <View
                  key={a.appPackage}
                  style={{ gap: space[2] }}
                  accessible
                  accessibilityLabel={`${appDisplayName(a.app)}: ${minutes(a.attendedMin)} attended, ${minutes(a.glanceMin)} glance, ${minutes(a.passiveMin)} passive`}
                >
                  <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space[2], justifyContent: 'space-between', alignItems: 'baseline' }}>
                    <Text variant="bodyMedium" style={{ flex: 1, minWidth: space[10] * 2 }}>{appDisplayName(a.app)}</Text>
                    <Text variant="numeral" tone="secondary">
                      {minutes(a.attendedMin)} / {minutes(a.screenOnMin)}
                    </Text>
                  </View>
                  <View style={{ height: control.chart.stackHeight, flexDirection: 'row', borderRadius: radius.pill, overflow: 'hidden', backgroundColor: c.surfaceRaised }}>
                    <View style={{ width: barW(a.attendedMin, a.screenOnMin), backgroundColor: c.chart.primary, borderRightWidth: control.hairline, borderRightColor: c.surfaceRaised }} />
                    <View style={{ width: barW(a.glanceMin, a.screenOnMin), backgroundColor: c.chart.secondary, borderRightWidth: control.hairline, borderRightColor: c.surfaceRaised }} />
                    <View style={{ width: barW(a.passiveMin, a.screenOnMin), backgroundColor: c.chart.tertiary }} />
                  </View>
                </View>
              ))}
            </View>
          ) : (
            <EmptyState title="No apps yet" body="Each app will show how much of its time was real attention, a quick glance, or passive." />
          )}
        </Card>
      </Reveal>

      <SectionHeader title="How we measure" />
      <Reveal index={4}>
        <Card padded={false}>
          {APPROACHES.map((a, n) => {
            const active = day?.source === a.source;
            return (
              <View
                key={a.source}
                accessible
                accessibilityLabel={`${a.name}${active ? ', active' : ''}. ${a.line}`}
                style={{
                  padding: layout.cardPadding,
                  gap: space[3],
                  backgroundColor: active ? accent.soft : undefined,
                  borderBottomWidth: n === APPROACHES.length - 1 ? 0 : control.hairline,
                  borderBottomColor: c.border,
                }}
              >
                <View style={{ flexDirection: 'row', alignItems: 'center', gap: space[2] }}>
                  <Text variant="bodyMedium" style={{ flex: 1 }}>
                    {a.name}
                  </Text>
                  {active && <Check size={control.iconSmall} color={accent.text} strokeWidth={control.stroke} />}
                </View>
                <Text variant="callout" tone="secondary">
                  {a.line}
                </Text>
              </View>
            );
          })}
        </Card>
      </Reveal>
      <Reveal index={5}>
        <Button
          label="Camera permission settings"
          variant="secondary"
          full
          icon={<Camera size={control.iconSmall} color={c.text} strokeWidth={control.stroke} />}
          onPress={() => openPermissionSettings(camera)}
        />
      </Reveal>

      <Reveal index={6}>
        <PrivacyCard
          senses="Foreground apps, accelerometer, available gyroscope and proximity, recent interactions, and optionally camera gaze while the screen is unlocked."
          stays="Reduced daily duration totals and an optional personal gaze profile on this phone. Delete the profile in Personal gaze check. Frames and raw motion readings are not saved."
          never="Photos, video or frames. They are read in memory and dropped."
        />
      </Reveal>
    </Screen>
  );
}

function Legend({ color, label }: { color: string; label: string }) {
  return (
    <View style={{ flexDirection: 'row', alignItems: 'center', gap: space[2] }}>
      <View style={{ width: space[2], height: space[2], borderRadius: radius.pill, backgroundColor: color }} />
      <Text variant="caption" tone="secondary">
        {label}
      </Text>
    </View>
  );
}
