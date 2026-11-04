import { describe, expect, it } from 'vitest';
import { control, motion } from '@attune/design-tokens';
import { advanceFigureEight, canAnimateFigureEight, figureEightPoint } from './figureEightMotion';

describe('onboarding figure-eight geometry', () => {
  it('closes seamlessly and crosses the centre twice each cycle', () => {
    for (const phase of [0, 0.5, 1]) {
      const point = figureEightPoint(phase);
      expect(point.x).toBeCloseTo(control.intro.pathWidth / 2);
      expect(point.y).toBeCloseTo(control.intro.pathHeight / 2);
    }
    expect(figureEightPoint(0.25).x).toBeGreaterThan(control.intro.pathWidth / 2);
    expect(figureEightPoint(0.75).x).toBeLessThan(control.intro.pathWidth / 2);
    expect(figureEightPoint(1.125).x).toBeCloseTo(figureEightPoint(0.125).x);
    expect(figureEightPoint(1.125).y).toBeCloseTo(figureEightPoint(0.125).y);
  });

  it('keeps every sampled dot fully within the canvas', () => {
    const radius = control.intro.dot / 2;
    for (let i = 0; i <= 1000; i++) {
      const point = figureEightPoint(i / 1000);
      expect(point.x - radius).toBeGreaterThanOrEqual(0);
      expect(point.y - radius).toBeGreaterThanOrEqual(0);
      expect(point.x + radius).toBeLessThanOrEqual(control.intro.pathWidth);
      expect(point.y + radius).toBeLessThanOrEqual(control.intro.pathHeight);
    }
  });
});

describe('onboarding ambient animation', () => {
  it('runs only when visible, focused, foregrounded, and motion is allowed', () => {
    expect(canAnimateFigureEight(true, true, true, false)).toBe(true);
    expect(canAnimateFigureEight(false, true, true, false)).toBe(false);
    expect(canAnimateFigureEight(true, false, true, false)).toBe(false);
    expect(canAnimateFigureEight(true, true, false, false)).toBe(false);
    expect(canAnimateFigureEight(true, true, true, true)).toBe(false);
  });

  it('advances by elapsed time, not device refresh rate', () => {
    const run = (hz: number) => {
      let phase = 0;
      for (let i = 0; i < hz; i++) phase = advanceFigureEight(phase, 1000 / hz);
      return phase;
    };
    expect(run(60)).toBeCloseTo(run(120));
    expect(run(60)).toBeCloseTo(1000 / motion.figureEightDuration);
  });

  it('ignores invalid and first/resumed frames; bounds long stalls', () => {
    for (const elapsed of [null, 0, -16, NaN, Infinity]) expect(advanceFigureEight(0.4, elapsed)).toBe(0.4);
    expect(advanceFigureEight(0.4, 60000)).toBeCloseTo(0.4 + motion.maxAmbientFrame / motion.figureEightDuration);
    expect(advanceFigureEight(0.999, 32)).toBeLessThan(0.01);
  });
});
