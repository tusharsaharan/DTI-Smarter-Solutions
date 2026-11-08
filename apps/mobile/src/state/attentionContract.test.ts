import { describe, expect, it } from 'vitest';
import { mapAttentionHistory, mapAttentionLive, summarizeAttention } from './attentionContract';

describe('attention measurement presentation', () => {
  it('never presents sensor estimates as observed gaze', () => {
    const result = mapAttentionLive({ source: 'sensor_fusion', label: 'ATTENDED', confidence: .45 });
    expect(result.label).toBe('Interaction suggests attention');
    expect(result.source).toBe('sensors');
    expect(mapAttentionLive({ source: 'camera', label: 'ATTENDED' }).label).toBe('Likely looking at screen');
  });

  it('maps native app and hourly aggregates without inventing missing data', () => {
    const [day] = mapAttentionHistory([{
      dayStartMs: 0,
      foregroundMs: 120_000,
      attendedMs: 60_000,
      source: 'mixed',
      appBreakdown: {
        'com.example.reader': {
          screenOnMs: 90_000,
          attendedMs: 45_000,
          glanceMs: 15_000,
          passiveMs: 0,
        },
      },
      hourlyMs: [60_000, 30_000],
    }]);

    expect(day.apps).toEqual([{
      appPackage: 'com.example.reader',
      app: 'com.example.reader',
      screenOnMin: 1.5,
      attendedMin: 0.75,
      glanceMin: 0.25,
      passiveMin: 0,
    }]);
    expect(day.hourly.slice(0, 3)).toEqual([1, 0.5, 0]);
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

  it('sums durations before deriving a weekly view', () => {
    const days = mapAttentionHistory([
      { dayStartMs: 0, foregroundMs: 60_000, attendedMs: 30_000, source: 'camera', hourlyMs: [30_000] },
      { dayStartMs: 86_400_000, foregroundMs: 120_000, attendedMs: 60_000, source: 'sensors', hourlyMs: [60_000] },
    ]);
    expect(summarizeAttention(days)).toMatchObject({
      screenOnMin: 3,
      attendedMin: 1.5,
      classifiedMin: 1.5,
      unknownMin: 1.5,
      days: 2,
    });
    expect(summarizeAttention(days).hourly[0]).toBe(1.5);
  });
});
