import { describe, expect, it } from 'vitest';
import { featureOrder } from '@attune/design-tokens';
import type { FeatureStatus, RankedNotification } from '@/state/types';
import { featureIndexPresentation, latestPriority } from './featureIndexPresentation';

const status: FeatureStatus = { key: 'notify', enabled: true, mode: 'full', headline: 'One priority message', missing: [] };
const item = (postedAt: number, verdict: RankedNotification['verdict'] = 'priority'): RankedNotification => ({
  id: String(postedAt), app: 'Messages', appPackage: 'example.messages', sender: 'Class coordinator',
  conversation: null, text: 'Reporting time changed to 9:45.', postedAt, verdict,
  score: 0.9, tier: 'class', urgency: 0.8, reasons: [],
});

describe('numbered feature index presentation', () => {
  it('selects the latest priority message rather than the first or highest-scoring item', () => {
    const old = { ...item(10), score: 1 };
    const latest = { ...item(30), score: 0.8 };
    const items = [old, item(40, 'digest'), latest, item(50, 'original')];
    expect(latestPriority(items)).toBe(latest);
    expect(items).toEqual([old, item(40, 'digest'), latest, item(50, 'original')]);
  });

  it('ignores invalid timestamps and does not invent a preview for an empty inbox', () => {
    expect(latestPriority([])).toBeNull();
    expect(latestPriority([item(NaN), item(Infinity), item(-1), item(0)])).toBeNull();
    expect(latestPriority([item(NaN), item(20)])?.postedAt).toBe(20);
  });

  it('exposes the same priority sender and text to assistive technology', () => {
    const read = featureIndexPresentation('notify', status, item(20));
    expect(read.preview).toBe('Class coordinator · Reporting time changed to 9:45.');
    expect(read.accessibilityLabel).toContain(read.preview);
  });

  it('keeps sensitive previews out of disabled and non-running feature rows', () => {
    for (const patch of [{ enabled: false }, { mode: 'off' as const }]) {
      const read = featureIndexPresentation('notify', { ...status, ...patch }, item(20));
      expect(read.preview).toBeNull();
      expect(read.accessibilityLabel).not.toContain('Reporting time');
      expect(read.enabled).toBe(false);
    }
    expect(featureIndexPresentation('notify', { ...status, mode: 'off' }, item(20)).mode).toBe('Not running');
  });

  it('numbers all five existing capabilities without leaking inbox content into another feature', () => {
    for (const [i, key] of featureOrder.entries()) {
      const read = featureIndexPresentation(key, { ...status, key }, item(20));
      expect(read.index).toBe(`0${i + 1}`);
      if (key !== 'notify') expect(read.preview).toBeNull();
    }
  });
});
