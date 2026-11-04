import type { ContextSnapshot } from '@/state/types';

const placeLabel: Record<ContextSnapshot['place'], string> = {
  home: 'At home', college: 'On campus', library: 'In the library', work: 'At work',
  transit: 'In transit', outside: 'Outdoors', unknown: '',
};
const activityLabel: Record<ContextSnapshot['activity'], string> = {
  still: 'Phone is still', walking: 'Walking', running: 'Running', vehicle: 'In a vehicle',
  cycling: 'Cycling', unknown: '',
};

/** Presentation, not policy. A context score never proves that real DND is on. */
export function contextPresentation(ctx: ContextSnapshot | null) {
  if (!ctx) return {
    heading: 'Your day,\nin context.',
    summary: 'Connect your signals to get an interruption estimate.',
    score: null, reasons: [] as string[], capturedAt: null,
  };

  const value = ctx.interruptibility;
  const capturedAt = Number.isFinite(ctx.at) && ctx.at > 0 && Number.isFinite(new Date(ctx.at).getTime()) ? ctx.at : null;
  const score = capturedAt !== null && Number.isFinite(value) && value >= 0 && value <= 100 ? Math.round(value) : null;
  const heading = score === null ? 'Context needs another read'
    : score < 25 ? 'Quiet time was likely'
      : score < 55 ? 'A lower-interruption moment'
        : score < 80 ? 'Room for an interruption'
          : 'A more available moment';
  const summary = [ctx.event?.trim() || placeLabel[ctx.place], activityLabel[ctx.activity],
    ctx.environment && ctx.environment !== 'unknown' ? `${ctx.environment} surroundings` : '']
    .filter(Boolean).join(' · ') || 'Your surroundings are not yet classified.';
  return {
    heading, summary, score,
    reasons: [...new Set(ctx.reasons.map((reason) => reason.trim()).filter(Boolean))],
    capturedAt,
  };
}
