# Personal gaze calibration

## Purpose and limits

This is a user-labelled, on-device setup check for the camera gaze path. It is
not the planned camera-to-sensor hybrid model and does not validate sensor-based
attention. A passing setup check is not an independently measured accuracy score.

The fixed baseline can mistake looking down toward a phone for looking away:
the confirmed diagnostic reading trial had near-centred iris coordinates but
strong down-looking blend coefficients. Blendshapes are facial-expression
coefficients, not calibrated gaze probabilities. They are derived from the same
landmarks, so agreement is correlated evidence.

## Explicit collection protocol

1. Enable True Screen Time with camera access. Keep this page visible, with one
   face, even lighting, and the phone in a comfortable, stable reading position.
2. Follow five screen targets: centre, top-left, top-right, bottom-left,
   bottom-right. Each step lasts ten seconds; the first two seconds are excluded.
   Blinking normally is expected.
3. Confirm after each step whether the instruction was followed for its entirety.
   Cancelling or rejecting a step discards the candidate check.
4. Fit the profile from those five confirmed screen targets only.
5. Freeze that profile. Test fresh natural reading, then eye-only off-screen
   looks left, right and below the phone. Keep the head facing the phone. Each
   test uses the same ten-second/two-second-settling window and requires separate
   participant confirmation.
6. Save only if every validation trial passes. Failed validation cannot refit or
   widen the profile. Restarting requires collecting all five targets again.

Each step requires at least eight observations, six quality-accepted eye
observations, and 55% usable observations. Observations must reach within one
second of both scored-window edges, with no gap over two seconds. Rejected
captures invalidate collection instead of disappearing from the denominator.
Confirmation waits for every outstanding capture requested before the end of
the window. Late results are scored by their original capture time; capture
failure or a drain exceeding the two-second freshness limit fails the step.
Each separate validation trial must
produce at least 65% correct predictions and at most 10% incorrect predictions;
unknown predictions remain in the denominator. These are predefined setup
criteria on observations, not duration-weighted accuracy metrics.

## Decision boundaries

- Subject-left and subject-right identities match their blendshape scores.
- Both iris and blend axes use image-right-positive and image-down-positive.
- Vertical iris position uses the eye-corner midpoint and eye width, separately
  from eyelid aperture. Squinting cannot move the geometric origin or denominator.
- Robust per-target summaries define capped per-eye screen envelopes. Individual
  eye offsets preserve normal vergence rather than assuming identical coordinates.
- Looking requires both eyes and both channels inside the screen envelope.
- Calibrated binocular and iris-versus-blend difference ranges prevent opposed
  eye movements inside those individual envelopes from becoming a looking claim.
- Away requires both eyes and both channels beyond the envelope in the same
  direction. Channel disagreement and boundary cases abstain.
- Head position, face size, face location and roll restrict applicability.
  A different camera, capture geometry or rotation cannot reuse a profile.
- Blinks, missing eyes/faces, multiple faces, stale captures and contradictory
  temporal evidence continue to withdraw a gaze claim.

## Privacy and lifecycle

Collection uses existing duty-cycled on-device CPU inference. No extra camera
stream or network inference is introduced. At most 24 derived observations per
target are temporarily retained in memory. Samples are cleared on failure,
cancellation or completion; ordinary predictions never train the profile.

Only bounded centres/radii, camera geometry binding, classifier version and
creation time are saved to app-private `noBackupFilesDir` using an atomic write.
Profiles expire after 30 days and are invalidated by classifier-version changes.
Delete through Personal gaze check or the app's attention/all-data deletion.

Leaving the page, losing window focus (including the notification shade),
backgrounding the activity, a camera-session change, screen
lock, or a missing three-second UI heartbeat interrupts collection. Calibration
time is unknown attention, never counted as normal attended/away usage. Profile
revision changes invalidate in-flight old-profile results.

Protected Android service dumps expose ephemeral reduced ratios and validation
counts for diagnosis. Ratios are excluded from ordinary app events and cleared
when evidence is stopped, rejected or no longer fresh. No images or face meshes
are exported.

## Required accuracy evaluation after setup

Use `README.md` and `evaluate.py` on separate independently observed trials, with
the profile frozen and no trial reuse for tuning. Include natural reading across
the whole screen, eye-only looks in every direction, blinks, glasses, lighting,
distance and posture changes. Report false-away, false-looking, unknown duration,
coverage and latency. Keep the failing `gaze-heuristic-2` baseline in
`VALIDATION.md`. Additional phones and sustained battery/thermal tests remain
necessary before claiming general accuracy.
