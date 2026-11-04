import { useEffect, useState, type ReactNode } from 'react';
import { Linking, View } from 'react-native';
import { BellOff, BellRing, Calendar, MapPin, Moon, Pause, Smartphone, Undo2, Footprints, type LucideIcon } from 'lucide-react-native';
import { control, radius, space, useTheme } from '@/theme';
import { Button, Card, EmptyState, PressableScale, PrivacyCard, Reveal, Ring, Screen, SectionHeader, Switch, Text } from '@/ui';
import { FeatureHero, featureEyebrow } from '@/components/FeatureHero';
import { FocusLearning } from '@/components/FocusLearning';
import { useAttune } from '@/state/store';
import { refreshFocusState } from '@/state/focusActions';
import { focusReason, freshFocusProbability } from '@/state/focusContract';
import { getAttuneNative } from '../../../modules/attune-native';
import { clock } from '@/lib/format';
import { haptic } from '@/lib/haptics';
import type { FocusEvent, PermissionKey, PermissionStatus } from '@/state/types';

const EVENT: Record<FocusEvent['action'], { icon: LucideIcon; label: string }> = {
  dnd_on: { icon: Moon, label: 'Do Not Disturb on' },
  dnd_off: { icon: BellRing, label: 'Do Not Disturb off' },
  let_through: { icon: BellRing, label: 'Let through' },
  held: { icon: Pause, label: 'Held' },
};

const PERM_LABEL: Record<PermissionStatus, string> = { granted: 'Allowed', denied: 'Not allowed', unknown: 'Not asked' };

function grant() {
  const fallback = () => Linking.openSettings().catch(() => {});
  try {
    Linking.sendIntent('android.settings.NOTIFICATION_POLICY_ACCESS_SETTINGS').catch(fallback);
  } catch {
    fallback();
  }
}

export default function FocusScreen() {
  const { c, feature } = useTheme();
  const a = feature('focus');
  const dnd = useAttune((s) => s.dndActive);
  const ctx = useAttune((s) => s.context);
  const log = useAttune((s) => s.focusLog);
  const perms = useAttune((s) => s.permissions);
  const focus = useAttune((s) => s.focus);
  const nativeAvailable = !!getAttuneNative();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [now, setNow] = useState(Date.now);
  const rules = { starred: focus?.allowPrioritySenders ?? true, repeat: focus?.allowRepeatCallers ?? true };

  useEffect(() => {
    void refreshFocusState().catch(() => {});
    const timer = setInterval(() => setNow(Date.now()), 30_000);
    return () => clearInterval(timer);
  }, []);

  const probability = freshFocusProbability(focus, now);
  const freshContext = ctx && now - ctx.at >= 0 && now - ctx.at <= 120_000 ? ctx : null;
  const score = probability !== null ? Math.round(100 * (1 - probability)) : freshContext ? Math.max(0, Math.min(100, freshContext.interruptibility)) : null;
  const sorted = [...log].sort((x, y) => y.at - x.at);
  const undoable = sorted.find((e) => e.action === 'dnd_on' || e.action === 'dnd_off');

  const manual = async (turnOn: boolean) => {
    if (busy) return;
    const native = getAttuneNative();
    if (!native) return;
    setBusy(true); setError(null);
    try {
      const result = await native.applyDnd(turnOn, rules.starred, rules.repeat);
      if (result.applied !== true) {
        setError(result.reason === 'not_active_no_snapshot'
          ? 'This DND state belongs to Android or another app. Change it in Android settings.'
          : typeof result.reason === 'string' ? focusReason(result.reason) : 'Android could not change DND. Check policy access and try again.');
      } else {
        haptic.toggle();
        if (result.feedbackSaved === false) setError('DND changed, but this choice could not be saved for learning.');
      }
      await refreshFocusState();
    } catch { setError('Could not change DND. Your Android state has not been replaced by a local toggle.'); }
    finally { setBusy(false); }
  };
  const undo = () => { if (undoable) void manual(undoable.action === 'dnd_off'); };

  const changeRule = async (key: keyof typeof rules, value: boolean) => {
    if (busy) return;
    const next = { ...rules, [key]: value };
    const native = getAttuneNative();
    if (!native) return;
    setBusy(true); setError(null);
    try {
      if (!await native.configureFocus(focus?.enabled ?? false, next.starred, next.repeat))
        setError('The exception rule could not be saved. Check DND access and retry.');
      await refreshFocusState();
    } catch { setError('Could not confirm this rule. Check the current Android DND policy before retrying.'); }
    finally { setBusy(false); }
  };

  const signals: { icon: LucideIcon; label: string; value: string | null; perm: PermissionKey }[] = [
    { icon: Footprints, label: 'Activity', value: freshContext && freshContext.activity !== 'unknown' ? freshContext.activity : null, perm: 'activityRecognition' },
    { icon: Calendar, label: 'Calendar', value: freshContext?.event ?? null, perm: 'calendar' },
    { icon: MapPin, label: 'Place', value: freshContext && freshContext.place !== 'unknown' ? freshContext.place : null, perm: 'location' },
    { icon: Smartphone, label: 'App in use', value: freshContext?.appCategory ?? null, perm: 'usageAccess' },
  ];

  return (
    <Screen back title="Smart Focus" eyebrow={featureEyebrow('focus')} eyebrowColor={a.text} hero={<FeatureHero feature="focus" onGrant={grant} personaNote="and everyone with a lecture, a deadline and a phone that will not stop" />}>
      <FocusLearning>
      <Reveal>
        <Card>
          <View style={{ flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space[5] }}>
            <Ring size={space[10] + space[9]} stroke={control.chart.ringStroke} progress={score === null ? 0 : score / 100} color={c.chart.primary}>
              <Text variant={score === null ? 'calloutMedium' : 'stat'} tabular accessibilityLabel={score === null ? 'Interruptibility unknown' : `Interruptibility ${score} of 100`}>
                {score === null ? 'Unknown' : Math.round(score)}
              </Text>
            </Ring>
            <View style={{ flex: 1, minWidth: space[10] * 2, gap: space[2] }}>
              <Text variant="overline" tone="tertiary" uppercase>
                Do Not Disturb
              </Text>
              <Text variant={nativeAvailable ? 'heroCompact' : 'title3'} color={dnd ? a.text : undefined}>
                {nativeAvailable ? (dnd ? 'On' : 'Off') : 'Unavailable'}
              </Text>
              <Text variant="caption" tone="secondary">
                {!nativeAvailable ? 'Android DND status is unavailable here' : score === null ? 'Waiting for fresh context' : probability !== null ? 'Predicted interruptibility, 0 to 100' : 'Context estimate, 0 to 100'}
              </Text>
            </View>
          </View>
          {freshContext && freshContext.reasons.length > 0 ? (
            <View style={{ marginTop: space[6], gap: space[3] }}>
              {freshContext.reasons.map((r) => (
                <View key={r} style={{ flexDirection: 'row', gap: space[2], alignItems: 'center' }}>
                  <View style={{ width: space[1], height: space[1], borderRadius: radius.pill, backgroundColor: a.text }} />
                  <Text variant="callout" tone="secondary" style={{ flex: 1 }}>
                    {r}
                  </Text>
                </View>
              ))}
            </View>
          ) : (
            <Text variant="callout" tone="tertiary" style={{ marginTop: space[5] }}>
              Reasons will appear here as Attune senses your moment.
            </Text>
          )}
          <View style={{ marginTop: space[6], gap: space[4] }}>
            <Button label={focus?.ownsDnd ? 'End Attune DND' : 'Start DND now'} variant="secondary" loading={busy}
              disabled={!getAttuneNative() || busy || (dnd && !focus?.ownsDnd)} onPress={() => void manual(!focus?.ownsDnd)} />
             {dnd && !focus?.ownsDnd ? <Text variant="callout" tone="secondary">DND belongs to Android or another app. Attune will leave it alone.</Text> : null}
             {focus?.ownsDnd && focus.dndPhase !== 'active' ? <Text variant="callout" tone="secondary" accessibilityLiveRegion="polite">
               Android policy recovery is pending. End Attune DND to stop it, or check Android settings.
             </Text> : null}
             {focus?.policyUpdatePending ? <Text variant="callout" tone="danger" accessibilityLiveRegion="polite">
               {focus.policyUpdateReason ?? 'Your exception change has not been confirmed. Check the current Android DND policy.'}
             </Text> : null}
            <Text variant="callout" tone="secondary">Manual choices teach Focus. Timetable predictions always wait for the warning.</Text>
            {error ? <Text variant="callout" tone="danger" accessibilityLiveRegion="polite">{error}</Text> : null}
          </View>
        </Card>
      </Reveal>
      </FocusLearning>

      <SectionHeader title="Let through" />
      <Reveal index={1}>
        <Card>
          <RuleRow title="Starred contacts" sub="People starred in Android contacts" value={rules.starred} onChange={(v) => void changeRule('starred', v)} color={a.base} disabled={busy || !getAttuneNative()} />
          <RuleRow title="Repeat callers" sub="Android repeat-caller exception" value={rules.repeat} onChange={(v) => void changeRule('repeat', v)} color={a.base} disabled={busy || !getAttuneNative()} last />
          <Text variant="callout" tone="secondary" style={{ marginTop: space[3] }}>Priority Inbox alerts also depend on Android’s channel and DND settings; Focus cannot promise every alert will bypass them.</Text>
        </Card>
      </Reveal>

      <SectionHeader title="Context signals" />
      <Reveal index={2}>
        <Card>
          {signals.map((s, i) => {
            const Icon = s.icon;
            const status = perms[s.perm];
            return (
              <View
                key={s.label}
                accessible
                accessibilityLabel={`${s.label}: ${s.value ?? 'no reading'}. ${PERM_LABEL[status]}`}
                style={{ flexDirection: 'row', alignItems: 'center', gap: space[4], paddingVertical: space[5], borderBottomWidth: i === signals.length - 1 ? 0 : 1, borderBottomColor: c.border }}
              >
                <Icon size={control.icon} color={c.textSecondary} strokeWidth={control.stroke} />
                <View style={{ flex: 1, minWidth: 0, gap: space[2] }}>
                  <Text variant="bodyMedium">{s.label}</Text>
                  <Text variant="caption" color={status === 'granted' ? c.success : c.textTertiary}>
                    {PERM_LABEL[status]}
                  </Text>
                </View>
                <Text variant="calloutMedium" tone={s.value ? 'primary' : 'tertiary'} style={{ flex: 1, minWidth: 0, textAlign: 'right', textTransform: 'capitalize' }}>
                  {s.value ?? 'No reading'}
                </Text>
              </View>
            );
          })}
        </Card>
      </Reveal>

      <SectionHeader title="What Attune did" action={undoable ? 'Undo last change' : undefined} onAction={undo} />
      <Reveal index={3}>
        <Card>
          {sorted.length === 0 ? (
            <EmptyState icon={<BellOff size={control.iconLarge} color={a.text} strokeWidth={control.stroke} />} title="No changes yet" body="DND changes are logged with their reason. Attune can reverse only the sessions it owns." />
          ) : (
            <View>
              <View style={{ position: 'absolute', left: space[4], top: space[2], bottom: space[2], width: 1, backgroundColor: c.border }} />
              {sorted.slice(0, 12).map((e, i) => {
                const meta = EVENT[e.action];
                const Icon = meta.icon;
                return (
                  <View key={e.id} accessible style={{ flexDirection: 'row', gap: space[4], paddingBottom: i === Math.min(sorted.length, 12) - 1 ? 0 : space[6] }}>
                    <View style={{ width: space[7], height: space[7], borderRadius: radius.pill, backgroundColor: e.action === 'dnd_on' ? a.soft : c.surfaceRaised, borderWidth: 1, borderColor: c.surface, alignItems: 'center', justifyContent: 'center' }}>
                      <Icon size={control.iconSmall} color={e.action === 'dnd_on' ? a.text : c.textSecondary} strokeWidth={control.stroke} />
                    </View>
                    <View style={{ flex: 1, minWidth: 0, gap: space[2] }}>
                      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space[2], justifyContent: 'space-between' }}>
                        <Text variant="calloutMedium" style={{ flexShrink: 1 }}>{meta.label}</Text>
                        <Text variant="numeral" tone="tertiary">
                          {clock(e.at)}
                        </Text>
                      </View>
                      <Text variant="callout" tone="secondary">
                        {focusReason(e.reason)}
                      </Text>
                    </View>
                  </View>
                );
              })}
            </View>
          )}
          {undoable && (
            <PressableScale onPress={undo} haptics={false} accessibilityLabel="Undo last change" style={{ flexDirection: 'row', alignItems: 'center', gap: space[2], marginTop: space[6], alignSelf: 'flex-start', minHeight: control.touchTarget, paddingVertical: space[3], paddingHorizontal: space[4], borderRadius: radius.pill, backgroundColor: c.surfaceRaised }}>
              <Undo2 size={control.iconSmall} color={c.text} strokeWidth={control.stroke} />
              <Text variant="calloutMedium">Undo last change</Text>
            </PressableScale>
          )}
        </Card>
      </Reveal>

      <PrivacyCard
        senses="Reviewed class times, current calendar availability, recent app context and your explicit DND choices."
        stays="Timetable and personal training data stay in encrypted storage on this phone. Use Delete Focus data above to remove them."
        never="Your timetable, images, calendar and app context are not uploaded. Temporary import copies are discarded. Google’s OCR SDK may send usage/performance metrics, never the timetable content."
      />
    </Screen>
  );
}

function RuleRow({ title, sub, value, onChange, color, last, disabled }: { title: string; sub: string; value: boolean; onChange: (v: boolean) => void; color: string; last?: boolean; disabled?: boolean }): ReactNode {
  const { c } = useTheme();
  return (
    <View style={{ flexDirection: 'row', alignItems: 'center', gap: space[4], paddingVertical: space[5], borderBottomWidth: last ? 0 : 1, borderBottomColor: c.border }}>
      <View style={{ flex: 1, minWidth: 0, gap: space[2] }}>
        <Text variant="bodyMedium">{title}</Text>
        <Text variant="callout" tone="secondary">
          {sub}
        </Text>
      </View>
      <Switch value={value} onChange={onChange} color={color} accessibilityLabel={title} disabled={disabled} />
    </View>
  );
}
