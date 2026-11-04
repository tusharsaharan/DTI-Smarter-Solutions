import { describe, expect, it, vi } from 'vitest';
import { control, type } from '@attune/design-tokens';
import { editorialCollapseDistance, editorialIndexWidth, startEditorialEntrance } from './editorialMotion';

describe('editorial motion and layout boundaries', () => {
  it('reserves enough space for scaled and three-digit index labels', () => {
    for (const scale of [1, 1.4, 2]) {
      const effectiveScale = Math.min(scale, 1.4);
      for (const index of ['01', '99', '100']) {
        expect(editorialIndexWidth(index, scale)).toBeGreaterThanOrEqual(index.length * type.numeral.fontSize * 0.7 * effectiveScale);
      }
    }
    expect(editorialIndexWidth('01', NaN)).toBe(control.editorial.indexWidth);
  });

  it('does not fade a multiline or enlarged title at the old fixed distance', () => {
    expect(editorialCollapseDistance(136)).toBe(152);
    expect(editorialCollapseDistance(200)).toBe(216);
    expect(editorialCollapseDistance(NaN)).toBe(64);
    expect(editorialCollapseDistance(0)).toBe(64);
  });

  it('starts one finite entrance, cancels the old one, and cancels on cleanup', () => {
    const progress = { value: 1 };
    const cancel = vi.fn();
    const animate = vi.fn(() => { expect(progress.value).toBe(0); return 0.5; });
    const cleanup = startEditorialEntrance(progress, { focused: true, foreground: true, reduced: false }, { cancel, animate });
    expect(cancel).toHaveBeenCalledTimes(1);
    expect(animate).toHaveBeenCalledTimes(1);
    expect(progress.value).toBe(0.5);
    cleanup();
    expect(cancel).toHaveBeenCalledTimes(2);
  });

  it.each([
    { focused: false, foreground: true, reduced: false },
    { focused: true, foreground: false, reduced: false },
    { focused: true, foreground: true, reduced: true },
  ])('settles fully visible without animation for %j', (lifecycle) => {
    const progress = { value: 0.4 };
    const driver = { cancel: vi.fn(), animate: vi.fn(() => 0.5) };
    startEditorialEntrance(progress, lifecycle, driver);
    expect(driver.cancel).toHaveBeenCalledOnce();
    expect(driver.animate).not.toHaveBeenCalled();
    expect(progress.value).toBe(1);
  });
});
