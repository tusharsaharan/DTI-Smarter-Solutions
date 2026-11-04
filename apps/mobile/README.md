# Attune Android app

Attune is an Android-first Expo development build. Expo Go is not sufficient because the app
contains a local Kotlin module with a notification listener, foreground camera/microphone
services, a DND controller, and a real `InputMethodService` keyboard.

## Local development

After cloning, fetch the bundled notification encoder from the repository root:

```powershell
git lfs install
git lfs pull
```

From `apps/mobile`:

```powershell
npm install
npm run typecheck
npm test
npm run lint
npx expo prebuild --clean --platform android --no-install
npm run android
```

The first native build requires Android Studio/SDK, a configured `ANDROID_HOME`, Java, Gradle
wrapper access, a Java 17 JDK, and either a running emulator or an Android phone with USB debugging enabled.

## Native module

The local module is at `modules/attune-native` and is intentionally Android-only. Verify that it
is discovered before a build:

```powershell
npx expo-modules-autolinking resolve --platform android --json modules
```

The output must contain `@attune/native` and
`app.attune.attunenative.AttuneNativeModule`.

## Real-device setup

After installing the development build, grant only the capabilities you want to test through the
Attune onboarding/You screen:

- Notification access and Post notifications for Priority Inbox
- Notification policy access for Smart Focus
- Usage access for daily foreground-time totals and attention accounting
- Microphone for Ambient Volume
- Camera optionally for on-device iris, eyelid and head-direction checks (MediaPipe model bundled)
- Enable Attune in Android keyboard settings for the real system IME

All denied permissions must leave an honest reduced or unavailable state. Do not enable the
development design preview when collecting professor-facing evidence.

True Screen Time starts a separate motion foreground service even when camera access is denied.
It uses the accelerometer and available gyroscope/proximity sensor, plus fresh Android interaction
events. Sensor estimates cannot measure gaze; stillness or movement alone never means attention.
Both paths suspend sensing while the screen is off or locked. Gaze tracking withdraws stale,
occluded or ambiguous readings instead of retaining the previous looking/away result.
Daily totals use local midnight and keep unobserved foreground time unclassified. Sampled attention
is buffered for up to 15 seconds; abrupt process termination can lose that final buffer. Per-app
attention breakdowns, hourly attention charts and personal hybrid calibration remain unimplemented.

Physical acceptance checks for each rebuilt APK:

- Read the top/bottom/edges of the screen, move only the eyes away and back, turn/tilt the head,
  blink, wear glasses, and repeat in dim light; compare labels with hand-labelled timestamps.
- Deny/revoke camera access, enable True Screen Time, and confirm a sensor estimate appears.
  Try face-down, covered proximity, handheld reading, still video and walking; motion is never gaze.
- Close the UI, reopen after a minute, lock/unlock, rotate, and interrupt camera access with another
  app. Confirm no stale looking claim or attention accumulation through screen-off/service gaps.
- Compare a full local day's baseline with Android usage, including app switches and midnight.
  Android/OEM usage-event omissions can require a lower-fidelity aggregate baseline.

## Notification ranking

Priority Inbox reads the newest MessagingStyle message separately from a bounded preceding thread.
Group titles, old urgent messages, missing senders and expired response feedback cannot inflate it.
College-focused rules recognize asserted upcoming exams, viva, classes, deadline and venue/time
changes. A confirmed cancellation today can itself be important; old papers, memes, rumours,
questions and undated ambiguous schedule updates do not receive the same interruption priority.
An offline multilingual encoder and a small CPU-trained intent head supplement clause-level
checks for current requests, negation, cancellation, recovery, marketing and quoted/conditional text.
The model cannot invent an urgent/safety action unsupported by the current-message safeguards.
Uncertainty, disagreement, truncated input, processing backlog, model failure and denied permissions
preserve the original notification. Inference runs on one bounded worker; actions are checked against
newer notification updates before applying. No message is sent to a GPU, server or model API.

Model generation and limits are documented in `../../models/notification/README.md`. A fresh
checkout includes the trained head and requires Git LFS to fetch the encoder before building an APK.
To regenerate the assets, prepare the public encoder first, then train/export the small head locally.
For a completely offline Gradle check, stage the public ONNX Android dependency once:

```powershell
python ../../models/notification/stage_android_runtime.py
./android/gradlew.bat -p android :attune-native:testDebugUnitTest --offline
```

Priority Inbox must be explicitly enabled. A native rebuild is required for the new model/runtime.
Validate with real notifications on a connected Android phone before claiming delivery accuracy:
cancelled deadlines, updated room/time, negated urgency, Hinglish requests, closed app, denied access,
disabled posting channels, reply/open-original actions, bursts and notification edits/removals.
The small intent head has limited synthetic validation and does not guarantee perfect understanding.

## Smart Focus and timetable learning

Rebuild the Android app after native changes. Focus accepts photos/screenshots, PDF, UTF-8 CSV
(`day,start,end,title`, optionally `startdate,enddate`) and `.ics` calendar files. OCR is bundled
and runs locally; fuzzy/merged cells are omitted with warnings. Calendar imports preserve date
bounds and supported weekly occurrences, exceptions and timezone conversion. Unsupported or
unbounded recurrence rules, embedded VTIMEZONE, edited recurrence overrides and all-day events
are rejected rather than silently reinterpreted.

Review every extracted class before saving. Choose inclusive term dates covering no more than
180 days and containing every dated calendar entry. Import limits are 200 slots, 25 MiB/file,
512 KiB/text, six PDF pages and three megapixels per OCR image. Importing never enables DND.

Enable **Predict busy moments** to allow a native **60-second warning with Skip**. Allow both
DND access and notifications, including the Smart Focus warnings channel. Manual DND starts
immediately only when you explicitly choose it. Local CPU gradient boosting learns from reviewed
timetable expectations and explicit On/Off/Skip choices, never its own predictions. Derived samples
expire after 45 days; old timetable priors are removed when the term expires. Estimated confidence
does not prove attendance or busyness.

Kotlin confirms actual system notification presence before starting the full 60-second countdown;
Skip is available during warning preparation too. It rechecks current evidence, rule revision,
warning visibility and monotonic time at the policy/filter write boundaries. A deadline more than
15 seconds late is dropped. Countdown replay is blocked across process death/reboot and clock
changes; force-stop disables alarms until reopening. Inexact Android
alarms/Doze can delay warnings and restoration. Attune preserves pre-existing DND, unrelated
snapshots and user overrides; automatic timetable sessions never end a manual session. New
snapshots are tied to session UUIDs, and exception updates persist a recovery transaction before
writing. Failed exception edits retain the manual session: clean rollback leaves it active,
and uncertain writes recover the requested edit rather than ending DND. A cleanly failed repair
uses its verified starting policy, not a stale pre-failure policy. Exact captured write baselines are
checked against ownership, so a newly observed external filter/policy is not adopted. Unavailable
policy/filter reads and UNKNOWN filter broadcasts defer writes without being treated as a user override.
An Off intent retains its original authorization phase/policy until a policy write is actually attempted;
merely entering recovery does not authorize restoring through an external manual change. After an
attempted restoration, recovery retains the verified pre-write policy even when Android throws without
changing it. Explicit Off still attempts owned restoration
if saving its learning sample fails; a failed restoration-intent save retains a retry plan in the
current process, not a promise of recovery across process death. New acquisition/exception writes
require durable preparation and reject preparation older than 30 seconds. On older global-DND APIs,
up to 64 outstanding write receipts are retained across transactions, including events captured within
their deadline but still queued. Attribution uses the event's capture time: 30 seconds after settlement,
bounded to 120 seconds from preparation; retaining a receipt never extends that window. Broadcasts may
still be delayed or coalesced. After a process-death
observation gap, Attune relinquishes ownership in every phase without changing DND; manage that state
in Android settings. Android does not provide an atomic compare-and-set DND operation.

Timetable/model/choices stay encrypted in private no-backup storage. **Delete Focus data**
disables automatic Focus, cancels pending warnings and restores owned automatic DND; manual DND
remains unchanged. Deletion reports a failure if storage or automatic restoration cannot finish,
rather than claiming success. Raw imports
are discarded. Google's OCR SDK may send usage/performance metrics, not timetable content.
Activity transitions/geofences, call-response training, Quick Settings tiles and widgets are not
implemented and must not be presented as sensed signals or completed capabilities.

Physical Smart Focus acceptance checks:

- Import an actual timetable photo, screenshot, PDF, CSV and supported ICS. Compare every extracted
  day/time/date with the original, edit a candidate, discard an import and verify saving is explicit.
- Start a reviewed current class with more than two minutes remaining. Confirm a real notification
  counts down for 60 seconds, then DND activates and restores at the class end. Record screenshots
  of the warning, Android DND state and Focus status using `mobile-mcp` when available.
- Skip from both the app and notification, including during import. Verify no DND and no repeat warning
  for that occurrence. Toggle automation off, replace/delete the timetable, revoke DND/posting/channel
  access, dismiss the warning and change evidence during countdown; automatic activation must cancel.
- Let a future calendar event approach: it must not be busy now. Exclude all-day/free/cancelled/
  declined events. Pause/stop/lock the foreground app and verify the old app signal disappears.
- Kill the process, reboot, change wall time/timezone and delay alarms with Doze. Old countdowns
  must never apply; reviewed timezone remains fixed until resaving. Verify term expiry removes priors.
- Start DND in Android first, change DND yourself during an Attune session, and start manual Attune
  DND before changing timetable rules. Preserve external/user changes and manual-session ownership.
- Tap the warning to open Smart Focus. Skip during warning preparation and confirm the countdown
  starts only after notification presence. Interrupt an exception update and verify recovery is
  shown without a false success. On global-DND devices, test external Off/On transitions, delayed
  broadcasts and process death: observation gaps must preserve Android DND, not restore blindly.
- Deny or fail feedback storage during explicit Off: owned DND restoration must still be attempted
  and the unsaved learning choice disclosed. Reject an exception update before writing, after policy
  mutation, and after a clean rollback: manual DND must not be ended by update recovery. Change
  Android exceptions during prepared/restoring recovery; unrecognised policies must be preserved.
- Deny a restoration-intent save and make the first Off attempt fail; the current process must
  retry Off rather than repairing an earlier exception edit. Fail policy/filter reads temporarily;
  recovery ownership must remain, with no writes until evidence becomes available. Delay preparation
  past 30 seconds and verify no new DND write; delay attempted-write broadcasts through settlement
  and verify receipt retention without hiding unmatched external changes.
- While a no-write Off attempt is deferred, select the original exceptions yourself in Android;
  retry must preserve that manual DND rather than treating it as an already-restored policy. Capture
  an expected broadcast within its receipt deadline, queue it across a later transaction, and verify
  capture-time matching. UNKNOWN broadcasts must retain ownership without authorizing writes.
- From a partially completed manual exception edit, make the first Off policy write throw without
  changing Android. Verify the observed pre-write policy is retained and a later Off can restore it,
  rather than abandoning the session as an external override.
- Delete learning and reopen. Verify zero timetable/model/feedback rows and automation off. Verify
  denied/unreadable encrypted storage blocks new automatic DND and reports restoration failures.

## Checks

```powershell
npm run typecheck
npm test -- --run
npm run lint
```

Kotlin/JUnit checks run through the generated Android project's Gradle wrapper when Gradle and the
Android SDK are available:

```powershell
.\android\gradlew.bat -p android :attune-native:testDebugUnitTest
```

The Android implementation is not exercised by Expo Go; native changes require rebuilding the
development APK.
