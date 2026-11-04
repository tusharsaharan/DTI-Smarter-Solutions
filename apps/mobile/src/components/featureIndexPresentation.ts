import { features, type FeatureKey } from '@attune/design-tokens';
import type { FeatureMode, FeatureStatus, RankedNotification } from '@/state/types';

const modeLabel: Record<FeatureMode, string> = {
  full: 'Full mode', reduced: 'Reduced mode', minimal: 'Minimal mode', off: 'Not running',
};

/** Select the latest already-ranked priority item. This never changes ranking or native state. */
export function latestPriority(items: RankedNotification[]): RankedNotification | null {
  return items.reduce<RankedNotification | null>((latest, item) =>
    item.verdict === 'priority' && Number.isFinite(item.postedAt) && item.postedAt > 0 &&
      (!latest || item.postedAt > latest.postedAt) ? item : latest, null);
}

export function featureIndexPresentation(key: FeatureKey, status: FeatureStatus, top: RankedNotification | null) {
  const enabled = status.enabled && status.mode !== 'off';
  const mode = status.enabled ? modeLabel[status.mode] : 'Off';
  const preview = key === 'notify' && enabled && top ? `${top.sender} · ${top.text}` : null;
  return {
    enabled, mode, preview,
    index: String(features[key].index).padStart(2, '0'),
    accessibilityLabel: `${features[key].name}. ${status.headline}. ${mode}.${preview ? ` ${preview}` : ''}`,
  };
}
