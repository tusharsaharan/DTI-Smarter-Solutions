export interface TimetableEntry {
  id: string;
  dayOfWeek: number;
  startMinute: number;
  endMinute: number;
  title: string;
  startDate?: string | null;
  endDate?: string | null;
}

export interface FocusPending {
  id: string;
  createdAtMs: number;
  deadlineAtMs: number;
  expiresAtMs: number;
  endAtMs: number;
  reason: string;
  source: string;
  confidence: string;
  confirmed: boolean;
}

export interface FocusStatus {
  enabled: boolean;
  status: string;
  reason: string;
  source: string;
  confidence: string;
  probability: number | null;
  capturedAtMs: number | null;
  pending: FocusPending | null;
  activeUntilMs: number | null;
  ownsDnd: boolean;
  dndOrigin: 'automatic' | 'manual' | null;
  dndPhase: 'prepared' | 'active' | 'updating' | 'restoring' | null;
  policyUpdatePending: boolean;
  policyUpdateReason: string | null;
  entries: TimetableEntry[];
  validFrom: string | null;
  validUntil: string | null;
  timeZone: string | null;
  trainingSamples: number;
  feedbackSamples: number;
  trainedAtMs: number | null;
  allowPrioritySenders: boolean;
  allowRepeatCallers: boolean;
  limitations: string[];
}

const map = (value: unknown): Record<string, unknown> =>
  value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {};
const number = (value: unknown): number | null => typeof value === 'number' && Number.isFinite(value) ? value : null;
const text = (value: unknown): string | null => typeof value === 'string' && value.trim() ? value : null;
export const strings = (value: unknown): string[] => Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : [];

const REASONS: Record<string, string> = {
  not_evaluated: 'Waiting for fresh context',
  focus_disabled: 'Automatic Focus is off. Manual DND is still available.',
  policy_access_required: 'Allow Do Not Disturb access in Android settings to use Focus.',
  visible_focus_warning_required: 'Allow notifications and the Smart Focus warning channel before automatic DND can start.',
  preexisting_dnd_preserved: 'DND was already on. Attune will leave it alone.',
  unrelated_dnd_snapshot_preserved: 'Another DND session is recorded. Attune will not replace it.',
  not_owned_preserving_user_dnd: 'This DND state is not owned by Attune. Change it in Android settings.',
  user_dnd_override_preserved: 'Your Android DND change has been preserved.',
  occurrence_or_choice_cooldown: 'Skipped for this class or recent choice. No new countdown yet.',
  prediction_skipped: 'Skipped this time. Your choice helps personalise Focus.',
  reviewed_timetable_prediction: 'Your reviewed timetable suggests a class is in progress.',
  learned_busy_calendar_prediction: 'Your choices and current calendar suggest a busy moment.',
  learned_focus_time_prediction: 'Your manual choices suggest this is a Focus time.',
  review_new_timetable_dates: 'Your timetable has expired. Review the current term dates.',
  no_supported_focus_prediction: 'No supported busy-time prediction right now.',
  manual_dnd_owned: 'Manual DND is on. Only you decide when to end it.',
  manual_dnd_on: 'You started DND manually.',
  manual_dnd_off: 'You ended Attune DND.',
  focus_session_ended: 'Automatic Focus ended. Your previous DND policy was restored.',
  dnd_restore_pending: 'Android could not restore the previous policy yet. Check DND access; Attune will retry.',
  stale_or_changed_focus_deadline: 'The prediction changed or expired. Automatic DND was cancelled.',
  focus_state_persist_failed: 'Local Focus storage is unavailable. New automatic DND is blocked.',
  encrypted_store_unavailable: 'Encrypted Focus data could not be read. Automatic DND is blocked.',
  confirming_visible_warning: 'Confirming the Android warning before starting the full 60 seconds.',
  focus_warning_not_posted: 'The warning was not confirmed or its evidence changed. Automatic DND was cancelled.',
  dnd_observation_gap_preserved: 'DND may have changed while Attune was closed. Manage it in Android settings; Attune will leave it alone.',
  focus_data_delete_failed: 'Focus data could not be deleted. Reopen Attune and try again.',
  visible_warning_or_deadline_unavailable: 'The warning could not be shown or scheduled. Automatic DND is blocked.',
  native_wakeup_unavailable: 'Android could not schedule Focus. No automatic DND will start.',
  validity_must_be_1_to_180_days: 'Choose a timetable covering no more than 180 days, including both dates.',
  entry_dates_outside_reviewed_validity: 'Include every imported class date within the reviewed term dates.',
  focus_data_cleared: 'Timetable and personal Focus learning deleted. Automatic Focus is off.',
  skip_cancelled_but_feedback_save_failed: 'The countdown was cancelled, but your choice could not be saved.',
  manual_dnd_policy_update_pending: 'Your exception change is pending recovery. Check Android DND settings before retrying.',
  owned_manual_dnd_policy_unavailable: 'The manual DND policy could not be verified. Check Android DND settings.',
  policy_write_guard_rejected: 'The warning or DND state changed before Android could finish. Automatic DND was cancelled; check the current Android policy.',
  policy_filter_changed: 'Android DND changed during this action. Attune will not replace the changed state.',
  policy_snapshot_owner_mismatch: 'This DND snapshot belongs to another session. Attune will leave it alone.',
  policy_snapshot_changed: 'DND ownership changed during this action. Check Android settings.',
  policy_write_rejected: 'Android could not finish changing DND. Check DND access and the current policy before retrying.',
  policy_restore_rejected: 'Android could not finish restoring DND. Check policy access; Attune will retry its owned restoration.',
};

export const focusReason = (value: string): string => REASONS[value] ?? value.replaceAll('_', ' ');
export function focusSource(value: string): string {
  if (value === 'untrained') return 'Not trained yet';
  if (value.startsWith('timetable_prior')) return 'Reviewed timetable, local model';
  if (value.startsWith('personalized_timetable')) return 'Timetable and your choices, local model';
  if (value.startsWith('personalized_calendar')) return 'Calendar and your choices, local model';
  if (value.startsWith('personalized')) return 'Your choices, local model';
  return value.replaceAll('_', ' ');
}
const confidence = (value: unknown): string => {
  const score = number(value);
  return score !== null && score >= 0 && score <= 1 ? `${Math.round(score * 100)}% estimated` : text(value) ?? 'unknown';
};

export function mapTimetableEntries(value: unknown): TimetableEntry[] {
  if (!Array.isArray(value)) return [];
  return value.slice(0, 200).flatMap((item, i) => {
    const row = map(item);
    const day = number(row.dayOfWeek), start = number(row.startMinute), end = number(row.endMinute);
    const title = text(row.title);
    if (day === null || start === null || end === null || !Number.isInteger(day) ||
      !Number.isInteger(start) || !Number.isInteger(end) || day < 1 || day > 7 || start < 0 ||
      end > 1440 || end <= start || end - start > 720 || !title || title.length > 160 || /[\u0000-\u001f\u007f]/.test(title)) return [];
    return [{ id: text(row.id) ?? `import-${i}`, dayOfWeek: day, startMinute: start, endMinute: end,
      title, startDate: text(row.startDate), endDate: text(row.endDate) }];
  });
}

export function mapFocusStatus(value: unknown): FocusStatus | null {
  const row = map(value);
  if (typeof row.enabled !== 'boolean' || !text(row.status)) return null;
  const rawPending = map(row.pending);
  const id = text(rawPending.id);
  const created = number(rawPending.createdAtMs), deadline = number(rawPending.deadlineAtMs);
  const expires = number(rawPending.expiresAtMs), end = number(rawPending.endAtMs);
  const validPending = id && created !== null && deadline !== null && expires !== null && end !== null &&
    created > 0 && deadline - created === 60_000 && expires >= deadline && expires - deadline <= 30_000 && end > deadline;
  const probability = number(row.probability);
  return {
    enabled: row.enabled, status: text(row.status)!, reason: focusReason(text(row.reason) ?? 'Waiting for fresh context'),
    source: text(row.source) ?? 'unknown', confidence: confidence(row.confidence),
    probability: probability !== null && probability >= 0 && probability <= 1 ? probability : null,
    capturedAtMs: number(row.capturedAtMs),
    pending: validPending ? { id, createdAtMs: created, deadlineAtMs: deadline, expiresAtMs: expires,
      endAtMs: end, reason: focusReason(text(rawPending.reason) ?? 'Predicted busy time'), source: text(rawPending.source) ?? 'unknown',
      confidence: confidence(rawPending.confidence), confirmed: rawPending.confirmed === true } : null,
    activeUntilMs: number(row.activeUntilMs), entries: mapTimetableEntries(row.entries),
    ownsDnd: row.ownsDnd === true, dndOrigin: row.dndOrigin === 'automatic' || row.dndOrigin === 'manual' ? row.dndOrigin : null,
    dndPhase: row.dndPhase === 'prepared' || row.dndPhase === 'active' || row.dndPhase === 'updating' || row.dndPhase === 'restoring' ? row.dndPhase : null,
    policyUpdatePending: row.policyUpdatePending === true,
    policyUpdateReason: text(row.policyUpdateReason) ? focusReason(text(row.policyUpdateReason)!) : null,
    validFrom: text(row.validFrom), validUntil: text(row.validUntil), timeZone: text(row.timeZone),
    trainingSamples: Math.max(0, Math.trunc(number(row.trainingSamples) ?? 0)),
    feedbackSamples: Math.max(0, Math.trunc(number(row.feedbackSamples) ?? 0)), trainedAtMs: number(row.trainedAtMs),
    allowPrioritySenders: row.allowPrioritySenders !== false, allowRepeatCallers: row.allowRepeatCallers !== false,
    limitations: strings(row.limitations),
  };
}

/** Display only. Android owns timing and actions, never this JavaScript counter. */
export function focusCountdownSeconds(pending: FocusPending | null, now: number): number | null {
  if (!pending || !pending.confirmed || now < pending.createdAtMs || now >= pending.expiresAtMs || now >= pending.endAtMs) return null;
  return Math.max(0, Math.ceil((pending.deadlineAtMs - now) / 1000));
}

export function freshFocusProbability(status: FocusStatus | null, now: number): number | null {
  if (!status?.enabled || status.capturedAtMs === null || now - status.capturedAtMs < 0 ||
    now - status.capturedAtMs > 120_000) return null;
  return status.probability;
}

export function localIsoDate(date = new Date()): string {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}

export function validIsoDate(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const date = new Date(`${value}T12:00:00Z`);
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === value;
}

export function timetableValidation(entries: TimetableEntry[], from: string, until: string): string | null {
  if (!validIsoDate(from) || !validIsoDate(until)) return 'Enter the timetable start and expiry as YYYY-MM-DD.';
  const days = (Date.parse(`${until}T12:00:00Z`) - Date.parse(`${from}T12:00:00Z`)) / 86_400_000;
  if (days < 0 || days >= 180) return 'Choose a timetable covering no more than 180 days, including both dates.';
  if (until < localIsoDate()) return 'This timetable has expired. Choose the current term dates.';
  if (!entries.length || entries.length > 200 || mapTimetableEntries(entries).length !== entries.length)
    return 'Review every class: choose a day, title and valid start/end time (up to 12 hours).';
  if (entries.some((entry) => (entry.startDate && !validIsoDate(entry.startDate)) ||
    (entry.endDate && !validIsoDate(entry.endDate)) ||
    (entry.startDate && entry.endDate && entry.startDate > entry.endDate)))
    return 'An imported class has invalid dates. Remove it or import a corrected calendar.';
  if (entries.some((entry) => (entry.startDate && (entry.startDate < from || entry.startDate > until)) ||
    (entry.endDate && (entry.endDate < from || entry.endDate > until))))
    return 'Include every imported class date within the reviewed term dates.';
  if (entries.some((entry) => entry.startDate && entry.startDate === entry.endDate &&
    ((new Date(`${entry.startDate}T12:00:00Z`).getUTCDay() + 6) % 7) + 1 !== entry.dayOfWeek))
    return 'A calendar class day does not match its date. Choose the original weekday or remove that class.';
  return null;
}

export const minuteTime = (minute: number): string => `${String(Math.floor(minute / 60)).padStart(2, '0')}:${String(minute % 60).padStart(2, '0')}`;
export function parseMinuteTime(value: string): number {
  if (!/^(?:[01]\d|2[0-3]):[0-5]\d$/.test(value) && value !== '24:00') return NaN;
  const [hour, minute] = value.split(':').map(Number);
  return hour * 60 + minute;
}
