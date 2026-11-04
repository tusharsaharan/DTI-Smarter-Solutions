import { getAttuneNative } from '../../modules/attune-native';
import { mapFocusStatus } from './focusContract';
import { setState } from './store';

/** Refresh only verified Android state. Never turn an estimate into a system action. */
export async function refreshFocusState(): Promise<void> {
  const native = getAttuneNative();
  if (!native) return;
  const [focusRaw, snapshot] = await Promise.all([native.getFocusStatus(), native.getStatusSnapshot()]);
  const focus = mapFocusStatus(focusRaw);
  if (!focus) return;
  setState((state) => ({
    focus,
    dndActive: typeof snapshot.dndFilter === 'number' && [2, 3, 4].includes(snapshot.dndFilter),
    features: { ...state.features, focus: { ...state.features.focus, enabled: focus.enabled,
      headline: focus.reason, mode: focus.enabled ? 'reduced' : 'off' } },
  }));
}
