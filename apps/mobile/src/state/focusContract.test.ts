import { describe, expect, it } from 'vitest';
import { focusCountdownSeconds, freshFocusProbability, mapFocusStatus, mapTimetableEntries,
  parseMinuteTime, timetableValidation, validIsoDate } from './focusContract';

const pending = { id: 'new', createdAtMs: 100_000, deadlineAtMs: 160_000, expiresAtMs: 175_000,
  endAtMs: 200_000, reason: 'Class', source: 'timetable', confidence: 'limited', confirmed: true };

describe('focus native contract', () => {
  it('displays deadlines, never elapsed tick counts, and rejects clock reversal or expiry', () => {
    expect(focusCountdownSeconds(pending, 100_000)).toBe(60);
    expect(focusCountdownSeconds(pending, 153_001)).toBe(7);
    expect(focusCountdownSeconds(pending, 160_000)).toBe(0);
    expect(focusCountdownSeconds(pending, 175_000)).toBeNull();
    expect(focusCountdownSeconds(pending, 99_000)).toBeNull();
  });
  it('does not display malformed or replayed countdowns as fresh', () => {
    expect(mapFocusStatus({ enabled: true, status: 'pending', pending })?.pending?.id).toBe('new');
    expect(mapFocusStatus({ enabled: true, status: 'pending', pending: { ...pending, deadlineAtMs: 100_001 } })?.pending).toBeNull();
    expect(mapFocusStatus({ enabled: true, status: 'pending', pending: { ...pending, expiresAtMs: 900_000 } })?.pending).toBeNull();
    expect(mapFocusStatus({ enabled: 'true', status: 'pending' })).toBeNull();
  });
  it('keeps Skip available without counting unconfirmed notification delivery time', () => {
    const focus = mapFocusStatus({ enabled: true, status: 'warning_posting', pending: { ...pending, confirmed: false } })!;
    expect(focus.pending?.id).toBe('new');
    expect(focusCountdownSeconds(focus.pending, 100_000)).toBeNull();
    const missingConfirmation = mapFocusStatus({ enabled: true, status: 'pending', pending: { ...pending, confirmed: undefined } })!;
    expect(missingConfirmation.pending?.id).toBe('new');
    expect(focusCountdownSeconds(missingConfirmation.pending, 100_000)).toBeNull();
  });
  it('withdraws stale predictions and never fabricates confidence or model probability', () => {
    const focus = mapFocusStatus({ enabled: true, status: 'ready', capturedAtMs: 100_000, probability: .8 })!;
    expect(freshFocusProbability(focus, 200_000)).toBe(.8);
    expect(freshFocusProbability(focus, 220_001)).toBeNull();
    expect(freshFocusProbability(focus, 99_000)).toBeNull();
    expect(mapFocusStatus({ enabled: false, status: 'off', probability: 80 })?.probability).toBeNull();
    expect(freshFocusProbability({ ...focus, enabled: false }, 100_000)).toBeNull();
  });
  it('requires reviewed, bounded, nonexpired class times', () => {
    const entry = { id: '1', dayOfWeek: 1, startMinute: 540, endMinute: 600, title: 'Maths' };
    expect(mapTimetableEntries([entry])).toHaveLength(1);
    expect(mapTimetableEntries([{ ...entry, dayOfWeek: 1.5 }])).toHaveLength(0);
    expect(mapTimetableEntries([{ ...entry, endMinute: 500 }])).toHaveLength(0);
    expect(timetableValidation([entry], '2020-01-01', '2020-02-01')).toContain('expired');
    expect(timetableValidation([entry], '2030-01-01', '2030-12-31')).toContain('180');
    expect(timetableValidation([entry], '2030-01-01', '2030-03-01')).toBeNull();
    expect(validIsoDate('2030-02-30')).toBe(false);
    expect(parseMinuteTime('09:15')).toBe(555);
    expect(parseMinuteTime('9:15')).toBeNaN();
    expect(parseMinuteTime('12:99')).toBeNaN();
  });
  it('maps native numeric confidence, sources and ownership without inventing evidence', () => {
    const focus = mapFocusStatus({ enabled: true, status: 'pending', reason: 'reviewed_timetable_prediction',
      confidence: .62, ownsDnd: true, dndOrigin: 'manual', pending: { ...pending, confidence: .62 } })!;
    expect(focus.confidence).toBe('62% estimated');
    expect(focus.pending?.confidence).toBe('62% estimated');
    expect(focus.reason).toContain('reviewed timetable');
    expect(focus.ownsDnd).toBe(true);
    expect(focus.dndOrigin).toBe('manual');
    expect(mapFocusStatus({ enabled: true, status: 'ready', confidence: null, probability: null })?.confidence).toBe('unknown');
    expect(mapFocusStatus({ enabled: true, status: 'ready', confidence: 80 })?.confidence).toBe('unknown');
  });
  it('matches native inclusive term bounds and preserves calendar dates within them', () => {
    const entry = { id: '1', dayOfWeek: 1, startMinute: 540, endMinute: 600, title: 'Maths' };
    expect(timetableValidation([entry], '2030-01-01', '2030-06-29')).toBeNull();
    expect(timetableValidation([entry], '2030-01-01', '2030-06-30')).toContain('180');
    expect(timetableValidation([{ ...entry, startDate: '2029-12-31' }], '2030-01-01', '2030-03-01')).toContain('every imported class date');
  });
  it('exposes native recovery state without claiming an exception edit succeeded', () => {
    const focus = mapFocusStatus({ enabled: false, status: 'blocked', ownsDnd: true,
      dndOrigin: 'manual', dndPhase: 'restoring', policyUpdatePending: true,
      policyUpdateReason: 'policy_write_rejected' })!;
    expect(focus.dndPhase).toBe('restoring');
    expect(focus.policyUpdatePending).toBe(true);
    expect(focus.policyUpdateReason).toContain('could not finish');
    expect(mapFocusStatus({ enabled: false, status: 'disabled', dndPhase: 'unknown' })?.dndPhase).toBeNull();
  });
});
