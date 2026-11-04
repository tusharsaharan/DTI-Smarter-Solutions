# AGENTS.md — "Same Phone, Just Smarter" (DTI prototype)

Read this first. It applies to every agent and sub-agent working in this repo.

## 1. What we are building
A working, installable prototype of the 5 ideas in `DTI_Smarter_Solutions.pptx`, unified as one product
following the deck's loop **Sense → Understand → Adapt → Protect → Care**:

| # | Feature (deck idea) | Core signal | Owner team |
|---|---|---|---|
| 1 | Smart group-chat notification ranking | sender + message text (NLP) + past response | Aman, Prince |
| 2 | Hinglish / code-switching keyboard, learns on-device (federated) | typing patterns | Riya, Shreya |
| 3 | Context-aware interruption (smart DND) | activity, calendar, location, app usage | Rahul, Yash |
| 4 | Attention-accurate screen time | front-camera gaze (+ fallbacks) | Tushar, Rakesh |
| 5 | Ambient-noise adaptive volume | microphone dB + environment type | Omprakash, Varshith |

Rules: follow the **ideas** in the deck strictly (no new features), but the implementation approach is ours.
**Android app only** (no website). Design comes first: the app must feel premium.
**Real, not demo:** real notifications, real DND, real volume, real system keyboard, real usage stats. No mock data or demo modes.
Everything sensitive runs **on-device**; no raw text/audio/video leaves the phone. See `PLAN.md` for the build plan.

## 2. Token-efficient, quality-first execution policy
Minimize token usage wherever it does not reduce the quality of the finished result. Correctness, security, privacy, native Android behavior, design quality, meaningful tests, and required verification take priority over token savings. There is no fixed token cap; use the reasoning and implementation capability the task needs.

**Token efficiency**
- Search with scoped `rg` queries first, then read only relevant files and line ranges. Skip generated files, dependency trees, build output, binary payloads, and lockfile contents unless needed.
- Batch independent searches and reads. Reuse verified facts and existing tool results; do not reread unchanged files or repeat passing checks without a reason.
- Keep plans, updates, tool output, and final reports concise. Summarize findings and failures instead of pasting logs or repeating the task.
- Give sub-agents bounded, independent scopes and minimal necessary context; avoid duplicate implementation work. Use them when parallel work or independent review improves the result enough to justify its cost.
- Make focused changes and run the relevant tests first, then all required checks. Never omit a necessary investigation, test, documentation lookup, or device check just to save tokens.
- Preserve complete user requirements and honest limitations in handoffs. Token efficiency must not turn a real feature into a mock, hide uncertainty, or lower the definition of done.

**Working style**
- Use sub-agents when they improve correctness, provide independent review, or allow genuinely parallel work. Give each one a self-contained scope and integrate their actual results.
- Use the best available model or agent for architecture, native Android lifecycle work, cross-feature behavior, debugging, and final review.
- Read the relevant source and project instructions before changing code. Avoid irrelevant generated output, dependency directories, build artifacts, and binary payloads unless the task requires them.
- Prefer proven open-source foundations over reimplementing difficult platform behavior (see `PLAN.md §4`). Verify licenses, versions, integration boundaries, and credit sources in `CREDITS.md`.
- Use authoritative, version-matched documentation for Expo, Android, Kotlin, and third-party libraries instead of relying on memory.
- Explain important architectural or privacy choices clearly, but spend the majority of effort on working code, tests, and verification.
- Keep temporary experiments and synthetic design data clearly separated from release behavior. Never present a mock as a real feature.

**Offline models and authorized GPU use**
- Prefer the checked-in or locally staged offline model assets under `/models` and native `assets/` for semantic understanding, evaluation, and Android integration. Do not replace a required model with keyword-only judging or a mock just because a model is inconvenient to run.
- When an authorized machine exposes the RTX 5000 Ada 32 GB GPU (or another available accelerator), use it generously for offline model evaluation, synthetic-data training, quantization checks, latency/batch benchmarks, and error analysis. Keep runs reproducible by recording the model hash, dataset/split, command, hardware, and metrics.
- Inspect GPU availability and active processes before starting (`nvidia-smi` or the platform equivalent). Never kill, evict, or contend with another model's active workload; leave the concurrent chat compressor's GPU, models, sessions, and memory untouched. Use only genuinely free capacity or a separately assigned GPU.
- GPU use is a development/evaluation aid, not a privacy exception. Use public/synthetic data only for workstation experiments; never upload raw notifications, typed text, audio, video, calendar data, or credentials. Do not commit private endpoints, proxy settings, tokens, or machine-specific paths.
- Android behavior must still have a CPU/on-device path with no network inference, CUDA, Ollama, or workstation dependency. GPU results may guide or validate a model, but they do not replace Kotlin/JUnit tests, CPU parity checks, build checks, or physical-device verification.

## 3. Repo layout (target)
```
/apps/mobile            Expo (React Native) Android app + Kotlin native modules (Expo Modules API)
/apps/keyboard          FlorisBoard fork → Attune Keyboard (Kotlin IME)
/server/fl              FastAPI + Flower FedAvg/DP server (Docker)
/packages/design-tokens shared colour/type/spacing/motion tokens (app + keyboard theme)
/models                 on-device models (tflite) + training notebooks
/legacy/web             old Next.js `trueattention` app; its gaze maths in lib/ is ported for Idea 4
/vendor                 cloned OSS repos (git-ignored, referenced only)
```

## 4. Engineering conventions
- TypeScript strict in RN, Kotlin for services. Logic that must run with the UI closed lives in Kotlin.
- Each feature follows the same shape: sense → understand → adapt, with its permissions and fallback documented.
- Every feature MUST degrade gracefully when a permission is denied (see `PLAN.md §6`). Never crash, never nag.
- Priority Inbox: validate English/Hinglish college messages (exams, classes, viva, timing/venue changes and deadlines), including important cancellation updates. Rank the latest message, never the group title or stale bundled urgency. Preserve the original when meaning is uncertain. Use local CPU resources for notification work; leave the other chat's compressor GPU, models and sessions untouched.
- Battery-aware: duty-cycle sensors, use hysteresis, and never poll in tight loops.
- True Screen Time: check both eyes, eyelids and head direction; discard stale or unreliable camera evidence instead of retaining a looking/away label. When camera access is denied or unavailable, automatically use the accelerometer and available motion/proximity sensors plus recent interaction signals. Label this fallback as an estimate, never measured gaze; motion or stillness alone does not prove attention. Keep unknown time separate and validate both false positives and false negatives against physical-device ground truth.
- Privacy card per feature: what is sensed, where it's stored, how to delete it.
- UI: only use design tokens (no raw colours or sizes), Reanimated + Skia for motion and visuals, haptics on state changes,
  dark and light themes. No 3D. Run the design-critique / polish skills on every new screen.
- No authored emoji in source, sample UI content, or documentation. Use shared vector icons or plain text. Unicode input tests may use escaped fixtures; preserve the pinned third-party model vocabulary and original user messages. Run `npm run check:emoji` at the repo root.
- Commit small, conventional messages (`feat(notify): ...`).

The Priority Inbox may use a local workstation GPU for offline model training/evaluation only; its Android runtime remains CPU/on-device. The concurrent chat compressor's GPU, models, and sessions are reserved and must not be touched.

## 5. Definition of done
A feature is complete only when:
1. Its real Android signal is read.
2. Its real system action is performed.
3. Its permission-denied behavior works honestly.
4. Sensitive raw inputs stay on-device according to the privacy contract.
5. It survives the app UI being closed wherever Android permits it.
6. Meaningful unit/integration tests exist for its important boundaries.
7. The UI shows current source, confidence, status, and limitations.
8. It has been tested on a physical Android device when the feature depends on hardware or OS behavior.

## 6. Verification
- Unit tests (Jest for TS, JUnit for Kotlin) must pass.
- When an authorized GPU is available, run the relevant offline model benchmark/evaluation and compare it with the CPU/runtime path; report model hash, data split, hardware, metrics, and abstentions or regressions.
- Verify on a real Android phone (or emulator) via `mobile-mcp`; screenshot each feature doing its real action.
- Report honestly what was and wasn't verified.
