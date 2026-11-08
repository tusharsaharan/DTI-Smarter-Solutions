import { AppState, type AppStateStatus } from 'react-native';
import type { FeatureKey } from '@attune/design-tokens';
import { getAttuneNative, type AmbientSampleEvent, type NotificationRankedEvent } from '../../modules/attune-native';
import { getState, setState } from './store';
import { mapAttentionHistory, mapAttentionLive } from './attentionContract';
import { mapFocusStatus } from './focusContract';
import {
  normalizeEnvironment,
  normalizeNativeScore,
  normalizeNativeUrgency,
  normalizeRingVolume,
  normalizeSenderTier,
  notificationVerdict,
  notificationMeaning,
} from './nativeContract';
import type {
  ContextSnapshot,
  FeatureMode,
  FeatureStatus,
  PermissionKey,
  PermissionStatus,
  RankedNotification,
  VolumeSample,
} from './types';

type JsonMap = Record<string, unknown>;
type NativeSubscription = { remove: () => void };

const FEATURE_KEYS: FeatureKey[] = ['notify', 'keyboard', 'focus', 'attention', 'volume'];
const PERMISSION_KEYS: PermissionKey[] = [
  'notificationAccess',
  'postNotifications',
  'dndPolicy',
  'usageAccess',
  'activityRecognition',
  'calendar',
  'location',
  'camera',
  'microphone',
  'batteryExemption',
  'keyboardEnabled',
];

const asMap = (value: unknown): JsonMap => (value && typeof value === 'object' ? (value as JsonMap) : {});
const asBoolean = (value: unknown): boolean | undefined => (typeof value === 'boolean' ? value : undefined);
const asNumber = (value: unknown): number | undefined => (typeof value === 'number' && Number.isFinite(value) ? value : undefined);
const asString = (value: unknown): string | undefined => (typeof value === 'string' && value.length > 0 ? value : undefined);
const asStringArray = (value: unknown): string[] => (Array.isArray(value) ? value.filter((v): v is string => typeof v === 'string') : []);

function permissionStatus(value: unknown): PermissionStatus | undefined {
  const bool = asBoolean(value);
  return bool === undefined ? undefined : bool ? 'granted' : 'denied';
}

function readPermission(snapshot: JsonMap, key: PermissionKey): PermissionStatus | undefined {
  const nested = asMap(snapshot.permissions);
  const nativeKeys: Record<PermissionKey, string> = {
    notificationAccess: 'notificationAccess',
    postNotifications: 'postNotifications',
    dndPolicy: 'dndPolicy',
    usageAccess: 'usageAccess',
    activityRecognition: 'activityRecognition',
    calendar: 'calendar',
    location: 'location',
    camera: 'camera',
    microphone: 'microphone',
    batteryExemption: 'batteryExemption',
    keyboardEnabled: 'keyboardEnabled',
  };
  const directFallback: Partial<Record<PermissionKey, string>> = {
    notificationAccess: 'notificationAccess',
    dndPolicy: 'dndPolicyAccess',
    usageAccess: 'usageStatsAccess',
    calendar: 'calendarAccess',
    camera: 'cameraPermission',
    microphone: 'microphonePermission',
    keyboardEnabled: 'keyboardSelected',
  };
  return permissionStatus(nested[nativeKeys[key]]) ?? permissionStatus(snapshot[directFallback[key] ?? '']);
}

export function mapPermissionTruth(snapshot: JsonMap, previous: Record<PermissionKey, PermissionStatus>): Record<PermissionKey, PermissionStatus> {
  return Object.fromEntries(
    PERMISSION_KEYS.map((key) => [key, readPermission(snapshot, key) ?? previous[key] ?? 'unknown']),
  ) as Record<PermissionKey, PermissionStatus>;
}

function nativeMode(enabled: boolean, available: boolean, fallback: boolean): FeatureMode {
  if (!enabled) return 'off';
  if (available) return 'full';
  if (fallback) return 'reduced';
  return 'off';
}

export function deriveFeatureStatuses(
  snapshot: JsonMap,
  permissions: Record<PermissionKey, PermissionStatus>,
  enabled: Record<FeatureKey, boolean>,
): Record<FeatureKey, FeatureStatus> {
  const connected = asBoolean(snapshot.notificationListenerConnected) === true;
  const camera = asMap(snapshot.camera);
  const attentionSensors = asMap(snapshot.attentionSensors);
  const liveAttention = mapAttentionLive(asMap(snapshot.attention));
  const ambient = asMap(snapshot.ambient);
  const usage = asMap(snapshot.usageWindow);
  const usageReady = permissions.usageAccess === 'granted';
  const micReady = permissions.microphone === 'granted';
  const notifyAccessReady = permissions.notificationAccess === 'granted';
  const postReady = permissions.postNotifications !== 'denied';
  const notifyReady = notifyAccessReady && connected && postReady;
  const dndReady = permissions.dndPolicy === 'granted';
  const focus = mapFocusStatus(snapshot.focus);
  const keyboardReady = permissions.keyboardEnabled === 'granted';
  const cameraRunning = asBoolean(camera.running) === true;
  const sensorsRunning = asBoolean(attentionSensors.running) === true;
  const ambientRunning = asBoolean(ambient.running) === true;
  const usageForeground = asNumber(usage.foregroundMs) ?? 0;
  const contextStatus = asMap(snapshot.context);
  const contextAvailable = snapshot.calendarBusy === true || asString(snapshot.foregroundPackage) !== undefined || contextStatus.foregroundAppAvailable === true;

  return {
    notify: {
      key: 'notify',
      enabled: enabled.notify,
      mode: nativeMode(enabled.notify, notifyReady && asMap(snapshot.notificationSemantics).state === 'ready', notifyReady),
      headline: !notifyAccessReady
        ? 'Notification access is required'
        : !postReady
          ? 'Allow Attune to post priority alerts'
          : !connected
            ? 'Access granted · waiting for listener'
            : asMap(snapshot.notificationSemantics).state === 'ready'
              ? 'On-device meaning and context ranking active'
              : 'Context rules active · semantic model loading or unavailable',
      missing: !notifyAccessReady
        ? ['notificationAccess']
        : permissions.postNotifications === 'denied'
          ? ['postNotifications']
          : [],
    },
    keyboard: {
      key: 'keyboard',
      enabled: enabled.keyboard,
      mode: nativeMode(enabled.keyboard, keyboardReady, true),
      headline: keyboardReady
        ? 'Attune is the active system keyboard'
        : 'Enable Attune in Android keyboard settings',
      missing: keyboardReady ? [] : ['keyboardEnabled'],
    },
    focus: {
      key: 'focus',
      enabled: enabled.focus,
      mode: nativeMode(enabled.focus, dndReady && postReady && !!focus &&
        (focus.entries.length > 0 || focus.feedbackSamples > 0 || contextAvailable), dndReady),
      headline: !dndReady
        ? 'Do Not Disturb access is required'
        : permissions.postNotifications === 'denied'
          ? 'Allow notifications to show the countdown'
          : focus?.reason ?? 'Upload your timetable to learn busy times',
      missing: !dndReady ? ['dndPolicy'] : permissions.postNotifications === 'denied' ? ['postNotifications'] : [],
    },
    attention: {
      key: 'attention',
      enabled: enabled.attention,
      mode: nativeMode(enabled.attention, cameraRunning && liveAttention.source === 'camera' && liveAttention.confidence > 0, usageReady || sensorsRunning),
      headline: !usageReady
        ? 'Usage access is required'
        : cameraRunning || sensorsRunning
          ? `${liveAttention.label} · ${liveAttention.source === 'camera' ? 'camera' : 'sensor estimate'}`
          : usageForeground > 0
            ? 'Usage baseline active · camera optional'
            : 'Waiting for real usage on this phone',
      missing: usageReady ? [] : ['usageAccess'],
    },
    volume: {
      key: 'volume',
      enabled: enabled.volume,
      mode: nativeMode(enabled.volume, micReady && ambientRunning, false),
      headline: !micReady
        ? 'Microphone access is required for live sensing'
        : ambientRunning
          ? 'Measuring room loudness on-device'
          : 'Microphone ready · sensing is off',
      missing: micReady ? [] : ['microphone'],
    },
  };
}

export function mapNotification(event: NotificationRankedEvent): RankedNotification {
  const score = normalizeNativeScore(event.score);
  const urgency = normalizeNativeUrgency(event.urgencyScore);
  const adaptation = event.adaptation ?? '';
  const verdict: RankedNotification['verdict'] = notificationVerdict(score, adaptation);
  return {
    id: event.id,
    app: event.packageName,
    appPackage: event.packageName,
    sender: event.sender ?? 'Notification',
    conversation: event.conversation ?? null,
    text: event.text ?? 'Notification content is available only while the source notification is active.',
    postedAt: event.postedAtMs,
    score,
    tier: normalizeSenderTier(event.senderTier),
    urgency,
    verdict,
    meaning: notificationMeaning(event.semanticIntent, event.semanticSource, event.semanticConfidence),
    reasons: [...event.reasonCodes, event.adaptationReason].filter((reason): reason is string => Boolean(reason))
      .map((reason) => reason.startsWith('college_current_schedule_') ? 'College schedule update' : reason.replaceAll('_', ' ')),
  };
}

export function mapAmbientSample(event: AmbientSampleEvent): VolumeSample | null {
  const db = asNumber(event.db);
  if (db === undefined) return null;
  return {
    at: asNumber(event.atMs) ?? Date.now(),
    db,
    environment: normalizeEnvironment(event.environment),
    ringVolume: normalizeRingVolume(event.ringVolume),
    source: asString(event.source),
    confidence: asNumber(event.confidence) ?? null,
    reason: asString(event.reason),
  };
}

function reasonLabel(reason: string): string {
  return reason.replaceAll('_', ' ').replace(/\b\w/g, (letter) => letter.toUpperCase());
}

function mapContext(snapshot: JsonMap, ambient: VolumeSample | null): ContextSnapshot | null {
  const score = asNumber(snapshot.score);
  if (score === undefined) return null;
  const status = asMap(snapshot.contextStatus);
  const reasons = asStringArray(snapshot.reasonCodes).map(reasonLabel);
  return {
    at: asNumber(snapshot.capturedAtMs) ?? Date.now(),
    place: 'unknown',
    activity: 'unknown',
    event: snapshot.calendarBusy === true ? 'Calendar event' : null,
    appCategory: asString(snapshot.foregroundPackage) ?? null,
    noiseDb: ambient?.db ?? null,
    environment: ambient?.environment ?? null,
    interruptibility: Math.max(0, Math.min(100, score)),
    reasons: reasons.length > 0 ? reasons : asStringArray(status.captureWarnings).map(reasonLabel),
  };
}

function historyRecords(value: unknown, key: string): JsonMap[] {
  const map = asMap(value);
  const records = map[key];
  return Array.isArray(records) ? records.map(asMap) : [];
}

function mapFocusEvents(records: JsonMap[]): import('./types').FocusEvent[] {
  return records
    .map((record, index) => {
      const applied = record.applied === true;
      const enabled = record.enabled === true;
      const restored = record.restored === true;
      const action: import('./types').FocusEvent['action'] = !applied
        ? 'held'
        : restored || !enabled
          ? 'dnd_off'
          : 'dnd_on';
      return {
        id: asString(record.id) ?? `native-dnd-${index}`,
        at: asNumber(record.atMs) ?? Date.now(),
        action,
        reason: asString(record.reason) ?? 'Native DND state changed',
      };
    })
    .sort((a, b) => b.at - a.at)
    .slice(0, 100);
}

function upsertById<T extends { id: string }>(items: T[], item: T): T[] {
  const index = items.findIndex((existing) => existing.id === item.id);
  if (index < 0) return [item, ...items].slice(0, 500);
  const copy = items.slice();
  copy[index] = { ...copy[index], ...item };
  return copy;
}

function appendVolumeSample(items: VolumeSample[], sample: VolumeSample): VolumeSample[] {
  const next = items.filter((item) => item.at !== sample.at);
  return [...next, sample].sort((a, b) => a.at - b.at).slice(-240);
}

function applyNativeState(
  snapshot: JsonMap,
  interruptibility: JsonMap,
  records: NotificationRankedEvent[],
  ambientHistory: JsonMap[],
  dndHistory: JsonMap[] | null,
  attentionHistory: JsonMap[] | null,
) {
  const ambientMap = asMap(snapshot.ambient);
  const latestAmbient = asNumber(ambientMap.lastDb) === undefined
    ? null
    : {
        at: Date.now(),
        db: asNumber(ambientMap.lastDb) ?? 0,
        environment: normalizeEnvironment(ambientMap.environment),
        ringVolume: normalizeRingVolume(ambientMap.ringVolume),
        source: asString(ambientMap.source),
        confidence: asNumber(ambientMap.confidence) ?? null,
        reason: asString(ambientMap.lastReason),
      };
  setState((state) => {
    const permissions = mapPermissionTruth(snapshot, state.permissions);
    const focus = mapFocusStatus(snapshot.focus);
    const enabled = Object.fromEntries(FEATURE_KEYS.map((key) => [key,
      key === 'focus' && focus ? focus.enabled : state.features[key].enabled])) as Record<FeatureKey, boolean>;
    const notifications = records.map(mapNotification).reduce(upsertById, state.notifications);
    const context = mapContext(
      { ...interruptibility, ...asMap(snapshot) },
      latestAmbient ?? state.volume[state.volume.length - 1] ?? null,
    );
    const nativeAmbient = ambientHistory
      .map((record) => mapAmbientSample({
        atMs: asNumber(record.atMs),
        db: asNumber(record.db) ?? NaN,
        applied: record.applied === true,
        targetVolume: asNumber(record.targetVolume) ?? 0,
        reason: asString(record.reason) ?? '',
        ringVolume: asNumber(record.ringVolume),
        environment: asString(record.environment),
        source: asString(record.source),
        confidence: asNumber(record.confidence),
      }))
      .filter((sample): sample is VolumeSample => sample !== null);
    return {
      permissions,
      features: deriveFeatureStatuses({ ...snapshot, ...interruptibility }, permissions, enabled),
      context,
      notifications,
      keyboard: (() => {
        const keyboard = asMap(snapshot.keyboard);
        if (Object.keys(keyboard).length === 0) return state.keyboard;
        const federated = asMap(keyboard.federated);
        return {
          wordsLearned: asNumber(keyboard.wordsLearned) ?? 0,
          correctionsAvoided: asNumber(keyboard.correctionsAvoided) ?? 0,
          keystrokesSaved: asNumber(keyboard.keystrokesSaved) ?? 0,
          languageMix: { english: 0, hinglish: 0, other: 0 },
          federated: {
            joined: asBoolean(federated.joined) ?? false,
            rounds: asNumber(federated.rounds) ?? 0,
            modelVersion: asString(federated.modelVersion) ?? null,
            lastSync: asNumber(federated.lastSync) ?? null,
            epsilon: asNumber(federated.epsilon) ?? null,
          },
        };
      })(),
      dndActive: [2, 3, 4].includes(asNumber(snapshot.dndFilter) ?? -1),
      focus,
      focusLog: dndHistory === null ? state.focusLog : mapFocusEvents(dndHistory),
      attention: attentionHistory === null ? state.attention : mapAttentionHistory(attentionHistory),
      attentionLive: mapAttentionLive(asMap(snapshot.attention)),
      volume: nativeAmbient.length > 0
        ? nativeAmbient.reduce(appendVolumeSample, state.volume)
        : latestAmbient
          ? appendVolumeSample(state.volume, latestAmbient)
          : state.volume,
    };
  });
}

export function startNativeHydration(): () => void {
  const native = getAttuneNative();
  if (!native) return () => {};

  let disposed = false;
  let refreshing = false;
  let lastAttentionRefresh = 0;
  let lastAttentionRecoveryAttempt = 0;
  const subscriptions: NativeSubscription[] = [];
  const liveExpiry = setInterval(() => {
    const live = getState().attentionLive;
    if (live && Date.now() - live.observedAt > 3_000) setState({ attentionLive: null });
  }, 1_000);
  const refresh = async () => {
    if (disposed || refreshing) return;
    refreshing = true;
    const [statusResult, contextResult, recordsResult, ambientResult, dndResult, attentionResult] = await Promise.allSettled([
      native.getStatusSnapshot(),
      native.getInterruptibility(),
      native.getNotificationRecords(100),
      native.getAmbientHistory(240),
      native.getHistory('dnd', 100),
      native.getHistory('attention', 90),
    ]);
    refreshing = false;
    if (disposed) return;
    const status = statusResult.status === 'fulfilled' ? asMap(statusResult.value) : {};
    const context = contextResult.status === 'fulfilled' ? asMap(contextResult.value) : {};
    const records = recordsResult.status === 'fulfilled' ? recordsResult.value : [];
    const ambientHistory = ambientResult.status === 'fulfilled' ? ambientResult.value : [];
    const dndHistory = dndResult.status === 'fulfilled' ? historyRecords(dndResult.value, 'records') : null;
    const attentionHistory = attentionResult.status === 'fulfilled' ? historyRecords(attentionResult.value, 'records') : null;
    applyNativeState(status, context, records, ambientHistory, dndHistory, attentionHistory);
    const attention = asMap(status.attentionSensors);
    const camera = asMap(status.camera);
    if (
      getState().features.attention.enabled &&
      attention.running !== true &&
      camera.running !== true &&
      Date.now() - lastAttentionRecoveryAttempt >= 30_000
    ) {
      lastAttentionRecoveryAttempt = Date.now();
      await native.startService('attention').catch(() => false);
    }
  };

  subscriptions.push(native.addListener('onNotificationRanked', (event) => {
    const notification = mapNotification(event);
    setState((state) => ({ notifications: upsertById(state.notifications, notification) }));
  }));
  subscriptions.push(native.addListener('onAmbientSample', (event) => {
    const sample = mapAmbientSample(event);
    if (!sample) return;
    setState((state) => ({ volume: appendVolumeSample(state.volume, sample) }));
  }));
  subscriptions.push(native.addListener('onNativeStatusChanged', () => {
    void refresh();
  }));
  subscriptions.push(native.addListener('onAttentionFrame', (event) => {
    setState({ attentionLive: mapAttentionLive(event) });
    if (Date.now() - lastAttentionRefresh >= 15_000) {
      lastAttentionRefresh = Date.now();
      void refresh();
    }
  }));

  const appStateListener = AppState.addEventListener('change', (next: AppStateStatus) => {
    if (next === 'active') void refresh();
  });
  void refresh();

  return () => {
    disposed = true;
    clearInterval(liveExpiry);
    subscriptions.forEach((subscription) => subscription.remove());
    appStateListener.remove();
  };
}
