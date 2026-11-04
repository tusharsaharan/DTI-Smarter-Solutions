# Tushar: offline camera attention evaluation

Duration-weighted evaluation of **derived camera classifications** against
independently labelled intervals. Standard-library Python 3.10+ only; no network,
GPU, model execution/download, or third-party dependencies. This tooling does not
change Android behavior and does not measure the sensor-only fallback.

**No validated physical-device accuracy is claimed.** The unit tests are
hand-computed synthetic cases testing the evaluator, not the camera classifier.
See [VALIDATION.md](VALIDATION.md) for the failing participant-confirmed device
smoke test and runtime verification. Supplying device metadata does not establish
that an input was actually collected on that device.

## Run

From this directory:

```powershell
python evaluate.py INPUT_JSON --output OUTPUT_JSON
python -B -m unittest discover -s . -p test_evaluate.py -v
```

Or from the repository root:

```powershell
python models/attention/evaluate.py INPUT_JSON --output OUTPUT_JSON
python -B -m unittest discover -s models/attention -p test_evaluate.py -v
```

Input/output are local files; the output parent directory must already exist.
The CLI exits 2 on validation or file errors and validates before writing output.
Input and output must differ. A successful run replaces the specified output.
Keep collected data and reports in a private local working directory, not Git.

## Strict input contract (version 1)

Exactly these three top-level fields are required:

```text
{
  "metadata": {
    "device": string,
    "android_version": string,
    "model_sha256": string,
    "classifier_version": string,
    "split": string,
    "label_method": string
  },
  "observations": [{
    "at_elapsed_ms": integer,
    "observed_elapsed_ms": integer,
    "valid_until_elapsed_ms": integer,
    "label": "ATTENDED" | "PASSIVE" | "UNKNOWN",
    "inference_ms": number                         // optional; otherwise omit
  }],
  "truth": [{
    "start_elapsed_ms": integer,
    "end_elapsed_ms": integer,
    "label": "ATTENDED" | "PASSIVE",
    "condition": string
  }]
}
```

This is a schema illustration, not a JSON fixture or a device report.

- UTF-8 JSON only. No comments, duplicate keys, trailing data, unknown fields,
  `null` substitutes, NaN, or Infinity. Unknown fields are rejected at every level.
- All timestamps are **integer milliseconds on the same Android monotonic
  elapsed-realtime clock and within one boot/session**. Use the native source's
  elapsed timestamp, not wall time, JS receipt time, or unconverted camera ticks.
  Booleans and floating-point timestamps (even `10.0`) are rejected.
- All numeric values must be finite and within `[0, 9007199254740991]`, inclusive,
  preserving exact timestamp interchange with JavaScript. `inference_ms` may be
  fractional and is measured inference latency, not a replacement for capture or
  arrival timestamps. Omit unavailable latency; do not invent zero.
- Every metadata value and `condition` must be a nonblank string. Use nonpersonal
  hardware/build identifiers; no device serial, participant name, app text, or
  message content. `android_version` is a string. `classifier_version` identifies
  the exact classifier revision/configuration (including thresholds/expiry).
- `model_sha256` is exactly 64 hexadecimal characters, case-insensitive, without
  a prefix. Hash the actual local camera model artifact; the tool checks the form
  but cannot verify that an unseen model was used. If a model is unavailable,
  collect its digest before running; do not substitute a fake digest.
- `split` records the fixed evaluation cohort/run and held-out or calibration
  split. `label_method` records the independent-label protocol revision and clock
  synchronization/transition exclusion method. All metadata is preserved verbatim.
- Observations must already be in **strictly increasing arrival order**, with no
  duplicate `at_elapsed_ms`; they are not silently reordered. Truth intervals
  may arrive unordered and are sorted internally, but must have `start < end`
  and never overlap, even across conditions. Adjacent intervals are allowed.
- Empty arrays are valid and explicitly yield unscored/unknown results. Missing
  truth is never filled in. Ground truth `UNKNOWN` is not allowed: exclude genuinely
  ambiguous or unlabelled periods by leaving a gap, rather than calling them
  correct predictions or treating them as passive attention.

Only the above derived values are accepted. **Do not supply or export camera
images, video, landmarks, face/eye coordinates, raw sensor streams, screen content,
typed/message text, audio, or identifiers.** Metadata/condition strings describe
the collection setup only. Keep human observation independent of model outputs.

## Timing and stale evidence

All intervals are half-open `[start, end)`. An event can contribute a prediction
only over:

```text
[at_elapsed_ms, min(valid_until_elapsed_ms, next_at_elapsed_ms))
```

The last event ends at its expiry. Its capture time never backdates a prediction.
For example, capture at 0, arrival at 300, and expiry at 500 provide only 200 ms
of usable evidence; the pre-arrival 300 ms is not retroactively relabelled.

`valid_until_elapsed_ms` is the actual native evidence expiry, based on capture
freshness; exporting it as arrival plus a fresh lifetime would hide delayed
inference and is invalid collection practice. Expiry before capture is invalid
input. Expiry equal to capture is permitted but provides no usable interval.
There is no invented evaluator freshness threshold; the collector must export
the classifier's real bounded expiry, with policy identified by its version.

Timing exclusion rules, in priority order:

1. Capture after arrival: `future_observed`; it cannot become valid later.
2. Expiry at or before arrival: `expired_at_arrival`.
3. Capture at or before the greatest earlier non-future capture timestamp:
   `superseded_observed`. This discards out-of-order inference completions and
   replayed samples. Emit a sample's result once, not repeated UI snapshots. An
   expired non-future arrival also advances this capture high-water mark; a future
   capture does not, so it cannot poison later valid evidence.

Every arrival supersedes the prior result, **including** `UNKNOWN` or rejected
events. A rejected result cannot restore the previous prediction. Known evidence
never persists past expiry; initial gaps, expiry gaps, invalid-evidence periods,
and uncovered final time inside truth all count as predicted `UNKNOWN`.
Truth gaps are unscored, not filled. A still-valid evidence interval can intersect
multiple labelled windows; only the actual intersections contribute duration.

## Report and metric denominators

The report contains `schema_version`, evaluator version/source SHA-256,
`input_sha256` (SHA-256 of the **exact input bytes**, including whitespace), original
`metadata`, timing/latency `semantics`, `observation_diagnostics`, `overall`, and
`per_condition`. Output is deterministic for identical input and evaluator bytes.
Do not merge reports by averaging rates: sum duration cells and recompute them.

Each summary includes:

- `confusion_duration_ms`: rows are truth `ATTENDED`/`PASSIVE`; columns are
  predictions `ATTENDED`/`PASSIVE`/`UNKNOWN`. Durations, not event counts.
- `truth_duration_ms`, `known_duration_ms`, `unknown_duration_ms`,
  `correct_duration_ms`, and `incorrect_duration_ms` (wrong known predictions).
- `coverage`: known / total truth duration.
- `accuracy_over_all_truth`: correct / total truth duration. Unknown contributes
  zero correct time; **abstention cannot improve this accuracy**.
- `accuracy_when_known`: correct / known duration. This conditional diagnostic
  can look high at low coverage and must never be presented alone as accuracy.
- `false_attended.duration_ms`: passive truth predicted attended.
- `false_passive.duration_ms`: attended truth predicted passive.

Every rate has `{numerator_ms, denominator_ms, value}`; `value` is a fraction in
`[0, 1]` or **`null` for zero denominator**, never a fabricated zero/perfect score.
Conditional error denominators intentionally differ:

| Rate | Numerator | Denominator |
|---|---|---|
| `false_attended.rate_given_truth` | False-attended duration | All passive truth duration |
| `false_passive.rate_given_truth` | False-passive duration | All attended truth duration |
| `false_attended.error_rate_given_prediction` | False-attended duration | All predicted attended duration |
| `false_passive.error_rate_given_prediction` | False-passive duration | All predicted passive duration |

Truth-conditional denominators include unknown predictions. A low false rate
with high unknown time is not success: always inspect coverage, all-truth
accuracy, and both truth rows, including unknown duration for each. Unknown
attended time is not a false-passive prediction, but does reduce recorded
attended duration.

`attended_duration_error_ms` contains true and predicted attended durations,
`signed = predicted - truth` (positive means overcount), and `absolute = abs(signed)`.
This is **absolute net duration error**, not the sum of classification mistakes;
opposing mistakes can cancel. Overall absolute error is computed after pooling,
not by summing per-condition absolute errors. Unknown time is not imputed.

`inference` contains `count`, `p50_ms`, and `p95_ms` for supplied latencies whose
**arrival falls inside labelled truth**. Rejected-timing and `UNKNOWN` events
remain included to avoid hiding slow failures. Arrivals in truth gaps/outside
truth are excluded; an event with an earlier arrival may contribute duration but
not a latency sample. Conditions are assigned by arrival, not by capture or expiry.
Percentiles are sample-weighted, sorted linear interpolation at `(n - 1) * q`,
not duration-weighted. No samples gives `null` percentiles; one sample gives that
sample for both. Event diagnostics cover all input observations, with exclusive
timing-exclusion reason counts and an outside-truth arrival count.

## Independent ground-truth collection protocol

### Android derived diagnostics

With an authorized device connected, enable True Screen Time and grant camera
permission in Attune. The camera foreground service exposes a derived-only
snapshot through Android's permission-protected service dump:

```text
adb -s SERIAL shell dumpsys activity service app.attune.phone/app.attune.attunenative.attention.AttuneAttentionCameraService
```

If camera startup fails and that service stops, the same derived snapshot remains
available through `app.attune.phone/app.attune.attunenative.attention.AttuneAttentionSensorService`
while the attention fallback service is running. Model/camera initialization
exceptions are logged under `AttuneCamera` before any image is captured.

Read the single `ATTUNE_CAMERA_STATUS` JSON line. It contains the session ID,
accepted/rejected frame counters, elapsed observation/completion/expiry times,
clock basis, classification/reason, inference and capture-to-result latency,
rotation, classifier version and calibration status. No images or landmarks are
exported. Missing service output means unavailable evidence, not passive use.

Record each changed `(sessionId, framesObserved, framesRejected)` once. Preserve
stop/error transitions as `UNKNOWN`; stop clears sample timing and latency.
Pre-inference rejection clears latency; post-inference rejection reports that
attempt's measured latency, including expired inference. Never copy the previous
frame's latency into a new event. Separate different sessions/boots.

For a polled snapshot, use `atElapsedMs` as the conservative arrival time, with
`observationElapsedMs` and `validUntilElapsedMs` for capture/expiry. Do not backdate
the arrival to completion: intermediate superseding events may have been missed.
Null sample timing on an `UNKNOWN` status can be represented as observation and
expiry equal to arrival, without a latency sample. Record the polling interval
and resulting uncertainty in `label_method`. Polling is a development-only
smoke-test method; complete native event delivery is preferable for a benchmark.

`sensor_realtime` means Camera2 declares a clock shared with elapsed realtime.
`capture_request_bound` means its clock is unspecified and request time is used
as a conservative lower bound. Both paths reject mismatched or reordered images
and expire evidence two seconds after the capture-time basis, including delivery
and inference. The heuristic remains **uncalibrated**; its confidence is signal
strength, not a measured probability of correct gaze.

### Labelling and scoring

1. Identify the physical device, Android build, actual model digest, and exact
   classifier revision/configuration. Predeclare condition IDs, trial durations,
   repetitions, and held-out split before inspecting classifier results. Keep
   any tuning/calibration subjects/runs separate from held-out evaluation.
2. Use a second human observer to mark actual behavior independently, blinded to
   classifier output. A prompt to look at the screen is not itself evidence of
   compliance; mark only observed screen-directed visual attention. Cognitive
   understanding cannot be established by gaze and is outside this evaluation.
3. Synchronize the observer's labels with the same device elapsed-realtime clock
   before/after each run. Record synchronization precision in `label_method`.
   Exclude transition windows covering observer reaction and clock uncertainty;
   do not adjust truth boundaries to improve agreement. Never mix boots/clocks.
4. Capture only the derived arrival, capture, native expiry, label, and optional
   inference latency. Ensure arrival is when the native classification became
   available, not retrospective export/UI time. Record denied/unavailable camera,
   unreliable eyelids/eyes/head pose, lifecycle gaps, and stale inference honestly
   as `UNKNOWN` or gaps. Do not feed sensor-only fallback estimates into camera
   accuracy; evaluate that source separately with an explicitly versioned contract.
5. Balance attended/passive trials within every position/condition. Include both
   transitions and sustained trials; vary order. Include a second independent
   observer on a subset, adjudicate disagreement without classifier output, and
   exclude unresolved intervals. Record protocol details in `label_method` and
   private collection notes; avoid any raw-media recording/export.
6. Run this tool against held-out derived logs. Archive input/report/evaluator
   hashes, metadata, collection protocol, and Python version locally. Report
   false-attended and false-passive duration/rates, unknown/coverage, net duration
   error, and latency **for every condition**, including failures. Timing/exclusion
   diagnostics require investigation, not selective removal of poor outcomes.

## Position and failure-condition validation matrix

These are required candidate validation conditions, **not validated support
claims**. Predeclare intended supported positions; label unsupported conditions
explicitly, retain their results, and expect honest uncertainty when evidence is
unreliable. Use fixed `condition` IDs for each position/modifier combination.

| Position/setup | Attended truth trials | Passive truth trials | Coverage/robustness checks |
|---|---|---|---|
| Upright handheld, portrait | Natural reading at near/typical/far distances | Look beside/above phone while holding it | Pitch/yaw variation, mild movement |
| Seated, desk/stand below eye level | Read screen with downward head angle | Look at adjacent desk/paper/person | Head direction without eye agreement |
| Reclined, phone above chest | Natural reading with changed pitch | Look past/away from phone | Both eyes visible, eyelid confidence |
| Side-lying | Screen viewing with roll/partial occlusion | Look away while maintaining posture | One-eye visibility must not create false certainty |
| Landscape, held and stand-mounted | Read in rotated orientation | Look off-screen at similar head pose | Camera/screen rotation and aspect mapping |
| Walking slowly in a safe controlled space | Brief deliberate screen glances | Carry phone while looking ahead | Motion blur, frame loss, bounded expiry |
| Stationary phone, no viewer | Not applicable | Empty scene, person outside view | No-face handling, no attention from stillness |

Cross applicable positions with bright/indoor/dim/backlit illumination; glasses
and reflections; no glasses; natural blinks, sustained closed eyes, one-eye
occlusion, and both-eyes visibility; near/far distance; head-forward eyes-away
and head-turned eyes-screen trials. Record actual behavior rather than deriving
truth from the classifier's eyelid/pose thresholds.

Separately exercise app UI closed/open, camera permission denied/revoked, another
camera client, lock/unlock, dropped frames, delayed arrivals, and expiry gaps.
Assess correct `UNKNOWN` behavior and denied-permission fallback separately from
supported-position camera accuracy. Do not convert unknown into passive to make
a test pass. The full matrix remains pending; the initial guided device smoke
test is recorded in [VALIDATION.md](VALIDATION.md).
