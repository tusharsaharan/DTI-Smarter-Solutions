import Storage from 'expo-sqlite/kv-store';
import { featureOrder, type FeatureKey } from '@attune/design-tokens';
import { getState, setState, subscribe, initialState } from './store';
import { getAttuneNative } from '../../modules/attune-native';
import { refreshFocusState } from './focusActions';

const KEY = 'attune.settings.v1';

interface Saved {
  onboarded: boolean;
  enabled: Partial<Record<FeatureKey, boolean>>;
}

let started = false;
let suppressPersist = false;

function snapshot(): Saved {
  const s = getState();
  return {
    onboarded: s.onboarded,
    enabled: Object.fromEntries(featureOrder.map((k) => [k, s.features[k].enabled])),
  };
}

/** Load persisted settings once, then keep saving changes. Safe to call repeatedly. */
export async function hydrate(): Promise<void> {
  if (started) return;
  started = true;
  try {
    const raw = await Storage.getItemAsync(KEY);
    if (raw) {
      const saved = JSON.parse(raw) as Saved;
      setState((s) => ({
        onboarded: !!saved.onboarded,
        features: Object.fromEntries(
          featureOrder.map((k) => [k, { ...s.features[k], enabled: saved.enabled?.[k] ?? s.features[k].enabled }]),
        ) as typeof s.features,
      }));
    }
  } catch {
    // Corrupt or unavailable storage: continue with defaults.
  }
  setState({ hydrated: true });
  let last = JSON.stringify(snapshot());
  subscribe(() => {
    if (suppressPersist) return;
    const next = JSON.stringify(snapshot());
    if (next === last) return;
    last = next;
    Storage.setItemAsync(KEY, next).catch(() => {});
  });
}

export function completeOnboarding() {
  setState({ onboarded: true });
}

/** Forget everything stored on this device and reset settings. */
export async function wipeAll() {
  const native = getAttuneNative();
  if (native) {
    // Stop scheduling before deletion so a queued prediction cannot race the wipe.
    const focus = getState().focus;
    if (!await native.configureFocus(false, focus?.allowPrioritySenders ?? true, focus?.allowRepeatCallers ?? true))
      throw new Error('Automatic Focus could not stop. Check Android DND access and try deleting again.');
    if (!await native.clearFocusLearning())
      throw new Error('Focus deletion or DND restoration could not finish. Check Android DND access and try deleting again.');
    // A false stopService result can mean already stopped, not a deletion failure.
    const stops = await Promise.allSettled([native.stopService('attention'), native.stopService('ambient')]);
    if (stops.some((result) => result.status === 'rejected'))
      throw new Error('A sensor service could not stop. Reopen Attune and try deleting again.');
    const deletions = await Promise.allSettled([
      native.clearKeyboardLearning(),
      native.clearAmbientConfig(),
      native.setNotificationAdaptationEnabled(false),
    ]);
    if (deletions.some((result) => result.status === 'rejected' || result.value !== true))
      throw new Error('Some on-device data could not be deleted. Reopen Attune and try deleting again.');
    // Clear after owned restoration so its log cannot race deletion of the history.
    if (!await native.clearHistory('all')) throw new Error('History could not be deleted. Try deleting again.');
    await refreshFocusState();
  }
  await Storage.removeItemAsync(KEY);
  suppressPersist = true;
  try {
    setState({ ...initialState, hydrated: true, focus: native ? getState().focus : null,
      dndActive: native ? getState().dndActive : false });
  } finally {
    suppressPersist = false;
  }
}
