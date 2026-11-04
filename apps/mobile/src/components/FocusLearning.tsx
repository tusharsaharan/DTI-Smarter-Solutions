import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Alert, TextInput, View, useWindowDimensions } from 'react-native';
import { Calendar, FilePlus2 } from 'lucide-react-native';
import { control, radius, space, type, useTheme } from '@/theme';
import { Button, Card, PressableScale, SectionHeader, Switch, Text } from '@/ui';
import { getAttuneNative } from '../../modules/attune-native';
import { useAttune } from '@/state/store';
import { refreshFocusState } from '@/state/focusActions';
import { haptic } from '@/lib/haptics';
import { structuredInputMinWidth } from '@/ui/numeralVariant';
import { focusCountdownSeconds, focusReason, focusSource, localIsoDate, mapTimetableEntries, minuteTime, parseMinuteTime,
  strings, timetableValidation, type TimetableEntry } from '@/state/focusContract';

const DAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
const PAGE_SIZE = 4;
type Draft = Omit<TimetableEntry, 'startMinute' | 'endMinute'> & { start: string; end: string };
const draft = (entry: TimetableEntry): Draft => ({ ...entry, start: minuteTime(entry.startMinute), end: minuteTime(entry.endMinute) });
const wire = (entry: Draft): TimetableEntry => ({ id: entry.id, title: entry.title.trim(),
  dayOfWeek: entry.dayOfWeek, startMinute: parseMinuteTime(entry.start), endMinute: parseMinuteTime(entry.end),
  startDate: entry.startDate, endDate: entry.endDate });

/** Timetable review and live warning are inline, keeping cancellation within the Focus screen. */
export function FocusLearning({ children }: { children?: ReactNode }) {
  const { c, feature } = useTheme();
  const accent = feature('focus');
  const focus = useAttune((state) => state.focus);
  const native = getAttuneNative();
  const [now, setNow] = useState(Date.now);
  const [busy, setBusy] = useState<string | null>(null);
  const busyRef = useRef(false);
  const skipRef = useRef(false);
  const [skipping, setSkipping] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  const [showLimits, setShowLimits] = useState(false);
  const [review, setReview] = useState<Draft[] | null>(null);
  const [warnings, setWarnings] = useState<string[]>([]);
  const [sourceName, setSourceName] = useState('Timetable');
  const [from, setFrom] = useState(localIsoDate);
  const [until, setUntil] = useState('');
  const [page, setPage] = useState(0);
  const pending = focus?.pending ?? null;
  const seconds = focusCountdownSeconds(pending, now);

  useEffect(() => {
    if (!pending) return;
    const update = () => setNow(Date.now());
    update();
    const timer = setInterval(update, 1000);
    const refresh = setTimeout(() => { void refreshFocusState().catch(() => {}); }, Math.max(0, pending.deadlineAtMs - Date.now()) + 1000);
    return () => { clearInterval(timer); clearTimeout(refresh); };
  }, [pending]);

  const run = async (name: string, task: () => Promise<void>) => {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(name);
    setMessage(null);
    setFailed(false);
    try {
      await task();
      await refreshFocusState();
    } catch (error) {
      setFailed(true);
      setMessage(error instanceof Error ? error.message : 'This change could not be saved. Try again.');
    } finally {
      busyRef.current = false;
      setBusy(null);
    }
  };

  const upload = () => void run('import', async () => {
    if (!native) throw new Error('Install the rebuilt Android app to import a timetable.');
    const result = await native.pickFocusTimetable();
    if (result.cancelled === true) return;
    const entries = mapTimetableEntries(result.entries);
    const discarded = Array.isArray(result.entries) ? result.entries.length - entries.length : 0;
    setReview(entries.map(draft));
    setWarnings([...strings(result.warnings), ...(discarded > 0 ? [`${discarded} invalid entries were left out. Check the original file.`] : [])]);
    setSourceName(typeof result.sourceName === 'string' ? result.sourceName : 'Imported timetable');
    const firstDate = entries.map((entry) => entry.startDate ?? '').filter(Boolean).sort().at(0);
    setFrom(firstDate ?? localIsoDate());
    const lastDate = entries.map((entry) => entry.endDate ?? '').sort().at(-1);
    setUntil(lastDate && lastDate >= localIsoDate() ? lastDate : '');
    setPage(0);
  });

  const save = () => void run('save', async () => {
    if (!native || !review) return;
    const entries = review.map(wire);
    const invalid = timetableValidation(entries, from, until);
    if (invalid) throw new Error(invalid);
    const result = await native.saveFocusTimetable(entries.map((entry) => ({ ...entry })), from, until);
    if (result.saved !== true)
      throw new Error(typeof result.error === 'string' ? focusReason(result.error) : 'The timetable could not be saved. No new schedule was activated.');
    setReview(null);
    haptic.toggle();
    setMessage('Timetable saved. Learning stays on this phone; DND still waits for a fresh countdown.');
  });

  const removeLearning = () => Alert.alert('Delete Focus data?',
    'This removes your timetable and personal training choices, cancels the countdown and stops automatic Focus. Manual DND stays unchanged.', [
       { text: 'Keep data', style: 'cancel' },
      { text: 'Delete', style: 'destructive', onPress: () => void run('delete', async () => {
        if (native && !await native.clearFocusLearning()) throw new Error('Could not clear Focus. Check DND access and try again.');
        setReview(null);
        haptic.toggle();
        setMessage('Timetable and personal Focus learning deleted.');
      }) },
    ]);

  // Cancellation must stay available while a long import or another settings operation runs.
  const skip = async () => {
    if (!native || !pending || skipRef.current) return;
    skipRef.current = true;
    setSkipping(true);
    try {
      const saved = await native.skipFocusPrediction(pending.id);
      await refreshFocusState();
      setFailed(!saved);
      setMessage(saved ? 'Skipped this time. Your choice was saved on this phone.' :
        'The countdown changed or the choice could not be saved. Check the current Focus status.');
      if (saved) haptic.toggle();
    } catch {
      setFailed(true);
      setMessage('Could not confirm Skip. Use Skip in the Android notification or turn automatic Focus off.');
    } finally { skipRef.current = false; setSkipping(false); }
  };

  const change = (index: number, update: Partial<Draft>) => setReview((rows) => rows?.map((row, i) => i === index ? { ...row, ...update } : row) ?? null);
  const count = review?.length ?? 0;
  const pageCount = Math.max(1, Math.ceil(count / PAGE_SIZE));
  const currentPage = Math.min(page, pageCount - 1);
  const invalid = review ? timetableValidation(review.map(wire), from, until) : null;

  return <>
    <Card>
      <View style={{ flexDirection: 'row', gap: space[4], alignItems: 'center' }}>
        <View style={{ flex: 1, minWidth: 0, gap: space[2] }}>
          <Text variant="bodyMedium">Predict busy moments</Text>
          <Text variant="callout" tone="secondary">A 60-second warning before DND. Skip any time.</Text>
        </View>
        <Switch value={focus?.enabled ?? false} color={accent.base} disabled={!native || !!busy}
          accessibilityLabel="Predict busy moments with a 60-second warning"
          onChange={(enabled) => void run('configure', async () => {
             if (native && !await native.configureFocus(enabled, focus?.allowPrioritySenders ?? true, focus?.allowRepeatCallers ?? true))
               throw new Error('Focus could not change. Check Do Not Disturb access and try again.');
             haptic.toggle();
          })} />
      </View>
      <Text variant="callout" tone="secondary" style={{ marginTop: space[5] }} accessibilityLiveRegion="polite">
        {focus?.reason ?? 'Import your timetable, review class times and choose the term expiry.'}
      </Text>
      {pending ? <View style={{ gap: space[4], marginTop: space[6] }}>
        <Text variant="title1" tabular color={accent.text}>
          {!pending.confirmed ? 'Preparing your warning' : seconds !== null && seconds > 0 ? `DND in ${seconds}s` : 'Checking Android status'}
        </Text>
        <Text variant="callout" tone="secondary">{pending.reason}</Text>
        <Button label="Skip this time" variant="secondary" loading={skipping} disabled={!native || skipping}
          onPress={() => void skip()} />
      </View> : null}
      {focus?.activeUntilMs ? <Text variant="callout" tone="secondary" style={{ marginTop: space[3] }}>
        Automatic session ends at {new Date(focus.activeUntilMs).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}. Android may delay background restoration.
      </Text> : null}
      {message ? <Text variant="callout" tone={failed ? 'danger' : 'secondary'} style={{ marginTop: space[3] }} accessibilityLiveRegion="polite">{message}</Text> : null}
      {focus?.limitations.length ? <>
        <Button label={showLimits ? 'Hide limitations' : 'How predictions work'} variant="ghost" onPress={() => setShowLimits((value) => !value)} />
        {showLimits ? <View style={{ gap: space[3] }}>{focus.limitations.map((limit) =>
          <Text key={limit} variant="callout" tone="secondary">{limit.replaceAll('_', ' ')}</Text>)}</View> : null}
      </> : null}
    </Card>

    {children}

    <SectionHeader title="Your timetable" />
    <Card>
      <View style={{ gap: space[4] }}>
        <Text variant="bodyMedium">{focus?.entries.length ? `${focus.entries.length} reviewed class slots` : 'Learn your week, privately'}</Text>
        <Text variant="callout" tone="secondary">
          Photos, screenshots, PDF, CSV or .ics. Nothing is used until you review it. CSV columns: day, start, end, title.
        </Text>
        {focus?.validUntil ? <Text variant="callout" tone="secondary">Valid {focus.validFrom} to {focus.validUntil} · {focus.timeZone}</Text> : null}
        <Button label={busy === 'import' ? 'Reading on this phone…' : 'Import timetable'} icon={<FilePlus2 size={control.icon} color={c.onAccent} strokeWidth={control.stroke} />}
          color={accent.base} loading={busy === 'import'} disabled={!native || !!busy} onPress={upload} />
        {!native ? <Text variant="callout" tone="secondary">Timetable import requires the rebuilt Android app, not Expo Go.</Text> : null}
        {focus?.entries.length && !review ? <Button label="Review saved times" variant="secondary" disabled={!!busy} onPress={() => {
          setReview(focus.entries.map(draft)); setFrom(focus.validFrom ?? localIsoDate()); setUntil(focus.validUntil ?? '');
          setWarnings([]); setSourceName('Saved timetable'); setPage(0);
        }} /> : null}
      </View>
    </Card>

    {review ? <View style={{ gap: space[5], marginTop: space[4] }}>
      <Text variant="title3">Review: {sourceName}</Text>
      <Text variant="callout" tone="secondary">Check every day and time against the original. Dates on calendar entries are preserved. Importing does not enable DND or replace your saved times.</Text>
      {warnings.length ? <Text variant="callout" tone="danger">{warnings.join('\n')}</Text> : null}
      {!count ? <Text variant="callout" tone="secondary">No reliable class times found. Try a clearer file, or add a class below.</Text> : null}
      {review.slice(currentPage * PAGE_SIZE, (currentPage + 1) * PAGE_SIZE).map((entry, offset) => {
        const index = currentPage * PAGE_SIZE + offset;
        return <View key={entry.id} style={{ gap: space[4], paddingVertical: space[6], borderBottomWidth: 1, borderBottomColor: c.border }}>
          <Input label={`Class ${index + 1}`} value={entry.title} onChangeText={(title) => change(index, { title })} maxLength={160} disabled={!!busy} />
          <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space[2] }}>
            {DAYS.map((day, i) => <DayChoice key={day} day={day} selected={entry.dayOfWeek === i + 1} disabled={!!busy} onPress={() => change(index, { dayOfWeek: i + 1 })} />)}
          </View>
          <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space[3] }}>
            <Input label="Start (24-hour)" value={entry.start} onChangeText={(start) => change(index, { start })} maxLength={5} disabled={!!busy} />
            <Input label="End (24-hour)" value={entry.end} onChangeText={(end) => change(index, { end })} maxLength={5} disabled={!!busy} />
          </View>
          {entry.startDate || entry.endDate ? <Text variant="callout" tone="secondary">Calendar dates: {entry.startDate ?? 'term start'} to {entry.endDate ?? 'term expiry'}</Text> : null}
          <Button label={`Remove class ${index + 1}`} variant="ghost" disabled={!!busy} onPress={() => setReview((rows) => rows?.filter((_, i) => i !== index) ?? null)} />
        </View>;
      })}
      {pageCount > 1 ? <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space[3], alignItems: 'center' }}>
        <Button label="Previous" variant="secondary" disabled={!!busy || currentPage === 0} onPress={() => setPage(currentPage - 1)} />
        <Text variant="callout">{currentPage + 1} / {pageCount}</Text>
        <Button label="Next" variant="secondary" disabled={!!busy || currentPage === pageCount - 1} onPress={() => setPage(currentPage + 1)} />
      </View> : null}
      <Button label="Add a class" variant="secondary" disabled={!!busy || count >= 200} onPress={() => {
        setReview((rows) => [...rows ?? [], { id: `manual-${Date.now()}`, title: '', dayOfWeek: 1, start: '', end: '' }]);
        setPage(Math.floor(count / PAGE_SIZE));
      }} />
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space[3] }}>
        <Input label="Valid from" value={from} onChangeText={setFrom} maxLength={10} disabled={!!busy} placeholder="YYYY-MM-DD" />
        <Input label="Expires on" value={until} onChangeText={setUntil} maxLength={10} disabled={!!busy} placeholder="YYYY-MM-DD" />
      </View>
      <Text variant="callout" tone="secondary">Use your current term dates, up to 180 days. Saved times use this phone’s time zone; expiry prevents an old term from muting a new one.</Text>
      {invalid ? <Text variant="callout" tone="secondary">{invalid}</Text> : null}
      <Button label="Save reviewed timetable" color={accent.base} loading={busy === 'save'} disabled={!!busy || !!invalid} onPress={save} />
      <Button label="Discard import" variant="ghost" disabled={!!busy} onPress={() => setReview(null)} />
    </View> : null}

    <SectionHeader title="Learning on this phone" />
    <Card>
      <View style={{ gap: space[4] }}>
        <Calendar size={control.icon} color={accent.text} strokeWidth={control.stroke} />
        <Text variant="bodyMedium">{focus?.trainingSamples ?? 0} training examples · {focus?.feedbackSamples ?? 0} personal choices</Text>
        <Text variant="callout" tone="secondary">Reviewed class times teach an initial pattern. Skip and your manual DND choices personalise it. Automatic predictions never train themselves.</Text>
        <Text variant="callout" tone="secondary">Source: {focus ? focusSource(focus.source) : 'waiting for timetable'} · Confidence: {focus?.confidence ?? 'unknown'}. This is a prediction, not measured busyness.</Text>
        <Button label="Delete Focus data" variant="ghost" disabled={!native || !!busy} onPress={removeLearning} />
      </View>
    </Card>
  </>;
}

function Input({ label, value, onChangeText, maxLength, disabled, placeholder }: {
  label: string; value: string; onChangeText: (value: string) => void; maxLength: number; disabled: boolean; placeholder?: string;
}) {
  const { c, feature } = useTheme();
  const { fontScale } = useWindowDimensions();
  const structured = maxLength === 5 || maxLength === 10;
  const [focused, setFocused] = useState(false);
  return <View style={{ flex: 1, minWidth: structured ? structuredInputMinWidth(maxLength, fontScale) : space[10] * 2, gap: space[2] }}>
    <Text variant="calloutMedium">{label}</Text>
    <TextInput accessibilityLabel={label} accessibilityState={{ disabled }} value={value} onChangeText={onChangeText} maxLength={maxLength}
      onFocus={() => setFocused(true)} onBlur={() => setFocused(false)} autoComplete="off" importantForAutofill="no"
      editable={!disabled} autoCorrect={false} autoCapitalize="none" allowFontScaling maxFontSizeMultiplier={1.4} placeholder={placeholder ?? label} placeholderTextColor={c.textSecondary}
      style={{ ...type[structured ? 'numeral' : 'body'], color: c.text, padding: space[4], minHeight: control.touchTarget, borderRadius: radius.md,
        borderWidth: control.hairline, borderColor: focused ? feature('focus').text : c.borderStrong, backgroundColor: c.surfaceRaised }} />
  </View>;
}

function DayChoice({ day, selected, disabled, onPress }: { day: string; selected: boolean; disabled: boolean; onPress: () => void }) {
  const { c, feature } = useTheme();
  const accent = feature('focus');
  return <PressableScale disabled={disabled} onPress={onPress} accessibilityLabel={day}
    accessibilityState={{ selected, disabled }} style={{ minWidth: control.touchTarget, minHeight: control.touchTarget,
      alignItems: 'center', justifyContent: 'center', paddingHorizontal: space[3], paddingVertical: space[2], borderRadius: radius.pill,
      borderWidth: control.hairline, borderColor: selected ? accent.text : c.borderStrong,
      backgroundColor: selected ? accent.soft : c.surfaceRaised }}>
    <Text variant="calloutMedium" color={selected ? accent.text : c.text}>{day}</Text>
  </PressableScale>;
}
