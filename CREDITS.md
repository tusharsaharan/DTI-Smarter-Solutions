# Attune implementation credits

This prototype keeps external foundations isolated from product code and records the intended
license boundary before any model or fork is bundled.

| Foundation | Intended use | Status |
|---|---|---|
| Expo Modules API | Kotlin-to-React-Native bridge | Used through the local `@attune/native` module |
| Android `NotificationListenerService` / `NotificationManager` | Real notification access and DND policy | Native platform APIs |
| Android `InputMethodService` | Real Attune system keyboard | Native platform API; keyboard model is product code |
| Android `UsageStatsManager` | Usage-only screen-time baseline | Native platform API |
| Android Storage Access Framework / `PdfRenderer` / `AlarmManager` | Local timetable selection, PDF-page rendering and background Focus wake-ups | Native platform APIs |
| ML Kit Text Recognition (Latin, bundled) | On-device timetable image/PDF OCR | Pinned `com.google.mlkit:text-recognition:16.0.1`; proprietary Google API/ML Kit terms, not an Apache-licensed model |
| Android core-library desugaring | `java.time` dates/time zones on Android API 24/25 | Pinned `com.android.tools:desugar_jdk_libs:2.0.3`; Google open-source Java API backport |
| Sentence Transformers DistilUSE multilingual v2 | Offline notification sentence semantics | Apache-2.0; immutable revision `bfe45d0732ca50787611c0fe107ba278c7f3f889`, ARM64 int8 ONNX encoder with published mean-pooling/Dense/tanh and L2 normalization |
| ONNX Runtime Android | Local CPU inference for notification semantics | MIT, pinned `com.microsoft.onnxruntime:onnxruntime-android:1.23.2` |
| Camera2 | Duty-cycled front-camera captures for local eye/head sensing | Native platform API; no frames stored or exported |
| MediaPipe Face Landmarker | On-device iris, eyelid and eye-direction landmarks | `com.google.mediapipe:tasks-vision:0.10.26` (Apache-2.0), pinned; model bundle float16 version 1 bundled offline |
| YAMNet | Planned on-device environment classification | Not bundled yet; verify model license/version before adding |
| Flower | Optional federated aggregation adapter | Optional server dependency; see `server/fl/README.md` |
| AI4Bharat IndicXlit | Planned romanized Indic transliteration seed | Not bundled yet; verify model/license before adding |

No raw notification text, typed text, audio, or camera frames are sent to the federated-learning
server. The server accepts only bounded numeric model updates.

## Timetable OCR and Focus

The importer follows the [ML Kit Android Text Recognition guide](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)
using the bundled Latin-script model, so recognition does not require a runtime model download.
[Google's ML Kit terms/privacy](https://developers.google.com/ml-kit/terms) state that input images,
text and recognition results stay on-device; the SDK can send usage/performance metrics and contact
Google for maintenance. This is disclosed in the Smart Focus privacy card. The SDK/model are subject
to Google's API/ML Kit terms, not labelled as open-source product code.

Timetable parsing, review, bounded personal gradient-boosted learning and expiry/countdown safeguards
are product code. Timetable-derived labels are schedule priors, not proof of attendance or real DND
preferences. Predictions do not train themselves and confidence is not validated prediction accuracy.
Native wake-ups follow [Android's alarm scheduling guidance](https://developer.android.com/develop/background-work/services/alarms/schedule);
late countdowns are dropped instead of replayed. No other feature's models, GPU sessions or services
are used for Focus training.

## Notification semantics

The encoder comes from the [official Sentence Transformers model repository](https://huggingface.co/sentence-transformers/distiluse-base-multilingual-cased-v2).
`models/notification/prepare_model.py` pins its revision and exports the published pooling and Dense
projection into the quantized graph. The cased WordPiece tokenizer follows the Apache-2.0
[Hugging Face tokenizer algorithms](https://github.com/huggingface/tokenizers/tree/v0.23.2/tokenizers/src)
and is checked against exact token-ID fixtures from the pinned vocabulary. The Android inference
boundary follows [ONNX Runtime's mobile guidance](https://onnxruntime.ai/docs/tutorials/mobile/).
License texts and checksums ship beside the model in `assets/notification_semantics/`.

The six-class intent head and clause-level safeguards are project code. Training uses agent-authored,
synthetic English/Hinglish examples, not device messages or compressor artifacts. Model confidence
is not established real-notification accuracy; unknown meaning, conflicting evidence and unsupported
inputs preserve the original notification. Full conversation understanding and physical Android
latency/accuracy have not been established. See `models/notification/README.md` for evaluation limits.

## MediaPipe gaze integration

Google's unmodified [Face Landmarker float16 model bundle, version 1](https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task)
is distributed in `apps/mobile/modules/attune-native/android/src/main/assets/face_landmarker.task`.
SHA-256: `64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff`.
The Apache-2.0 license is bundled beside it as `MEDIAPIPE-LICENSE.txt`.
License verified from Google's model cards for [BlazeFace](https://storage.googleapis.com/mediapipe-assets/MediaPipe%20BlazeFace%20Model%20Card%20(Short%20Range).pdf),
[Face Mesh V2](https://storage.googleapis.com/mediapipe-assets/Model%20Card%20MediaPipe%20Face%20Mesh%20V2.pdf), and
[Blendshape V2](https://storage.googleapis.com/mediapipe-assets/Model%20Card%20Blendshape%20V2.pdf), and the
[MediaPipe v0.10.26 license](https://github.com/google-ai-edge/mediapipe/blob/v0.10.26/LICENSE).
Integration follows the [Android Face Landmarker guide](https://ai.google.dev/edge/mediapipe/solutions/vision/face_landmarker/android)
and the pinned Java API. Android camera2 input rotation follows Google's
[front-camera ML input convention](https://developers.google.com/ml-kit/vision/face-detection/android#prepare_the_input_image).
Product-specific gaze thresholds and temporal gating are our own heuristics, informed by the existing
`legacy/web/lib/features.ts` eye geometry. These confidence scores are not measured gaze-accuracy percentages.
Camera frames, landmarks, and blendshape vectors are discarded after each inference; only attention labels,
source, confidence, and elapsed-time aggregates can reach the app history. No runtime model download is required.

## App typography

[Fahkwang](https://github.com/cadsondemak/Fah-Kwang), copyright 2018 The Fahkwang Project Authors,
is used unmodified for editorial headings under the SIL Open Font License 1.1. The app loads its
Light and SemiBold font files locally through `@expo-google-fonts/fahkwang` 0.4.1 (Expo wrapper: MIT).
The distribution's font copyright/license metadata is retained; its full license is also available
in the package's `LICENSE_FONT`. Inter body text and JetBrains Mono numerals remain unchanged.

## Editorial interaction reference

[Igloo Inc.](https://www.igloo.inc/) was inspected as an interaction reference for fine numbered
annotations, assembling forms and spare typography. Attune's masked headings, numbered feature
index and press-drawn rules are original 2D React Native/Reanimated/Skia implementations. No Igloo
source code, assets, logos, scene geometry, audio or artwork are copied or bundled.
