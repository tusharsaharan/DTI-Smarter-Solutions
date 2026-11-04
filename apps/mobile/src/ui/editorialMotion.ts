import { control, layout, space, type } from '@attune/design-tokens';

export function editorialIndexWidth(index: string, fontScale: number) {
  const scale = Number.isFinite(fontScale) ? Math.min(1.4, Math.max(1, fontScale)) : 1;
  return Math.max(control.editorial.indexWidth, Math.ceil(index.length * type.numeral.fontSize * 0.7 * scale) + space[1]);
}

/** Finish collapsing only when the whole wrapped title has passed beneath the compact header. */
export function editorialCollapseDistance(titleHeight: number) {
  return Number.isFinite(titleHeight) && titleHeight > 0
    ? Math.max(layout.headerCollapse, space[4] + titleHeight) : layout.headerCollapse;
}

interface MotionLifecycle { focused: boolean; foreground: boolean; reduced: boolean }
interface MotionDriver { cancel: () => void; animate: () => number }

/** The driver is injected so lifecycle cancellation is testable without a native renderer. */
export function startEditorialEntrance(progress: { value: number }, lifecycle: MotionLifecycle, driver: MotionDriver) {
  driver.cancel();
  if (lifecycle.focused && lifecycle.foreground && !lifecycle.reduced) {
    progress.value = 0;
    progress.value = driver.animate();
  } else {
    progress.value = 1;
  }
  return driver.cancel;
}
