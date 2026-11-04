package app.attune.attunenative.attention

import org.junit.Assert.*
import org.junit.Test

class GazeCalibrationSessionTest {
  private var now = 100L
  private fun session() = GazeCalibrationSession("front:270", 1, now)
  private fun step(s: GazeCalibrationSession, frame: GazeFrame, confirm: Boolean = true) {
    s.collect(now)
    val start = now
    for (i in 1..19) {
      now = start + i * 500L
      s.heartbeat(now)
      s.captureStarted(now)
      s.observe(frame, now, now + 100, "front:270", 1)
      s.captureFinished(now, now + 100)
    }
    now = start + 10_000
    s.heartbeat(now)
    if (confirm) s.confirm(now, 10_000)
  }

  @Test fun requiresExplicitConfirmationAndAllSeparatePositiveNegativeChecks() {
    val s = session()
    step(s, readingFrame(), false)
    assertEquals("review", s.state)
    assertNull(s.profile)
    s.confirm(now, 10_000)
    screenTargets().drop(1).forEach { step(s, it.first()) }
    assertEquals("ready", s.state)
    val frozen = s.profile
    assertNotNull(frozen)
    step(s, readingFrame())
    step(s, readingFrame(dx = -.4f))
    step(s, readingFrame(dx = .4f))
    step(s, readingFrame(dy = .3f))
    assertEquals("complete", s.state)
    assertFalse(s.active)
    assertEquals(frozen, s.profile)
    assertEquals(4, s.validationCounts.size)
    assertEquals(0, s.sampleCount())
  }

  @Test fun overlappingScreenAndAwayDataFailsRatherThanRefitting() {
    val s = session()
    screenTargets().forEach { step(s, it.first()) }
    step(s, readingFrame())
    step(s, readingFrame()) // labelled away but indistinguishable from reading
    assertEquals("failed", s.state)
    assertEquals("validation_failed", s.reason)
    assertNull(s.profile)
    assertEquals(0, s.sampleCount())
  }

  @Test fun unknownPredictionsDoNotCountAsSuccessfulValidation() {
    val s = session()
    screenTargets().forEach { step(s, it.first()) }
    step(s, readingFrame().copy(faceCenterX = .9f))
    assertEquals("validation_failed", s.reason)
  }

  @Test fun missingHeartbeatCancelsAndCannotBeResumedByLaterPing() {
    val s = session()
    s.collect(now)
    s.heartbeat(now + 3001)
    assertEquals("interrupted", s.reason)
    s.collect(now + 3100)
    assertEquals("failed", s.state)
    assertNull(s.profile)
  }

  @Test fun cameraSessionOrRotationChangeCancelsEvenBetweenSteps() {
    for ((key, id) in listOf("front:90" to 1L, "front:270" to 2L)) {
      val s = session()
      s.observe(readingFrame(), now, now, key, id)
      assertEquals("camera_changed", s.reason)
    }
  }

  @Test fun prePromptWarmupStaleAndDuplicateFramesCannotTrain() {
    val s = session()
    s.collect(now)
    s.observe(readingFrame(), now - 1, now + 10, "front:270", 1)
    s.observe(readingFrame(), now + 1000, now + 1100, "front:270", 1)
    assertEquals(0, s.sampleCount())
    s.heartbeat(now + 2500)
    s.observe(readingFrame(), now + 2500, now + 2600, "front:270", 1)
    assertEquals(1, s.sampleCount())
    s.observe(readingFrame(), now + 2500, now + 2700, "front:270", 1)
    assertEquals("tracking_unreliable", s.reason)
    assertEquals(0, s.sampleCount())
  }

  @Test fun absentCameraFramesAndUnreliableEyesCannotFinishATarget() {
    val s = session()
    step(s, readingFrame().copy(blinkRight = .9f))
    assertEquals("tracking_unreliable", s.reason)
    assertNull(s.profile)
  }

  @Test fun earlyGoodBurstCannotHideAnOutageAtTheEndOfTheWindow() {
    val s = session()
    s.collect(now)
    val start = now
    for (i in 1..20) {
      now = start + i * 500L
      s.heartbeat(now)
      if (i in 4..11) s.observe(readingFrame(), now, now + 100, "front:270", 1)
    }
    assertEquals("tracking_unreliable", s.reason)
    assertNull(s.profile)
  }

  @Test fun captureRejectionAndLargeInternalGapInvalidateCollection() {
    val rejected = session()
    rejected.collect(now)
    rejected.rejected()
    assertEquals("tracking_unreliable", rejected.reason)
    val gap = session()
    gap.collect(now)
    gap.heartbeat(now + 2100)
    gap.observe(readingFrame(), now + 2000, now + 2100, "front:270", 1)
    gap.heartbeat(now + 4500)
    gap.observe(readingFrame(), now + 4400, now + 4500, "front:270", 1)
    assertEquals("tracking_unreliable", gap.reason)
  }

  private fun provisionalFinalCheck(): Pair<GazeCalibrationSession, Long> {
    val s = session()
    screenTargets().forEach { step(s, it.first()) }
    step(s, readingFrame())
    step(s, readingFrame(dx = -.4f))
    step(s, readingFrame(dx = .4f))
    s.collect(now)
    val start = now
    for (i in 0..11) {
      now = start + 2000 + i * 650L
      s.heartbeat(now)
      s.captureStarted(now)
      val f = if (i < 9) readingFrame(dy = .3f) else readingFrame().copy(faceCenterX = .9f)
      s.observe(f, now, now + 100, "front:270", 1)
      s.captureFinished(now, now + 100)
    }
    // Before the pending result: 8 correct / 12 total would pass (66.7%).
    s.captureStarted(start + 9700)
    now = start + 10_000
    s.heartbeat(now)
    assertEquals("finalizing", s.state)
    s.confirm(now + 1, 10_000)
    assertEquals("finalizing", s.state)
    assertEquals(3, s.validationCounts.size)
    return s to start
  }

  @Test fun finalConfirmationWaitsForLateUnknownThatTurnsProvisionalPassIntoFailure() {
    val (s, start) = provisionalFinalCheck()
    s.observe(readingFrame().copy(faceCenterX = .9f), start + 9700, start + 10_300, "front:270", 1)
    assertEquals("finalizing", s.state)
    s.captureFinished(start + 9700, start + 10_300)
    assertEquals("review", s.state)
    s.confirm(start + 10_301, 10_000)
    assertEquals("validation_failed", s.reason)
    assertNull(s.profile)
    assertEquals(mapOf("total" to 13, "correct" to 8, "incorrect" to 0, "unknown" to 5), s.validationCounts.last())
  }

  @Test fun finalizationRejectionOrMissingResultCannotSaveProvisionalPass() {
    val (rejected, _) = provisionalFinalCheck()
    rejected.rejected()
    rejected.confirm(now + 2, 10_000)
    assertEquals("tracking_unreliable", rejected.reason)
    assertNull(rejected.profile)
    val (missing, start) = provisionalFinalCheck()
    missing.heartbeat(start + 12_001)
    missing.confirm(start + 12_002, 10_000)
    assertEquals("tracking_unreliable", missing.reason)
    assertNull(missing.profile)
  }

  @Test fun finishingRequestExposedAfterWindowDoesNotExtendScoringWindow() {
    val (s, start) = provisionalFinalCheck()
    s.observe(readingFrame().copy(faceCenterX = .9f), start + 10_100, start + 10_300, "front:270", 1)
    s.captureFinished(start + 9700, start + 10_300)
    s.confirm(start + 10_301, 10_000)
    assertEquals("complete", s.state)
    assertEquals(mapOf("total" to 12, "correct" to 8, "incorrect" to 0, "unknown" to 4), s.validationCounts.last())
  }

  @Test fun heartbeatMayOvertakeInferenceCompletionButCannotOmitItsQueuedResult() {
    val (s, start) = provisionalFinalCheck()
    s.observe(readingFrame().copy(faceCenterX = .9f), start + 9700, start + 9900, "front:270", 1, start + 10_300)
    s.captureFinished(start + 9700, start + 10_300)
    s.confirm(start + 10_301, 10_000)
    assertEquals("validation_failed", s.reason)
    assertEquals(13, s.validationCounts.last()["total"])
  }
}
