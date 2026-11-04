import { describe, expect, it } from 'vitest';
import type { ContextSnapshot } from '@/state/types';
import { contextPresentation } from './contextPresentation';

const snapshot = (score: number, patch: Partial<ContextSnapshot> = {}): ContextSnapshot => ({
  at: 1_700_000_000_000, place: 'unknown', activity: 'unknown', event: null,
  appCategory: null, noiseDb: null, environment: null, interruptibility: score,
  reasons: [], ...patch,
});

describe('context folio presentation', () => {
  it('does not invent context, scores, or active sensing before setup', () => {
    const read = contextPresentation(null);
    expect(read.score).toBeNull();
    expect(read.capturedAt).toBeNull();
    expect(read.reasons).toEqual([]);
    expect(read.summary).toContain('Connect');
    expect(read.heading).not.toMatch(/listening|active|measuring/i);
  });

  it.each([0, 24, 25, 54, 55, 79, 80, 100])('presents %s as an estimate, never an applied DND action', (score) => {
    const read = contextPresentation(snapshot(score));
    expect(read.score).toBe(score);
    expect(read.heading).not.toMatch(/gets through|held back|DND|turned on|fully available/i);
  });

  it.each([NaN, Infinity, -1, 101])('abstains on an invalid score %s', (score) => {
    expect(contextPresentation(snapshot(score)).score).toBeNull();
  });

  it('preserves long event titles and human-readable explanations without changing the input', () => {
    const event = 'A long examination timetable review with a changed venue and reporting time';
    const ctx = snapshot(40, { event, activity: 'still', reasons: [' Calendar busy ', 'Phone is still', 'Calendar busy', ''] });
    const read = contextPresentation(ctx);
    expect(read.summary).toContain(event);
    expect(read.reasons).toEqual(['Calendar busy', 'Phone is still']);
    expect(ctx.reasons).toEqual([' Calendar busy ', 'Phone is still', 'Calendar busy', '']);
  });

  it('does not describe an unknown environment or place as measured context', () => {
    const read = contextPresentation(snapshot(60, { environment: 'unknown' }));
    expect(read.summary).toBe('Your surroundings are not yet classified.');
    expect(read.summary).not.toContain('Somewhere');
  });

  it('rejects invalid capture timestamps rather than implying a recent read', () => {
    for (const at of [NaN, Infinity, 0, -1, Number.MAX_VALUE]) {
      const read = contextPresentation(snapshot(40, { at }));
      expect(read.capturedAt).toBeNull();
      expect(read.score).toBeNull();
    }
  });
});
