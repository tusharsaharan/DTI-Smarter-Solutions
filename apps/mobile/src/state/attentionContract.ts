import type { AttentionDay, AttentionLiveStatus, AttentionSource } from './types';

const finite = (value: unknown) => typeof value === 'number' && Number.isFinite(value) ? Math.max(0, value) : 0;

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
  };
}

export function mapAttentionHistory(records: Record<string, unknown>[]): AttentionDay[] {
  return records.map((record) => {
    const foreground = finite(record.foregroundMs);
    const attended = Math.min(foreground, finite(record.attendedMs));
    const date = new Date(finite(record.dayStartMs));
    return {
      date: `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`,
      screenOnMin: foreground / 60_000,
      attendedMin: attended / 60_000,
      classifiedMin: Math.min(foreground, attended + finite(record.glanceMs) + finite(record.passiveMs)) / 60_000,
      glances: 0,
      source: attentionSource(record.source),
      apps: [],
      hourly: Array.from({ length: 24 }, () => 0),
    };
  }).sort((a, b) => b.date.localeCompare(a.date));
}
