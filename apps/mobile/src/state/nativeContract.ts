import type { NoiseEnvironment, PermissionStatus, SenderTier } from './types';

export function normalizeNativeScore(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) ? Math.max(0, Math.min(1, value / 100)) : 0;
}

export function normalizeNativeUrgency(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) ? Math.max(0, Math.min(1, value / 50)) : 0;
}

export function normalizeRingVolume(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) ? Math.max(0, Math.min(1, value)) : 0;
}

export function normalizeSenderTier(value: unknown): SenderTier {
  const normalized = String(value ?? 'unknown').toLowerCase();
  return ['family', 'close', 'class', 'work', 'unknown'].includes(normalized) ? (normalized as SenderTier) : 'unknown';
}

export function normalizeEnvironment(value: unknown): NoiseEnvironment {
  const normalized = String(value ?? 'unknown').toLowerCase();
  return ['quiet', 'conversation', 'street', 'transit', 'music'].includes(normalized)
    ? (normalized as NoiseEnvironment)
    : 'unknown';
}

export function permissionStatusFromNative(value: unknown): PermissionStatus | undefined {
  if (typeof value !== 'boolean') return undefined;
  return value ? 'granted' : 'denied';
}

export function notificationVerdict(score: number, adaptation: unknown): 'priority' | 'digest' | 'original' {
  if (adaptation === 'CANCEL_TO_DIGEST') return 'digest';
  if (adaptation === 'REPOST_ATTUNE') return 'priority';
  return 'original';
}

export function notificationMeaning(intent: unknown, source: unknown, confidence: unknown) {
  if (typeof source !== 'string' || source === 'legacy_rules') return undefined;
  return {
    intent: typeof intent === 'string' ? intent.replaceAll('_', ' ').toLowerCase() : 'uncertain',
    source: source === 'on_device_semantic_and_context' ? 'On-device model + context' : 'On-device context rules',
    confidence: typeof confidence === 'number' && Number.isFinite(confidence) ? Math.max(0, Math.min(1, confidence)) : 0,
  };
}
