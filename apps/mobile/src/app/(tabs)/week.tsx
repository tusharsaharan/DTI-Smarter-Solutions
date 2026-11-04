import { useMemo, useState } from 'react';
import { View, useWindowDimensions } from 'react-native';
import { BarChart3, Eye, Moon } from 'lucide-react-native';
import { control, layout, radius, space, useTheme } from '@/theme';
import { useAttune } from '@/state/store';
import { minutes, percent } from '@/lib/format';
import { Screen } from '@/ui/Screen';
import { Reveal } from '@/ui/Reveal';
import { Text } from '@/ui/Text';
import { Ring } from '@/ui/Ring';
import { SectionHeader, EmptyState } from '@/ui/Section';
import { TAB_BAR_SPACE } from '@/components/TabBar';
import type { FocusEvent, VolumeSample } from '@/state/types';
import { numeralVariant, numeralWidth } from '@/ui/numeralVariant';

/** Minutes the phone spent in Do Not Disturb, from the on/off log. */
function dndMinutes(log: FocusEvent[], active: boolean, now: number): number {
  const edges = log.filter((e) => e.action === 'dnd_on' || e.action === 'dnd_off').sort((a, b) => a.at - b.at);
  let total = 0;
  let onAt: number | null = null;
  for (const e of edges) {
    if (e.action === 'dnd_on') onAt ??= e.at;
    else if (onAt !== null) {
      total += e.at - onAt;
      onAt = null;
    }
  }
  if (onAt !== null && active) total += now - onAt;
  return total / 60000;
}

function volumeChanges(v: VolumeSample[]): number {
  let n = 0;
  for (let i = 1; i < v.length; i++) if (Math.abs(v[i].ringVolume - v[i - 1].ringVolume) >= 0.05) n++;
  return n;
}

function Stat({ value, label, color }: { value: string; label: string; color?: string }) {
  const { width, fontScale } = useWindowDimensions();
  const available = Math.min(width, layout.maxContent + layout.gutter * 2) - layout.gutter * 2;
  const minWidth = Math.min(available, Math.max(space[10] * 2, numeralWidth(value, fontScale)));
  const [measured, setMeasured] = useState(0);
  return (
    <View onLayout={(event) => setMeasured(event.nativeEvent.layout.width)} style={{ flex: 1, minWidth, gap: space[3] }}>
      <Text variant={numeralVariant(value, measured || minWidth, fontScale, 'stat')} color={color} tabular>
        {value}
      </Text>
      <Text variant="callout" tone="secondary">
        {label}
      </Text>
    </View>
  );
}

function Hairline() {
  const { c } = useTheme();
  return <View style={{ height: control.hairline, backgroundColor: c.border, marginVertical: space[6] }} />;
}

export default function Week() {
  const { c, feature } = useTheme();
  const attention = useAttune((s) => s.attention);
  const notifications = useAttune((s) => s.notifications);
  const focusLog = useAttune((s) => s.focusLog);
  const dndActive = useAttune((s) => s.dndActive);
  const volume = useAttune((s) => s.volume);
  const keyboard = useAttune((s) => s.keyboard);
  const [now] = useState(() => Date.now());

  const day = attention.length ? attention[0] : null;
  const hasAttentionData = !!day && day.source !== 'usage';
  const sky = feature('attention');
  const ember = feature('notify');
  const mint = feature('focus');
  const honey = feature('volume');
  const iris = feature('keyboard');

  const priority = notifications.filter((n) => n.verdict === 'priority').length;
  const held = notifications.filter((n) => n.verdict === 'digest').length;
  const dnd = useMemo(() => dndMinutes(focusLog, dndActive, now), [focusLog, dndActive, now]);
  const letThrough = focusLog.filter((e) => e.action === 'let_through').length;
  const adaptations = useMemo(() => volumeChanges(volume), [volume]);

  const hourlyMax = day ? Math.max(...day.hourly, 1) : 1;
  const peakHour = day ? day.hourly.indexOf(Math.max(...day.hourly)) : -1;
  const hourLabel = (h: number) => `${h % 12 === 0 ? 12 : h % 12}${h < 12 ? 'am' : 'pm'}`;

  return (
    <Screen title="A little perspective" eyebrow="Care" bottomInset={TAB_BAR_SPACE}>
      <Text variant="callout" tone="secondary">Attention uses the latest recorded day. Other totals cover the records saved on this phone.</Text>
      {/* Attention */}
      <Reveal index={0}>
        {day && day.screenOnMin > 0 ? (
          <View style={{ gap: space[6] }}>
            <View style={{ flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space[6] }}>
              <Ring
                size={space[10] * 2}
                stroke={space[3]}
                progress={(hasAttentionData ? day.attendedMin : day.screenOnMin) / day.screenOnMin}
                color={c.chart.primary}
                ghost={1}
                ghostColor={c.borderStrong}
              >
                <Text variant="statCompact" tabular allowFontScaling={false}>
                  {percent((hasAttentionData ? day.attendedMin : day.screenOnMin) / day.screenOnMin)}
                </Text>
                <Text variant="caption" tone="tertiary">
                  {hasAttentionData ? (day.source === 'camera' ? 'observed' : 'estimated') : 'foreground'}
                </Text>
              </Ring>
              <View style={{ flex: 1, minWidth: space[9] * 5, gap: space[3] }}>
                <Text variant="story">{hasAttentionData ? `Estimated attention: ${minutes(day.attendedMin)}.` : `Android foreground time was ${minutes(day.screenOnMin)}.`}</Text>
                <Text variant="callout" tone="secondary">
                  {hasAttentionData ? 'Only sampled portions of the day are classified. Remaining foreground time can include unobserved use.' : 'This is a usage baseline, not a gaze measurement.'}
                </Text>
              </View>
            </View>
            <Text variant="caption" tone="tertiary">
              {hasAttentionData ? (day.source === 'camera' ? 'On-device iris and head-direction observations; accuracy varies with lighting and position.' : 'Includes motion and interaction estimates. Sensors cannot measure gaze.') : 'Android foreground time only; no gaze measurement is claimed.'}
            </Text>
          </View>
        ) : (
          <EmptyState
            icon={<Eye size={control.iconLarge} color={sky.text} strokeWidth={control.stroke} />}
            title="No attention data yet"
            body="Enable True Screen Time to compare Android foreground time with camera observations or sensor estimates. Unobserved time stays unclassified."
          />
        )}
      </Reveal>

      {/* Hourly */}
      <Reveal index={1}>
        <SectionHeader title="Through the recorded day" />
        {day && day.hourly.some((h) => h > 0) ? (
          <View style={{ marginTop: space[5], gap: space[4] }}>
            <View style={{ flexDirection: 'row', alignItems: 'flex-end', height: 96, gap: 3 }}>
              {day.hourly.map((m, h) => (
                <View
                  key={h}
                  style={{
                    flex: 1,
                    height: Math.max(3, (m / hourlyMax) * 96),
                    borderRadius: radius.xs / 2,
                    backgroundColor: h === peakHour ? c.chart.primary : m > 0 ? c.chart.secondary : c.surfacePressed,
                  }}
                />
              ))}
            </View>
            <View style={{ flexDirection: 'row', justifyContent: 'space-between' }}>
              {[0, 6, 12, 18].map((h) => (
                <Text key={h} variant="caption" tone="tertiary">
                  {hourLabel(h)}
                </Text>
              ))}
              <Text variant="caption" tone="tertiary">
                12am
              </Text>
            </View>
            <Text variant="callout" tone="secondary">
              Busiest recorded hour: {hourLabel(peakHour)}, with {minutes(day.hourly[peakHour])} of {hasAttentionData ? 'observed or estimated attention' : 'Android foreground time'}.
            </Text>
          </View>
        ) : (
          <EmptyState
            icon={<BarChart3 size={control.iconLarge} color={c.textTertiary} strokeWidth={control.stroke} />}
            title="Nothing to chart yet"
            body="Hourly foreground time, observations or estimates appear here as they are recorded."
          />
        )}
      </Reveal>

      {/* Protected */}
      <Reveal index={2}>
        <SectionHeader title="Recorded notifications" />
        <View style={{ marginTop: space[5] }}>
          {notifications.length > 0 ? (
            <>
              <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space[5] }}>
                <Stat value={String(held)} label="held for later" color={ember.text} />
                <Stat value={String(priority)} label="ranked as priority" />
              </View>
              {held > 0 && (
                <View style={{ flexDirection: 'row', height: 6, borderRadius: 3, overflow: 'hidden', marginTop: space[4], backgroundColor: c.surfacePressed }}>
                  <View style={{ flex: held, backgroundColor: ember.soft }} />
                  <View style={{ flex: Math.max(priority, 0.0001), backgroundColor: ember.base }} />
                </View>
              )}
            </>
          ) : (
            <EmptyState
              title="No notifications ranked yet"
              body="When messages arrive, you will see how many were held for later and which ones broke through."
            />
          )}
        </View>
      </Reveal>

      <Reveal index={3}>
        <Hairline />
        {focusLog.length > 0 && dnd > 0 ? (
          <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space[5] }}>
             <Stat value={minutes(dnd)} label="logged in DND" color={mint.text} />
            <Stat value={String(letThrough)} label={letThrough === 1 ? 'call let through' : 'calls let through'} />
          </View>
        ) : (
          <EmptyState
            icon={<Moon size={control.iconLarge} color={mint.text} strokeWidth={control.stroke} />}
            title="No quiet time logged"
            body="Smart Focus records the DND sessions it owns. Logged durations will appear here."
          />
        )}
      </Reveal>

      <Reveal index={4}>
        <Hairline />
        <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space[5] }}>
          {volume.length > 1 ? (
            <Stat value={String(adaptations)} label="recorded ringer changes" color={honey.text} />
          ) : (
            <View style={{ flex: 1, minWidth: 0, gap: space[3] }}>
              <Text variant="title3">No volume changes yet</Text>
              <Text variant="callout" tone="secondary">
                Ambient Volume will log each time your ringer adapts to the room.
              </Text>
            </View>
          )}
        </View>
      </Reveal>

      <Reveal index={5}>
        <Hairline />
        {keyboard ? (
          <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space[5] }}>
            <Stat value={String(keyboard.correctionsAvoided)} label="corrections avoided" color={iris.text} />
            <Stat value={String(keyboard.keystrokesSaved)} label="keystrokes saved" />
          </View>
        ) : (
          <View style={{ gap: space[3] }}>
            <Text variant="title3">No keyboard totals yet</Text>
            <Text variant="callout" tone="secondary">
              Corrections avoided and keystrokes saved show up once you have typed with Attune Keyboard.
            </Text>
          </View>
        )}
      </Reveal>
    </Screen>
  );
}
