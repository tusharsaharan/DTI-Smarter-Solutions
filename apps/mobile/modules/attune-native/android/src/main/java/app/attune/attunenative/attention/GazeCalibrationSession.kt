package app.attune.attunenative.attention

/** Pure, clock-injected guided collection. The participant confirms every label after each step.
 * Samples are bounded, never self-labelled, and cleared on completion, failure or cancellation. */
class GazeCalibrationSession(val cameraKey: String, val cameraSession: Long, nowMs: Long) {
  companion object {
    val STEPS = listOf("center", "top_left", "top_right", "bottom_left", "bottom_right", "reading", "away_left", "away_right", "away_down")
    const val STEP_MS = 10_000L
    const val SETTLE_MS = 2_000L
    const val HEARTBEAT_MS = 3_000L
  }
  var state = "ready"; private set
  var stepIndex = 0; private set
  var reason: String? = null; private set
  var profile: PersonalGazeProfile? = null; private set
  var validationCounts: List<Map<String, Int>> = emptyList(); private set
  private var heartbeat = nowMs
  private var started = 0L
  private var lastObserved = -1L
  private var firstObserved = -1L
  private var pendingCapture: Long? = null
  private var total = 0
  private var correct = 0
  private var incorrect = 0
  private val samples = mutableListOf<GazeFrame>()
  private val targets = mutableListOf<List<GazeFrame>>()
  private var validator: GazeClassifier? = null
  val active: Boolean get() = state in listOf("ready", "collecting", "finalizing", "review")

  fun heartbeat(nowMs: Long) { tick(nowMs); heartbeat = nowMs }

  fun tick(nowMs: Long) {
    if (!active) return
    if (nowMs < heartbeat || nowMs - heartbeat > HEARTBEAT_MS) { fail("interrupted"); return }
    if (state == "collecting" && nowMs >= started + STEP_MS) state = "finalizing"
    if (state == "finalizing") {
      if (pendingCapture != null) {
        if (nowMs > started + STEP_MS + CameraEvidenceClock.MAX_AGE_MS) fail("tracking_unreliable")
        return
      }
      if (total < 8 || samples.size < 6 || samples.size.toFloat() / total < .55f ||
        firstObserved < 0 || firstObserved > started + SETTLE_MS + 1000 || lastObserved < started + STEP_MS - 1000) fail("tracking_unreliable")
      else state = "review"
    }
  }

  fun collect(nowMs: Long) {
    heartbeat(nowMs)
    if (state != "ready") return
    started = nowMs; lastObserved = -1L; firstObserved = -1L; total = 0; correct = 0; incorrect = 0
    pendingCapture = null
    samples.clear()
    validator = profile?.let { GazeClassifier(it::classify) }
    state = "collecting"
  }

  /** Called under the runtime lock before Camera2 is asked to capture. A pre-window
   * request can still expose inside the window, so it must also be drained. */
  fun captureStarted(requestedMs: Long) {
    if (state !in listOf("collecting", "finalizing") || requestedMs >= started + STEP_MS) return
    if (pendingCapture != null && pendingCapture != requestedMs) { fail("tracking_unreliable"); return }
    pendingCapture = requestedMs
  }

  /** Resolve only after scoring or rejecting the result, never at inference start. */
  fun captureFinished(requestedMs: Long, nowMs: Long) {
    if (pendingCapture == requestedMs) pendingCapture = null
    tick(nowMs)
  }

  fun observe(frame: GazeFrame, observedMs: Long, completedMs: Long, key: String, session: Long, deliveredMs: Long = completedMs) {
    tick(deliveredMs)
    if (!active) return
    if (key != cameraKey || session != cameraSession) { fail("camera_changed"); return }
    if (state !in listOf("collecting", "finalizing") || observedMs < started + SETTLE_MS || observedMs >= started + STEP_MS) return
    if (observedMs > completedMs || deliveredMs < completedMs || deliveredMs - observedMs > CameraEvidenceClock.MAX_AGE_MS) {
      fail("tracking_unreliable"); return
    }
    if (observedMs <= lastObserved || (lastObserved >= 0 && observedMs - lastObserved > CameraEvidenceClock.MAX_AGE_MS)) {
      fail("tracking_unreliable"); return
    }
    if (firstObserved < 0) firstObserved = observedMs
    lastObserved = observedMs
    total++
    if (GazeQuality.rejection(frame) == null && PersonalGazeProfile.features(frame) != null && samples.size < 24) samples += frame
    if (stepIndex >= 5) {
      val prediction = validator!!.classify(frame, observedMs).label
      val truth = if (stepIndex == 5) AttentionLabel.ATTENDED else AttentionLabel.PASSIVE
      if (prediction == truth) correct++
      else if (prediction != AttentionLabel.UNKNOWN) incorrect++
    }
  }

  fun confirm(nowMs: Long, wallMs: Long) {
    heartbeat(nowMs)
    if (state != "review") return
    if (stepIndex < 5) {
      targets += samples.toList()
      if (stepIndex == 4) {
        profile = PersonalGazeProfile.fit(targets, cameraKey, wallMs)
        targets.clear()
        if (profile == null) { fail("position_unstable"); return }
      }
    } else {
      validationCounts = validationCounts + mapOf("total" to total, "correct" to correct, "incorrect" to incorrect, "unknown" to total - correct - incorrect)
      // Each separate check must pass. Unknowns count against coverage, not as successes.
      if (correct.toFloat() / total < .65f || incorrect.toFloat() / total > .10f) { fail("validation_failed"); return }
    }
    samples.clear(); validator = null
    if (stepIndex == STEPS.lastIndex) state = "complete"
    else { stepIndex++; state = "ready" }
  }

  fun fail(cause: String) {
    state = "failed"; reason = cause; profile = null
    targets.clear(); samples.clear(); validator = null; pendingCapture = null
  }

  /** Capture failures are missing evidence, never silently omitted from a setup trial. */
  fun rejected() {
    validator?.reset()
    if (state in listOf("collecting", "finalizing")) fail("tracking_unreliable")
  }

  fun remaining(nowMs: Long): Long = if (state == "collecting") (started + STEP_MS - nowMs).coerceIn(0, STEP_MS) else 0
  fun sampleCount(): Int = samples.size
}
