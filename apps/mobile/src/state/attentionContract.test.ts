import { describe, expect, it } from 'vitest';
import { mapAttentionHistory, mapAttentionLive } from './attentionContract';

describe('attention measurement presentation', () => {
  it('never presents sensor estimates as observed gaze', () => {
    const result = mapAttentionLive({ source: 'sensor_fusion', label: 'ATTENDED', confidence: .45 });
    expect(result.label).toBe('Interaction suggests attention');
    expect(result.source).toBe('sensors');
    expect(mapAttentionLive({ source: 'camera', label: 'ATTENDED' }).label).toBe('Likely looking at screen');
  });
  it('preserves mixed measurements and clamps invalid totals', () => {
    const [day] = mapAttentionHistory([{ dayStartMs: 0, foregroundMs: 60_000, attendedMs: 90_000, source: 'mixed' }]);
    expect(day.source).toBe('mixed');
    expect(day.attendedMin).toBe(1);
    expect(day.classifiedMin).toBe(1);
  });
  it('keeps missing or invalid readings uncertain', () => {
    expect(mapAttentionLive({ label: 'new_label', confidence: NaN }).label).toBe('Attention uncertain');
    expect(mapAttentionLive({ confidence: Infinity }).confidence).toBe(0);
    expect(mapAttentionHistory([{ foregroundMs: NaN, source: 'camera_frame' }])[0].source).toBe('usage');
  });
});
