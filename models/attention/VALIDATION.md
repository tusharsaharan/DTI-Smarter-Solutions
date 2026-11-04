# Camera attention engineering validation

Date: 2026-11-04. Owner: Tushar Saharan. Baseline: `gaze-heuristic-2`.
Current source: `gaze-heuristic-3` with optional personal calibration.

## Implemented boundaries

- Contradictory, missing or invalid evidence withdraws the stable gaze label.
  Two fresh consistent frames are required to establish it again.
- Camera2 capture and image timestamps must match. The realtime sensor clock is
  used only when declared; otherwise request time is a conservative capture bound.
- Evidence expires after two seconds including delivery/inference latency.
  Old, reordered, rotation-changed and stopped-session results cannot renew it.
- A publication gate serializes configuration, inference, rejection, expiry and
  failure with stopping. Android resources remain confined to the camera lock.
- YUV preprocessing validates row/pixel strides, buffer bounds, crop geometry and
  rotations; raw images remain in memory only.
- Protected service dumps expose derived timing, rejection and latency diagnostics.
  Slow rejected inference reports its own latency. Non-inference states clear it.
- The offline evaluator separates incorrect predictions from unknown/uncovered
  duration and never backdates delayed evidence.

## Baseline automated verification

- Full Android native unit suite: **356 passed**, 34 suites, no skips/failures/errors.
- Mobile suite: **75 passed**; lint and TypeScript checks passed.
- Offline evaluator: **23 passed** using Python standard-library unittest.
- Authored-emoji checks passed. These automated tests are not gaze-accuracy data.

Android verification uses JDK 17 and the real native Gradle module. The standalone
ARM64 release variant is minified and signed with the existing development
certificate, matching the installed app and allowing data-preserving updates.

## Baseline physical-device findings

Device model: `24094RAD4I`; Android 16 / API 36.

- Updated APK installed successfully with existing app data retained.
- With camera permission denied, the sensor foreground service ran and the UI
  showed **Attention uncertain** and **Sensor estimate - cannot measure gaze**.
- Camera permission was granted by the participant in Android settings.
- The first gaze trial could not start: release shrinking removed protobuf
  `SystemInfo.platform_`. After the documented protobuf/JNI rules were applied,
  startup reached a second failure: Flogger's stack-based caller lookup.
  The module now includes the version-matched MediaPipe JNI rules, documented
  generated-protobuf retention, and Flogger retention. The rebuilt minified APK
  successfully initialized MediaPipe and processed live camera frames. See `CREDITS.md`.
- Camera capture continued with the app UI backgrounded: accepted-frame counter
  advanced from 542 to 549 in the six-second check.
- After Android reported the screen dozing/locked, camera status was stopped,
  label `UNKNOWN`, and observation/completion/expiry/latency fields were cleared.
  An in-flight frame was rejected as `screen_off_or_locked`. The immediate
  pre-broadcast snapshot was still running; instantaneous screen-off delivery is
  not claimed.
- Unlocking resumed real capture in session 2 with fresh timestamps and a reset
  frame counter. Turning True Screen Time off stopped both attention services.
  The test session was left off; camera permission remains as granted by the user.

### Guided gaze smoke test: accuracy failed

Two 20-second trials were followed by participant confirmation of actual
behavior. The first three seconds of each were excluded as declared before
collection. Normal blinks were allowed. These are self-labelled, single-person
smoke tests, without an independent observer or held-out evaluation split.
Arrival times are conservative device-clock polling times; maximum polling
interval was 284 ms. No accepted-frame counter gaps were observed, but polling
can still miss intervening status changes. No thresholds were tuned to the trials.

| Confirmed behavior | Scored duration | Predicted looking | Predicted away | Unknown |
|---|---:|---:|---:|---:|
| Reading the phone screen | 16.754 s | 0 s | 9.568 s | 7.186 s |
| Looking beside the phone | 16.730 s | 0 s | 4.012 s | 12.718 s |

The uncalibrated classifier failed to recognize screen reading. It must not be
treated as accurate screen-time measurement. Across these observations, known
coverage was 40.6%, and correct duration over all labelled time was 12.0%.
Zero false-looking duration is not evidence of success: it never predicted
looking in either trial. The main false-away reason was `eyes_looking_away`.

Inference latency across the 39 arrivals inside labelled windows: median **131 ms**,
p95 **175.7 ms**. The device declared `sensor_realtime`; processed rotation was
270 degrees. No capture/inference timing rejection or camera runtime error
occurred during either trial. These measurements do not establish power or
sustained thermal behavior.

Derived inputs, the evaluator report, startup-failure logs and feature screenshots
are retained locally in the session's temporary `attune-gaze-device` directory.
No camera frames or landmarks were exported.

Reproducibility hashes:

```text
APK:       05e3194f6c84a4c3f3d8ad19cb9d87ab541241ffe2f6bbd9d966e40ad8e47e31
Input:     6030f79c0fa5143f7a26231f045d16d9da4a8b4e002bc31233c9d2412df6c26b
Evaluator: f71d0e3b46ff1b866ca5126c8b574af141e0cd834de6d0476cd9218fd6e3bdf7
```

## Model provenance and outstanding validation

Bundled and APK-extracted Face Landmarker float16 v1 SHA-256:

```text
64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff
```

Runtime: MediaPipe Tasks Vision `0.10.26`, on-device CPU. No workstation GPU was
available in this environment; no GPU benchmark or training was performed.

Physical verification of personal gaze calibration, a held-out multi-position
and multi-device accuracy evaluation, sustained power/thermal measurement, and
complete camera contention/permission-revocation/lifecycle checks remain
outstanding. Camera-to-sensor hybrid calibration remains planned. Usage accounting,
fallback modelling and per-app/hourly reporting are Rakesh's scope.

Next Tushar task: validate the corrected coordinates and personal calibration
with independently checked positive and negative trials. Preserve this failing
baseline; widening fixed thresholds until this participant passes would not
establish general accuracy.

Integration observations for Rakesh: Usage access remained off, so accounting
was not validated. The UI displayed Full mode despite missing Usage access; an
enabled preference also did not automatically restart services after an APK
replacement/cold launch. These were not changed by the camera work.

## Follow-up accuracy diagnosis and correction

The participant confirmed another continuous reading trial with normal blinks.
An earlier attempt detected no face and is excluded from reading-accuracy claims.
The diagnostic update retained `gaze-heuristic-2`; it only exposed ephemeral,
permission-protected derived ratios.

Across 25 distinct arrivals in the confirmed diagnostic reading capture (including
its initial settling period), iris x medians were -0.068 / 0.074 and the old
aperture-normalized iris y medians were -0.093 / -0.044. The old up-positive
blendshape vertical median was -0.711. Thus strong downward blendshape output
could independently trigger false-away while iris geometry stayed near centre.
Observed reasons: 9 head/eye conflicts, 7 closed/occluded, 6 away, 2 transitions,
and 1 multiple-face result. This diagnoses a failure mechanism; it is not a
held-out accuracy benchmark.

`gaze-heuristic-3` corrects subject-left/right pairing, aligns blend/iris direction
signs, separates vertical iris coordinates from eyelid aperture, and retains
bilateral channel evidence rather than averaging contradictory eyes. A single
channel can no longer force an away label. Personal calibration uses explicit
screen targets followed by separate frozen-profile positive/negative checks.
See `PERSONAL_CALIBRATION.md` for the predeclared protocol and storage contract.

The failing baseline above remains the reference. General accuracy improvement
requires new physical-device observations after calibration; synthetic unit
tests establish boundary behavior, not gaze accuracy.

## Calibration release verification

- Final native suite: **387 passed**, 36 suites, no failures/errors/skips. Includes
  13 calibration-session and 8 personal-profile tests.
- Final minified standalone ARM64 release build succeeded in 6m36s, using JDK 17:
  `:attune-native:testDebugUnitTest :app:assembleRelease -PreactNativeArchitectures=arm64-v8a --offline --max-workers=2`.
- APK Signature Scheme v2 verified with the existing development certificate,
  matching the previously installed app. The package is `app.attune.phone`,
  version `0.1.0` / code `1`, minimum Android API 24 and target API 36.
- APK inspection confirmed the embedded application bundle contains the
  finalization UI, the native code contains `gaze-heuristic-3`, and the bundled
  model hash matches the pinned Face Landmarker artifact above.
- Capture registration and confirmation share the runtime lock. A step cannot
  enter review until any request that could expose within its window is scored
  or rejected. Camera failures and a drain timeout fail the step.
- Regression coverage includes a late unknown changing 8/12 correct observations
  (a provisional pass) into 8/13 (failure), rejection during finalization,
  missing-result timeout, capture-time window boundaries, and a UI heartbeat
  overtaking inference completion. Independent scoped review found no blockers.
- Mobile unit tests: **75 passed**. Lint, TypeScript and authored-emoji checks passed.
- The offline evaluator's **23 tests passed** during this change; its source has
  not changed since that run. No new model training or GPU evaluation occurred.
- The physical phone was disconnected when the calibration build was prepared.
  The participant requested the APK first and phone trials later. This release
  has not yet been installed or physically validated. Guided setup, actual UI
  screenshots, cancellation/shade/background interruption, saved-profile deletion,
  and separate frozen-profile accuracy trials remain pending.

Deliverable: `Attune-gaze-calibration.apk` at the repository root (165,475,715 bytes),
copied from `apps/mobile/android/app/build/outputs/apk/release/app-release.apk`.

```text
APK SHA-256: a37208e0cdd029a607596bd14017d6e8a124019553d01a333b090685f3209171
Certificate: fac61745dc0903786fb9ede62a962b399f7348f0bb6f899b8332667591033b9c
```

When the phone is available: install the signed APK, open True Screen Time,
enable it and camera access, then choose **Personal gaze check → Calibrate my gaze**.
Follow all nine prompts and confirm only steps actually completed. Keep the
resulting profile frozen for fresh reading and off-screen trials; record failures
and unknown duration as well as correct predictions.
