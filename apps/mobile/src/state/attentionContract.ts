import type { AppAttention, AttentionDay, AttentionLiveStatus, AttentionSource, AttentionSummary } from './types';

const finite = (value: unknown) => typeof value === 'number' && Number.isFinite(value) ? Math.max(0, value) : 0;
const map = (value: unknown): Record<string, unknown> => value && typeof value === 'object' && !Array.isArray(value)
  ? value as Record<string, unknown>
  : {};

export function attentionSource(value: unknown): AttentionSource {
  if (value === 'camera' || value === 'hybrid' || value === 'mixed') return value;
  if (value === 'sensor_fusion' || value === 'interaction' || value === 'sensors') return 'sensors';
  return 'usage';
}

export function mapAttentionLive(record: Record<string, unknown>): AttentionLiveStatus {
  const source = attentionSource(record.source);
  const label = typeof record.label === 'string' ? record.label : 'UNKNOWN';
  const labels: Record<string, string> = {
    ATTENDED: source === 'camera' ? 'Likely looking at screen' : 'Interaction suggests attention',
    PASSIVE: source === 'camera' ? 'Likely looking away' : 'Likely passive use',
    GLANCE: 'Brief foreground use',
    NOT_IN_USE: 'Screen off or locked',
    USAGE_ONLY: 'Foreground time · attention unknown',
    UNKNOWN: 'Attention uncertain',
  };
  return {
    label: labels[label] ?? labels.UNKNOWN,
    source,
    confidence: Math.min(1, finite(record.confidence)),
    reason: typeof record.reason === 'string' ? record.reason.replaceAll('_', ' ') : 'No fresh reading',
    observedAt: finite(record.timestampMs) || Date.now(),
  };
}

export function mapAttentionHistory(records: Record<string, unknown>[]): AttentionDay[] {
  return records.map((record) => {
    const foreground = finite(record.foregroundMs);
    const attended = Math.min(foreground, finite(record.attendedMs));
    const apps: AppAttention[] = Object.entries(map(record.appBreakdown)).map(([appPackage, value]) => {
      const aggregate = map(value);
      return {
        appPackage,
        app: appPackage,
        screenOnMin: finite(aggregate.screenOnMs) / 60_000,
        attendedMin: finite(aggregate.attendedMs) / 60_000,
        glanceMin: finite(aggregate.glanceMs) / 60_000,
        passiveMin: finite(aggregate.passiveMs) / 60_000,
      };
    }).sort((a, b) => b.screenOnMin - a.screenOnMin);
    const hourlyValues = record.hourlyMs;
    const hourly = Array.isArray(hourlyValues)
      ? Array.from({ length: 24 }, (_, index) => finite(hourlyValues[index]) / 60_000)
      : Array.from({ length: 24 }, () => 0);
    const date = new Date(finite(record.dayStartMs));
    return {
      date: `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`,
      screenOnMin: foreground / 60_000,
      attendedMin: attended / 60_000,
      classifiedMin: Math.min(foreground, attended + finite(record.glanceMs) + finite(record.passiveMs)) / 60_000,
      glances: 0,
      source: attentionSource(record.source),
      apps,
      hourly,
    };
  }).sort((a, b) => b.date.localeCompare(a.date));
}

export function summarizeAttention(days: AttentionDay[]): AttentionSummary {
  return days.reduce<AttentionSummary>((summary, day) => ({
    screenOnMin: summary.screenOnMin + day.screenOnMin,
    attendedMin: summary.attendedMin + day.attendedMin,
    classifiedMin: summary.classifiedMin + (day.classifiedMin ?? day.attendedMin),
    unknownMin: summary.unknownMin + Math.max(0, day.screenOnMin - (day.classifiedMin ?? day.attendedMin)),
    hourly: summary.hourly.map((value, index) => value + (day.hourly[index] ?? 0)),
    days: summary.days + 1,
  }), {
    screenOnMin: 0,
    attendedMin: 0,
    classifiedMin: 0,
    unknownMin: 0,
    hourly: Array.from({ length: 24 }, () => 0),
    days: 0,
  });
}
