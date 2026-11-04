import { beforeEach, describe, expect, it, vi } from 'vitest';
import { syncNativeFeature } from './nativeActions';

const native = vi.hoisted(() => ({ configureFocus: vi.fn(), applyDnd: vi.fn(), getInterruptibility: vi.fn() }));
vi.mock('../../modules/attune-native', () => ({ getAttuneNative: () => native }));

describe('focus activation boundary', () => {
  beforeEach(() => vi.clearAllMocks());
  it('enables native prediction, never directly applies DND from an app toggle', async () => {
    native.configureFocus.mockResolvedValue(true);
    expect(await syncNativeFeature('focus', true)).toBe(true);
    expect(native.configureFocus).toHaveBeenCalledWith(true, true, true);
    expect(native.applyDnd).not.toHaveBeenCalled();
    expect(native.getInterruptibility).not.toHaveBeenCalled();
  });
  it('disables the scheduler rather than racing a queued activation', async () => {
    native.configureFocus.mockResolvedValue(true);
    expect(await syncNativeFeature('focus', false)).toBe(true);
    expect(native.configureFocus).toHaveBeenCalledWith(false, true, true);
    expect(native.applyDnd).not.toHaveBeenCalled();
  });
});
