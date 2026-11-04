import { useCallback, useRef, useState } from 'react';
import { AppState, View } from 'react-native';
import { useFocusEffect } from 'expo-router';
import { Button, Card, Text } from '@/ui';
import { space } from '@/theme';
import { haptic } from '@/lib/haptics';
import { getAttuneNative, type GazeCalibrationStatus } from '../../modules/attune-native';

const STEPS: Record<string, { title: string; instruction: string }> = {
  center: { title: 'Look at the screen centre', instruction: 'Hold your phone at your usual reading distance. Keep your head comfortably still and look at the centre of the screen.' },
  top_left: { title: 'Look at the top-left corner', instruction: 'Move your eyes to the top-left corner of the screen. Keep the phone and your head in the same position.' },
  top_right: { title: 'Look at the top-right corner', instruction: 'Move your eyes to the top-right corner of the screen. Keep the phone and your head in the same position.' },
  bottom_left: { title: 'Look at the bottom-left corner', instruction: 'Move your eyes to the bottom-left corner of the screen. Keep the phone and your head in the same position.' },
  bottom_right: { title: 'Look at the bottom-right corner', instruction: 'Move your eyes to the bottom-right corner of the screen. Keep the phone and your head in the same position.' },
  reading: { title: 'Check: read naturally', instruction: 'Read text on this screen, moving your eyes naturally. Keep your usual phone position. This fresh trial tests the calibration.' },
  away_left: { title: 'Check: look beside the phone', instruction: 'Keep your head facing the phone. Look at an object a hand-width to the LEFT of the phone, not at the screen.' },
  away_right: { title: 'Check: look to the right', instruction: 'Keep your head facing the phone. Look at an object a hand-width to the RIGHT of the phone, not at the screen.' },
  away_down: { title: 'Check: look below the phone', instruction: 'Keep your head facing the phone. Look a hand-width BELOW the phone, not at the screen.' },
};

const REASONS: Record<string, string> = {
  camera_unavailable: 'Enable True Screen Time and camera access, then wait for a fresh camera reading.',
  camera_changed: 'The camera or phone orientation changed. Keep the phone in one position and start again.',
  interrupted: 'The check was interrupted. Keep this page open throughout each step.',
  tracking_unreliable: 'Not enough reliable eye readings. Try even lighting, clean the front lens and keep both eyes visible.',
  position_unstable: 'The phone position or eye readings varied too much. Hold it comfortably steady and try again.',
  validation_failed: 'The check could not reliably separate screen reading from looking away. Try better lighting and a comfortable phone position.',
  save_failed: 'The calibration could not be saved. Please try again.',
};

/** Collection and its clock live in Kotlin; JS only presents the explicit participant prompts. */
export function GazeCalibration({ enabled }: { enabled: boolean }) {
  const [status, setStatus] = useState<GazeCalibrationStatus | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const mounted = useRef(false);
  const request = useRef(false);
  const priorState = useRef<string | null>(null);
  const generation = useRef(0);
  const apply = useCallback((next: GazeCalibrationStatus) => {
    if (!mounted.current) return;
    if (['collecting', 'finalizing'].includes(priorState.current ?? '') && next.state === 'review') void haptic.success();
    if (priorState.current !== 'failed' && next.state === 'failed') void haptic.warn();
    priorState.current = next.state;
    setStatus(next);
  }, []);

  useFocusEffect(useCallback(() => {
    mounted.current = true;
    const native = getAttuneNative();
    let inFlight = false;
    let active = AppState.currentState === 'active';
    const refresh = async () => {
      if (!native || !active || inFlight || request.current) return;
      inFlight = true;
      const current = generation.current;
      try {
        const next = await native.gazeCalibration('status');
        if (current === generation.current) apply(next);
      }
      catch { if (mounted.current) setError('Camera check unavailable. Reopen the installed Android app and try again.'); }
      finally { inFlight = false; }
    };
    void refresh();
    const timer = setInterval(() => void refresh(), 1000);
    const subscription = AppState.addEventListener('change', (state) => {
      active = state === 'active';
      if (!active) void native?.gazeCalibration('cancel').then(apply).catch(() => {});
      else void refresh();
    });
    const blur = AppState.addEventListener('blur', () => {
      active = false;
      generation.current++;
      void native?.gazeCalibration('cancel').then(apply).catch(() => {});
    });
    const focus = AppState.addEventListener('focus', () => { active = true; void refresh(); });
    return () => {
      mounted.current = false;
      clearInterval(timer);
      subscription.remove();
      blur.remove();
      focus.remove();
      generation.current++;
      void native?.gazeCalibration('cancel').catch(() => {});
    };
  }, [apply]));

  const act = async (action: Parameters<NonNullable<ReturnType<typeof getAttuneNative>>['gazeCalibration']>[0]) => {
    const native = getAttuneNative();
    if (!native || request.current) return;
    request.current = true;
    generation.current++;
    const current = generation.current;
    setBusy(true);
    setError(null);
    try {
      const next = await native.gazeCalibration(action);
      if (current === generation.current) apply(next);
    }
    catch { if (mounted.current) setError('The camera check could not continue. Please try again.'); }
    finally { request.current = false; if (mounted.current) setBusy(false); }
  };

  const checking = status && ['ready', 'collecting', 'finalizing', 'review'].includes(status.state);
  const step = status ? STEPS[status.step] : null;
  return (
    <Card style={{ gap: space[4] }}>
      <Text variant="bodyMedium">Personal gaze check</Text>
      {checking && step ? (
        <>
          <Text variant="caption" tone="secondary">Step {status.stepIndex + 1} of {status.stepCount} · 10 seconds each</Text>
          <Text variant="title3">{step.title}</Text>
          <Text variant="callout" tone="secondary">{step.instruction}</Text>
          <Text variant="callout" tone="secondary">Blink normally. Hold your gaze for the full 10 seconds after tapping Start. A completion vibration marks the end; ignore the brief tap vibration.</Text>
          {status.state === 'collecting' ? (
            <Text variant="numeral" tabular>{Math.ceil(status.remainingMs / 1000)} seconds left</Text>
          ) : status.state === 'finalizing' ? (
            <Text variant="callout" accessibilityLiveRegion="polite">10 seconds complete. Checking the last camera result…</Text>
          ) : status.state === 'review' ? (
            <View style={{ gap: space[3] }}>
              <Text variant="callout">Did you follow that instruction for the whole step?</Text>
              <Button label="Yes, continue" loading={busy} onPress={() => void act('confirm')} full />
              <Button label="No, discard this check" variant="secondary" disabled={busy} onPress={() => void act('cancel')} full />
            </View>
          ) : <Button label="Start this step" loading={busy} onPress={() => void act('collect')} full />}
          <Button label="Cancel check" variant="ghost" onPress={() => void act('cancel')} disabled={busy} />
        </>
      ) : (
        <>
          <Text variant="callout" tone="secondary">
            {status?.calibrated ? 'A personal calibration is saved on this phone. It applies near the position you checked; unfamiliar positions stay uncertain.' : 'Teach the camera your normal screen-reading position, then check fresh reading and three off-screen directions. Allow about two minutes.'}
          </Text>
          {status?.state === 'complete' && <Text variant="callout">Your guided checks passed. This is a personal setup check, not a measured accuracy rating.</Text>}
          {status?.reason && <Text variant="callout" tone="secondary">{REASONS[status.reason] ?? 'The check could not finish. Please start again.'}</Text>}
          {!enabled && <Text variant="caption" tone="secondary">Turn on True Screen Time to start.</Text>}
          {enabled && !status?.cameraReady && <Text variant="caption" tone="secondary">Waiting for the camera. Check camera permission and keep this page open.</Text>}
          <Button label={status?.calibrated ? 'Recheck my gaze' : 'Calibrate my gaze'} loading={busy} disabled={!enabled || !status?.cameraReady} onPress={() => void act('begin')} full />
          {status?.calibrated && <Button label="Delete gaze calibration" variant="ghost" disabled={busy} onPress={() => void act('delete')} />}
        </>
      )}
      {error && <Text variant="callout" accessibilityLiveRegion="polite">{error}</Text>}
      <Text variant="caption" tone="tertiary">Only a reduced calibration profile is saved on-device. Frames and individual eye readings are discarded. Delete it here at any time.</Text>
    </Card>
  );
}
