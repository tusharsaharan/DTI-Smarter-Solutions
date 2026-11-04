import { afterEach, describe, expect, it, vi } from 'vitest';
import { getState, initialState, setState } from './store';
import { startNativeHydration } from './nativeHydration';

const native = vi.hoisted(() => ({
  getStatusSnapshot: vi.fn(), getInterruptibility: vi.fn(), getNotificationRecords: vi.fn(),
  getAmbientHistory: vi.fn(), getHistory: vi.fn(), applyDnd: vi.fn(),
  addListener: vi.fn(() => ({ remove: vi.fn() })),
}));
vi.mock('../../modules/attune-native', () => ({ getAttuneNative: () => native }));
vi.mock('react-native', () => ({ AppState: { addEventListener: () => ({ remove: vi.fn() }) } }));

describe('focus hydration safety', () => {
  afterEach(() => { setState(initialState); vi.clearAllMocks(); });
  it('reads native scheduler truth without acting on a suppressing context', async () => {
    setState({ features: { ...initialState.features, focus: { ...initialState.features.focus, enabled: true } } });
    native.getStatusSnapshot.mockResolvedValue({ focus: { enabled: false, status: 'off', reason: 'Prediction off' }, dndFilter: 1 });
    native.getInterruptibility.mockResolvedValue({ score: 1, shouldSuppress: true, capturedAtMs: Date.now() });
    native.getNotificationRecords.mockResolvedValue([]);
    native.getAmbientHistory.mockResolvedValue([]);
    native.getHistory.mockResolvedValue({ records: [] });
    const stop = startNativeHydration();
    await vi.waitFor(() => expect(getState().focus?.status).toBe('off'));
    stop();
    expect(getState().features.focus.enabled).toBe(false);
    expect(native.applyDnd).not.toHaveBeenCalled();
  });
});
