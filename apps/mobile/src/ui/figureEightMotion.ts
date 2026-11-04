import { control, motion } from '@attune/design-tokens';

/** A single continuous loop, inset far enough to keep the travelling dot unclipped. */
export function figureEightPoint(phase: number) {
  'worklet';
  const angle = phase * Math.PI * 2;
  const { pathWidth, pathHeight, dot, trackWidth } = control.intro;
  const inset = dot / 2 + trackWidth;
  return {
    x: pathWidth / 2 + (pathWidth / 2 - inset) * Math.sin(angle),
    y: pathHeight / 2 + (pathHeight / 2 - inset) * Math.sin(angle * 2),
  };
}

/** Ignore the first/resumed frame and bound dropped frames instead of jumping ahead. */
export function advanceFigureEight(phase: number, elapsed: number | null) {
  'worklet';
  if (elapsed === null || !Number.isFinite(elapsed) || elapsed <= 0) return phase;
  return (phase + Math.min(elapsed, motion.maxAmbientFrame) / motion.figureEightDuration) % 1;
}

export function canAnimateFigureEight(visible: boolean, focused: boolean, foreground: boolean, reduced: boolean) {
  return visible && focused && foreground && !reduced;
}
