import { useEffect, useMemo, useState } from 'react';
import { View, useWindowDimensions } from 'react-native';
import { Canvas, Circle, Path, Skia } from '@shopify/react-native-skia';
import { Headphones, Minus, Mic, MicOff, Plus, Volume2 } from 'lucide-react-native';
import { control, layout, radius, space, useTheme } from '@/theme';
import { Card, EmptyState, PressableScale, PrivacyCard, Reveal, Row, Screen, SectionHeader, Sparkline, Switch, Text } from '@/ui';
import { FeatureHero, featureEyebrow } from '@/components/FeatureHero';
import { useAttune } from '@/state/store';
import { openPermissionSettings, permissionList } from '@/state/permissionInfo';
import { haptic } from '@/lib/haptics';
import { percent } from '@/lib/format';
import type { NoiseEnvironment } from '@/state/types';
import { getAttuneNative } from '../../../modules/attune-native';

const ENV_LABEL: Record<NoiseEnvironment, string> = {
  quiet: 'Quiet room',
  conversation: 'Conversation',
  street: 'Street',
  transit: 'Transit',
  music: 'Music',
  unknown: 'Environment not classified',
};

const DB_LO = 30;
const DB_HI = 90;
const mic = permissionList.find((p) => p.key === 'microphone')!;

function ambientStatus(sample: { reason?: string; confidence?: number | null; source?: string }) {
  const reason = sample.reason ?? '';
  const voiceMasked = reason.includes('speech_masked') || reason.includes('protected_context_speech');
  const confidence = typeof sample.confidence === 'number' ? ` · ${Math.round(sample.confidence * 100)}% evidence confidence` : '';
  if (voiceMasked) return `Speech detected · using the non-speech background floor${confidence}`;
  if (reason.includes('low_background_floor')) return `Background noise only${confidence}`;
  if (sample.source === 'microphone_acoustic_features' || sample.source === 'microphone') return `On-device acoustic features${confidence}`;
  return `Source unavailable${confidence}`;
}

export default function VolumeScreen() {
  const { c, feature } = useTheme();
  const accent = feature('volume');
  const { width } = useWindowDimensions();
  const samples = useAttune((s) => s.volume);
  const micStatus = useAttune((s) => s.permissions.microphone);
  const [minVol, setMinVol] = useState(0.15);
  const [maxVol, setMaxVol] = useState(0.9);
  const [leaveHeadphones, setLeaveHeadphones] = useState(true);

  useEffect(() => {
    const native = getAttuneNative();
    if (!native) return;
    void native.getAmbientConfig().then((config) => {
      if (typeof config.minVolumeFraction === 'number') setMinVol(config.minVolumeFraction);
      if (typeof config.maxVolumeFraction === 'number') setMaxVol(config.maxVolumeFraction);
      if (typeof config.leaveHeadphonesAlone === 'boolean') setLeaveHeadphones(config.leaveHeadphonesAlone);
    }).catch(() => {});
  }, []);

  const saveConfig = async (nextMin: number, nextMax: number, nextHeadphones: boolean) => {
    const native = getAttuneNative();
    if (!native) return true;
    const saved = await native.setAmbientConfig(nextMin, nextMax, nextHeadphones).catch(() => false);
    if (!saved) {
      setMinVol(minVol);
      setMaxVol(maxVol);
      setLeaveHeadphones(leaveHeadphones);
    }
    return saved;
  };

  const last = samples.length ? samples[samples.length - 1] : null;
  const inner = Math.min(width, layout.maxContent + layout.gutter * 2) - layout.gutter * 2 - layout.cardPadding * 2 - control.hairline * 2;

  return (
    <Screen
      title="Ambient Volume"
      eyebrow={featureEyebrow('volume')}
      eyebrowColor={accent.text}
      back
      bottomInset={space[4]}
      hero={
        <FeatureHero feature="volume" onGrant={() => openPermissionSettings(mic)} personaNote="whose phone rang loudest in the quietest room">
          <View style={{ marginTop: space[4], gap: space[2] }} accessible accessibilityLabel={last ? `${last.db} decibels, ${ENV_LABEL[last.environment]}` : 'No noise reading yet'}>
            <View style={{ flexDirection: 'row', alignItems: 'baseline', gap: space[2] }}>
              <Text
                variant="statLarge"
                tabular
                color={last ? accent.text : c.textTertiary}
              >
                {last ? Math.round(last.db) : '--'}
              </Text>
              <Text variant="bodyMedium" tone="tertiary">
                dB
              </Text>
            </View>
            <Text variant="bodyMedium" tone="secondary">
              {last ? `${ENV_LABEL[last.environment]} · ringer ${percent(last.ringVolume)}` : 'No noise reading yet'}
            </Text>
            <Text variant="caption" tone="tertiary" style={{ marginTop: space[1] }}>
              {last ? ambientStatus(last) : 'One-second samples. Raw audio is never saved.'}
            </Text>
          </View>
        </FeatureHero>
      }
    >
      {micStatus === 'denied' && (
        <Reveal index={1}>
          <Card tint={accent.soft}>
            <View style={{ flexDirection: 'row', gap: space[3], alignItems: 'flex-start' }}>
              <MicOff size={control.icon} color={accent.text} strokeWidth={control.stroke} />
              <View style={{ flex: 1, minWidth: 0, gap: space[2] }}>
                <Text variant="bodyMedium">Microphone access is off</Text>
                <Text variant="callout" tone="secondary">
                  Room noise cannot be measured without microphone access. Any context-only decision is an estimate.
                </Text>
              </View>
            </View>
          </Card>
        </Reveal>
      )}

      <SectionHeader title="Noise and ringer" />
      <Reveal index={2}>
        <Card>
          {samples.length > 1 ? (
            <View accessible accessibilityLabel="Noise level over time with the ringer volume line">
              <View style={{ width: inner, height: 120 }}>
                <Sparkline data={samples.map((s) => s.db)} width={inner} height={120} color={c.chart.primary} min={DB_LO} max={DB_HI} />
                <View style={{ position: 'absolute' }}>
                  <Sparkline data={samples.map((s) => s.ringVolume)} width={inner} height={120} color={c.chart.secondary} min={0} max={1} fill={false} strokeWidth={control.chart.secondaryStroke} />
                </View>
              </View>
              <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space[4], marginTop: space[3] }}>
                <Key color={c.chart.primary} label="Room noise" />
                <Key color={c.chart.secondary} label="Ringer level" />
              </View>
            </View>
          ) : (
            <EmptyState
              icon={<Mic size={control.iconLarge} color={accent.text} strokeWidth={control.stroke} />}
              title="No readings yet"
              body="Every half minute or so Attune listens for one second and notes how loud the room is. The trace builds up here."
            />
          )}
        </Card>
      </Reveal>

      <SectionHeader title="Noise to volume" />
      <Reveal index={3}>
        <Card>
          <CurveView width={inner} min={minVol} max={maxVol} now={last?.db ?? null} color={c.chart.primary} />
          <Text variant="callout" tone="secondary" style={{ marginTop: space[3] }}>
            Attune masks detected speech and uses the remaining background floor, so one person talking
            does not make your ringer louder. Calendar events and Focus protect the quiet policy.
          </Text>
        </Card>
      </Reveal>

      <Reveal index={4}>
        <Card>
          <Stepper label="Minimum ringer" value={minVol} lo={0.05} hi={maxVol - 0.05} onChange={(next) => { setMinVol(next); void saveConfig(next, maxVol, leaveHeadphones); }} color={accent.text} />
          <View style={{ height: control.hairline, backgroundColor: c.border, marginVertical: space[6] }} />
          <Stepper label="Maximum ringer" value={maxVol} lo={minVol + 0.05} hi={1} onChange={(next) => { setMaxVol(next); void saveConfig(minVol, next, leaveHeadphones); }} color={accent.text} />
        </Card>
      </Reveal>

      <Reveal index={5}>
        <Card>
          <Row
            last
            icon={<Headphones size={control.iconSmall} color={c.text} strokeWidth={control.stroke} />}
            title="Headphones: leave volume alone"
            subtitle="Attune never changes volume while headphones are connected."
            trailing={<Switch value={leaveHeadphones} onChange={(next) => { setLeaveHeadphones(next); void saveConfig(minVol, maxVol, next); }} color={accent.base} accessibilityLabel="Leave volume alone with headphones" />}
          />
        </Card>
      </Reveal>

      <Reveal index={6}>
        <PrivacyCard
          senses="Room loudness, one second at a time, and a label like quiet or street."
          stays="Audio lives in memory for one second. Only the background dB, label, confidence and decision reason stay on this phone."
          never="Recordings, transcripts, voiceprints, speaker identity or anything anyone said."
        />
      </Reveal>
    </Screen>
  );
}

function Key({ color, label }: { color: string; label: string }) {
  return (
    <View style={{ flexDirection: 'row', alignItems: 'center', gap: space[2] }}>
      <View style={{ width: space[4], height: space[1], borderRadius: radius.pill, backgroundColor: color }} />
      <Text variant="caption" tone="secondary">
        {label}
      </Text>
    </View>
  );
}

function Stepper({ label, value, lo, hi, onChange, color }: { label: string; value: number; lo: number; hi: number; onChange: (v: number) => void; color: string }) {
  const { c } = useTheme();
  const step = (d: number) => {
    const next = Math.round(Math.min(hi, Math.max(lo, value + d)) * 100) / 100;
    if (next !== value) {
      haptic.tap();
      onChange(next);
    }
  };
  const btn = { width: control.touchTarget, height: control.touchTarget, borderRadius: radius.pill, backgroundColor: c.surfaceRaised, borderWidth: control.hairline, borderColor: c.borderStrong, alignItems: 'center', justifyContent: 'center' } as const;
  return (
    <View style={{ gap: space[4] }}>
      <Text variant="bodyMedium">{label}</Text>
      <View style={{ flexDirection: 'row', alignSelf: 'flex-end', alignItems: 'center', gap: space[4] }}>
        <PressableScale accessibilityLabel={`Decrease ${label}`} haptics={false} onPress={() => step(-0.05)} style={btn}>
          <Minus size={control.iconSmall} color={c.text} strokeWidth={control.stroke} />
        </PressableScale>
        <Text variant="statCompact" color={color} tabular style={{ minWidth: space[10], textAlign: 'center' }} accessibilityLiveRegion="polite">
          {percent(value)}
        </Text>
        <PressableScale accessibilityLabel={`Increase ${label}`} haptics={false} onPress={() => step(0.05)} style={btn}>
          <Plus size={control.iconSmall} color={c.text} strokeWidth={control.stroke} />
        </PressableScale>
      </View>
    </View>
  );
}

const smooth = (t: number) => {
  const x = Math.min(1, Math.max(0, t));
  return x * x * (3 - 2 * x);
};

function CurveView({ width, min, max, now, color }: { width: number; min: number; max: number; now: number | null; color: string }) {
  const { c } = useTheme();
  const H = control.chart.curve.height;
  const pad = control.chart.curve.padding;
  const X = (db: number) => pad + ((db - DB_LO) / (DB_HI - DB_LO)) * (width - pad * 2);
  const Y = (v: number) => H - pad - v * (H - pad * 2);
  const vol = (db: number) => min + (max - min) * smooth((db - 40) / 30);

  const path = useMemo(() => {
    const p = Skia.PathBuilder.Make();
    for (let db = DB_LO; db <= DB_HI; db += 2) {
      if (db === DB_LO) p.moveTo(X(db), Y(vol(db)));
      else p.lineTo(X(db), Y(vol(db)));
    }
    return p.build();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [width, min, max]);

  const floor = useMemo(() => Skia.PathBuilder.Make().moveTo(pad, H - pad).lineTo(width - pad, H - pad).build(), [width, H, pad]);

  return (
    <View accessible accessibilityLabel={`Ringer ranges from ${percent(min)} in quiet rooms to ${percent(max)} in loud places`}>
      <Canvas style={{ width, height: H }}>
        <Path path={floor} style="stroke" strokeWidth={control.hairline} color={c.border} />
        <Path path={path} style="stroke" strokeWidth={control.chart.curve.stroke} color={color} strokeCap="round" strokeJoin="round" />
        <Circle cx={X(DB_LO)} cy={Y(min)} r={control.chart.curve.haloRadius} color={c.bg} />
        <Circle cx={X(DB_LO)} cy={Y(min)} r={control.chart.curve.markerRadius} color={color} />
        <Circle cx={X(DB_HI)} cy={Y(max)} r={control.chart.curve.haloRadius} color={c.bg} />
        <Circle cx={X(DB_HI)} cy={Y(max)} r={control.chart.curve.markerRadius} color={color} />
        {now !== null && <Circle cx={X(Math.min(DB_HI, Math.max(DB_LO, now)))} cy={Y(vol(now))} r={control.chart.curve.currentRadius} color={c.text} />}
      </Canvas>
      <View style={{ flexDirection: 'row', justifyContent: 'space-between' }}>
        <Text variant="caption" tone="tertiary">
          {DB_LO} dB · quiet
        </Text>
        <Volume2 size={control.iconSmall} color={c.textTertiary} strokeWidth={control.stroke} />
        <Text variant="caption" tone="tertiary">
          {DB_HI} dB · loud
        </Text>
      </View>
    </View>
  );
}
