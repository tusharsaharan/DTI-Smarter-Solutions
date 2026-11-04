import { beforeEach, describe, expect, it, vi } from 'vitest';
import { getState, initialState, setState } from './store';
import { wipeAll } from './persist';

const mocks = vi.hoisted(() => ({ storage: { removeItemAsync: vi.fn() }, native: {
  configureFocus: vi.fn(), clearFocusLearning: vi.fn(), clearKeyboardLearning: vi.fn(), clearAmbientConfig: vi.fn(),
  stopService: vi.fn(), setNotificationAdaptationEnabled: vi.fn(), clearHistory: vi.fn(), getFocusStatus: vi.fn(), getStatusSnapshot: vi.fn(),
} }));
vi.mock('expo-sqlite/kv-store', () => ({ default: mocks.storage }));
vi.mock('../../modules/attune-native', () => ({ getAttuneNative: () => mocks.native }));

describe('delete all confirmation boundary', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    setState({ ...initialState, onboarded: true });
    for (const name of ['configureFocus', 'clearFocusLearning', 'clearKeyboardLearning', 'clearAmbientConfig',
      'stopService', 'setNotificationAdaptationEnabled', 'clearHistory'] as const) mocks.native[name].mockResolvedValue(true);
    mocks.native.getFocusStatus.mockResolvedValue({ enabled: false, status: 'disabled' });
    mocks.native.getStatusSnapshot.mockResolvedValue({ dndFilter: 1 });
    mocks.storage.removeItemAsync.mockResolvedValue(undefined);
  });

  it('does not erase local settings when Focus cannot be disabled', async () => {
    mocks.native.configureFocus.mockResolvedValue(false);
    await expect(wipeAll()).rejects.toThrow('could not stop');
    expect(mocks.native.clearFocusLearning).not.toHaveBeenCalled();
    expect(mocks.storage.removeItemAsync).not.toHaveBeenCalled();
    expect(getState().onboarded).toBe(true);
  });

  it('does not claim success when Focus deletion or owned restoration failed', async () => {
    mocks.native.clearFocusLearning.mockResolvedValue(false);
    await expect(wipeAll()).rejects.toThrow('restoration');
    expect(mocks.storage.removeItemAsync).not.toHaveBeenCalled();
    expect(getState().onboarded).toBe(true);
  });

  it('checks other data deletion results instead of swallowing failures', async () => {
    mocks.native.clearKeyboardLearning.mockResolvedValue(false);
    await expect(wipeAll()).rejects.toThrow('could not be deleted');
    expect(mocks.storage.removeItemAsync).not.toHaveBeenCalled();
  });

  it('accepts an already stopped service and clears history after Focus restoration', async () => {
    mocks.native.stopService.mockResolvedValue(false);
    await wipeAll();
    expect(mocks.native.clearHistory.mock.invocationCallOrder[0]).toBeGreaterThan(mocks.native.clearFocusLearning.mock.invocationCallOrder[0]);
    expect(getState().onboarded).toBe(false);
  });

  it('preserves verified manual DND rather than manufacturing Off after deletion', async () => {
    mocks.native.getFocusStatus.mockResolvedValue({ enabled: false, status: 'active', ownsDnd: true, dndOrigin: 'manual' });
    mocks.native.getStatusSnapshot.mockResolvedValue({ dndFilter: 2 });
    await wipeAll();
    expect(getState().dndActive).toBe(true);
    expect(getState().focus?.dndOrigin).toBe('manual');
  });

  it('reports settings storage failure and retains the visible state for retry', async () => {
    mocks.storage.removeItemAsync.mockRejectedValue(new Error('storage unavailable'));
    await expect(wipeAll()).rejects.toThrow('storage unavailable');
    expect(getState().onboarded).toBe(true);
  });
});
