import { useMemo, useState, type ReactNode } from 'react';
import { Linking, View } from 'react-native';
import { Check, ChevronDown, Inbox, Info, X } from 'lucide-react-native';
import { control, layout, motion, radius, space, useTheme } from '@/theme';
import { Card, Chip, EmptyState, PressableScale, PrivacyCard, Reveal, Screen, SectionHeader, Text } from '@/ui';
import { FeatureHero, featureEyebrow } from '@/components/FeatureHero';
import { useAttune } from '@/state/store';
import { getAttuneNative } from '../../../modules/attune-native';
import { ago } from '@/lib/format';
import { appDisplayName } from '@/lib/appDisplayName';
import { haptic } from '@/lib/haptics';
import type { RankedNotification, SenderTier } from '@/state/types';

const TIER_LABEL: Record<SenderTier, string> = { family: 'Family', close: 'Close', class: 'Class', work: 'Work', unknown: 'Unknown' };

const SIGNALS = [
  { key: 'Message context and college updates', note: 'The latest message is read with a short recent thread window. Exam, viva, class, venue and deadline changes matter, including cancellations. Old messages and group names do not create urgency.' },
  { key: 'Sender and feedback', note: 'Your explicit sender tags and recent feedback from the same conversation adjust ranking. Recognized current danger retains priority.' },
  { key: 'Your current context', note: 'Calendar, meeting, navigation and focus signals protect your attention. A current deadline or safety message can still break through.' },
  { key: 'Uncertainty stays visible', note: 'Unclear meaning, missing content or unavailable model evidence keeps the original notification. A ranking score is not a measured accuracy percentage.' },
] as const;

function grant() {
  const fallback = () => Linking.openSettings().catch(() => {});
  try {
    Linking.sendIntent('android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS').catch(fallback);
  } catch {
    fallback();
  }
}

export default function NotifyScreen() {
  const { c, feature } = useTheme();
  const a = feature('notify');
  const items = useAttune((s) => s.notifications);
  const access = useAttune((s) => s.permissions.notificationAccess);
  const [noted, setNoted] = useState<Record<string, 'up' | 'down'>>({});
  const [open, setOpen] = useState<Record<string, boolean>>({});

  const priority = useMemo(() => items.filter((n) => n.verdict === 'priority').sort((x, y) => y.postedAt - x.postedAt), [items]);
  const originals = useMemo(() => items.filter((n) => n.verdict === 'original').sort((x, y) => y.postedAt - x.postedAt), [items]);
  const groups = useMemo(() => {
    const m = new Map<string, RankedNotification[]>();
    items.filter((n) => n.verdict === 'digest').forEach((n) => m.set(n.app, [...(m.get(n.app) ?? []), n]));
    return [...m.entries()].sort((x, y) => y[1].length - x[1].length);
  }, [items]);
  const senders = useMemo(() => {
    const m = new Map<string, SenderTier>();
    items.forEach((n) => m.set(n.sender, n.tier));
    return [...m.entries()];
  }, [items]);

  const feedback = async (n: RankedNotification, up: boolean) => {
    const native = getAttuneNative();
    if (!native) return;
    const saved = await native.submitNotificationFeedback(n.id, up ? 'MARKED_IMPORTANT' : 'MARKED_NOT_IMPORTANT').catch(() => false);
    if (!saved) return;
    if (up) haptic.success();
    else haptic.warn();
    setNoted((p) => ({ ...p, [n.id]: up ? 'up' : 'down' }));
  };

  return (
    <Screen back title="Priority Inbox" eyebrow={featureEyebrow('notify')} eyebrowColor={a.text} hero={<FeatureHero feature="notify" onGrant={grant} />}>
      <SectionHeader title="Priority now" />
      {priority.length === 0 ? (
        <Reveal>
          <Card>
            <EmptyState
              icon={<Inbox size={control.iconLarge} color={a.text} strokeWidth={control.stroke} />}
              title={access === 'granted' ? 'Nothing needs you right now' : 'Waiting for notification access'}
              body={
                access === 'granted'
                  ? 'Messages that matter will appear here the moment they arrive.'
                  : 'Allow notification access in setup to start ranking messages. Text stays on this phone.'
              }
            />
          </Card>
        </Reveal>
      ) : (
        priority.map((n, i) => (
          <Reveal key={n.id} index={i}>
            <Card>
              <View style={{ flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'space-between', alignItems: 'baseline', gap: space[2] }}>
                <Text variant="title3" style={{ flex: 1, minWidth: space[10] * 2 }}>
                  {n.sender}
                </Text>
                <Text variant="numeral" tone="tertiary">
                  {ago(n.postedAt)}
                </Text>
              </View>
              <Text variant="caption" tone="tertiary" style={{ marginTop: space[2] }}>
                {appDisplayName(n.app)}
                {n.conversation ? ` · ${n.conversation}` : ''}
              </Text>
              <Text variant="body" tone="secondary" style={{ marginTop: space[4] }}>
                {n.text}
              </Text>
              {n.reasons.length > 0 && (
                <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space[2], marginTop: space[4] }}>
                  {n.reasons.map((r) => (
                    <Chip key={r} label={r} color={a.text} soft={a.soft} />
                  ))}
                </View>
              )}
              {n.meaning && (
                <Text variant="caption" tone="tertiary" style={{ marginTop: space[3] }}>
                  {n.meaning.intent} · {n.meaning.source} · evidence {Math.round(n.meaning.confidence * 100)}% (not accuracy)
                </Text>
              )}
              <View
                accessible
                accessibilityLabel={`Priority score ${Math.round(n.score * 100)} percent`}
                style={{ height: space[1], borderRadius: radius.pill, backgroundColor: c.surfacePressed, marginTop: space[5], overflow: 'hidden' }}
              >
                <View style={{ width: `${Math.round(n.score * 100)}%`, height: space[1], backgroundColor: c.chart.primary }} />
              </View>
              <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space[2], marginTop: space[5], alignItems: 'center' }}>
                {noted[n.id] ? (
                  <Text variant="callout" tone="secondary">
                    Noted. Attune will rank {n.sender} {noted[n.id] === 'up' ? 'higher' : 'lower'}.
                  </Text>
                ) : (
                  <>
                    <FeedbackButton label="Important" icon={<Check size={control.iconSmall} color={a.text} strokeWidth={control.stroke} />} onPress={() => feedback(n, true)} fg={a.text} bg={a.soft} />
                    <FeedbackButton label="Not important" icon={<X size={control.iconSmall} color={c.textSecondary} strokeWidth={control.stroke} />} onPress={() => feedback(n, false)} fg={c.textSecondary} bg={c.surfaceRaised} />
                  </>
                )}
              </View>
            </Card>
          </Reveal>
        ))
      )}

      {originals.length > 0 && (
        <>
          <SectionHeader title="Left in place" />
          <Card>
            {originals.slice(0, 8).map((n) => (
              <View key={n.id} style={{ gap: space[2], paddingVertical: space[5], borderBottomWidth: control.hairline, borderBottomColor: c.border }}>
                <Text variant="calloutMedium">{n.sender}</Text>
                <Text variant="caption" tone="tertiary">{appDisplayName(n.app)}{n.conversation ? ` · ${n.conversation}` : ''}</Text>
                <Text variant="callout" tone="secondary">{n.text}</Text>
                {n.meaning && <Text variant="caption" tone="tertiary">{n.meaning.intent} · {n.meaning.source}</Text>}
              </View>
            ))}
            <Text variant="caption" tone="tertiary" style={{ marginTop: space[3] }}>
              Uncertain meaning and ordinary requests stay in the source app.
            </Text>
          </Card>
        </>
      )}

      <SectionHeader title="Held for later" />
      {groups.length === 0 ? (
        <Card>
          <EmptyState title="No digest yet" body="Confidently low-priority messages appear here after a quiet Attune digest notification is delivered." />
        </Card>
      ) : (
        <Card>
          {groups.map(([app, list], i) => {
            const isOpen = !!open[app];
            return (
              <View key={app} style={{ borderBottomWidth: i === groups.length - 1 ? 0 : control.hairline, borderBottomColor: c.border }}>
                <PressableScale
                  accessibilityLabel={`${appDisplayName(app)}, ${list.length} held. ${isOpen ? 'Collapse' : 'Expand'}`}
                  accessibilityState={{ expanded: isOpen }}
                  onPress={() => setOpen((p) => ({ ...p, [app]: !p[app] }))}
                  scaleTo={motion.rowPressScale}
                  style={{ flexDirection: 'row', alignItems: 'center', paddingVertical: space[4], gap: space[3] }}
                >
                  <Text variant="bodyMedium" style={{ flex: 1, minWidth: 0 }}>
                    {appDisplayName(app)}
                  </Text>
                  <Text variant="numeral" tone="secondary">
                    {list.length}
                  </Text>
                  <View style={{ transform: [{ rotate: isOpen ? '180deg' : '0deg' }] }}>
                    <ChevronDown size={control.iconSmall} color={c.textTertiary} strokeWidth={control.stroke} />
                  </View>
                </PressableScale>
                {isOpen && (
                  <View style={{ gap: space[5], paddingBottom: space[5] }}>
                    {list.map((n) => (
                      <View key={n.id} style={{ gap: space[2] }}>
                        <Text variant="calloutMedium">
                          {n.sender}
                          <Text variant="caption" tone="tertiary">
                            {'  '}
                            {ago(n.postedAt)}
                          </Text>
                        </Text>
                        <Text variant="callout" tone="secondary">
                          {n.text}
                        </Text>
                        <Text variant="calloutMedium" color={a.text} onPress={() => feedback(n, true)} accessibilityRole="button" style={{ paddingVertical: space[3], minHeight: control.touchTarget }}>
                          This one is important
                        </Text>
                      </View>
                    ))}
                  </View>
                )}
              </View>
            );
          })}
        </Card>
      )}

      <SectionHeader title="How ranking works" />
      <Reveal>
        <Card>
          <View style={{ gap: space[6] }}>
            {SIGNALS.map((w, i) => (
              <View key={w.key} style={{ flexDirection: 'row', gap: space[3] }}>
                <View style={{ width: space[2], height: space[2], borderRadius: radius.pill, marginTop: space[2], backgroundColor: a.text, opacity: 1 - i * 0.1 }} />
                <View style={{ flex: 1, minWidth: 0, gap: space[2] }}>
                  <Text variant="bodyMedium">
                    {w.key}
                  </Text>
                  <Text variant="callout" tone="secondary">
                    {w.note}
                  </Text>
                </View>
              </View>
            ))}
          </View>
        </Card>
      </Reveal>

      <SectionHeader title="Senders" />
      <Card>
        {senders.length === 0 ? (
          <EmptyState title="No sender tags yet" body="Sender tiers come from your explicit family, close, class or work tags; relationships are never guessed from a message." />
        ) : (
          <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space[2] }}>
            {senders.map(([name, tier]) => (
              <Chip key={name} label={`${name} · ${TIER_LABEL[tier]}`} size="md" />
            ))}
          </View>
        )}
      </Card>

      <View accessible style={{ flexDirection: 'row', gap: space[4], padding: layout.cardPadding, borderRadius: radius.lg, backgroundColor: a.soft }}>
        <Info size={control.icon} color={a.text} strokeWidth={control.stroke} style={{ marginTop: space[1] }} />
        <Text variant="callout" style={{ flex: 1 }}>
          Mute noisy groups in WhatsApp. Attune becomes their smart sound.
        </Text>
      </View>

      <PrivacyCard
        senses="Sender, group and message text from notifications, read on this phone."
        stays="Scores, feedback and held digest messages stay in app-private storage on this phone. Thread context is not saved."
        never="Message text is never uploaded or shared."
      />
    </Screen>
  );
}

function FeedbackButton({ label, icon, onPress, fg, bg }: { label: string; icon: ReactNode; onPress: () => void; fg: string; bg: string }) {
  return (
    <PressableScale
      onPress={onPress}
      haptics={false}
      accessibilityLabel={label}
      style={{ flexDirection: 'row', alignItems: 'center', gap: space[2], minHeight: control.touchTarget, maxWidth: '100%', paddingVertical: space[3], paddingHorizontal: space[4], borderRadius: radius.pill, backgroundColor: bg }}
    >
      {icon}
      <Text variant="calloutMedium" color={fg} style={{ flexShrink: 1 }}>
        {label}
      </Text>
    </PressableScale>
  );
}
