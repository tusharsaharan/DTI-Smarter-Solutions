# Notification ranking verification — 2026-10-29

Scope: Priority Inbox only. Current college schedule/deadline changes (including cancellations),
English/Hinglish scope and negation, latest-message parsing, guarded offline semantic inference,
personal-feedback boundaries and safe notification adaptation. Other pre-existing feature edits
in the working tree were preserved.

Passed on the final code:

- Kotlin compilation and all 182 JUnit tests: `:attune-native:testDebugUnitTest --offline`.
- All 9 mobile Vitest tests, TypeScript type checking and Expo lint.
- All 5 CPU model export/numerical/data-split checks.
- Exact tokenizer fixtures and eight exported classifier-vector parity fixtures run in JUnit.
- Whitespace verification (`git diff --check`).

The college integration test verifies that a confirmed current cancellation reaches the real
adaptation policy even with an unknown sender or negative response feedback, while denied posting
access and a missing open-original action preserve the source notification. Other tests cover
rumours, quotations, conditionals, negated danger, recovery, stale/group-crossing feedback,
notification updates, unsupported model suggestions and derived-only durable history.

Not verified:

- No Android phone/emulator was connected. Real notification delivery, channel/OEM behavior,
  actions, background operation, latency, battery and real-message accuracy remain unverified.
- Release APK packaging was attempted but failed in existing React Native Worklets CMake output
  paths on Windows and an uncached `androidx.collection:collection-ktx:1.1.0` release dependency.
  No new installable release APK is claimed. Native changes require rebuilding the app.
- The small intent model uses agent-authored synthetic training data with limited held-out
  generalization; confidence is not a real-message accuracy guarantee. Scope guards and
  uncertainty preserve originals instead of granting the classifier unrestricted control.

All work after the user's compressor restriction used local CPU resources only. The compressor's
remote GPU, models and sessions were not accessed during this work. Public model generation and
CPU evaluation are separate from live on-device notification processing; no real messages were
uploaded. See `README.md` for model evaluation limits and reproduction.
