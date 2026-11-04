package app.attune.attunenative.attention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttentionMotionClassifierTest {
  private val classifier = SensorAttentionClassifier()

  @Test
  fun motionNeverProvesAttentionAndStillnessDoesNotMeanLookingAway() {
    listOf(
      AttentionMotionSnapshot(reliable = true, fresh = true, still = true),
      AttentionMotionSnapshot(reliable = true, fresh = true, moving = true, heldOrientation = true)
    ).forEach { snapshot ->
      assertEquals(AttentionLabel.USAGE_ONLY, classifier.classify(snapshot, foregroundMs = 10_000L).label)
      assertEquals(AttentionLabel.UNKNOWN, classifier.classify(snapshot).label)
    }
  }

  @Test
  fun staleSensorsCannotClaimPassiveOrAttendedEvenWithInteraction() {
    val stale = AttentionMotionSnapshot(reliable = true, fresh = false, faceDown = true)
    val result = classifier.classify(stale, recentInteraction = true, foregroundMs = 10_000L)
    assertEquals(AttentionLabel.UNKNOWN, result.label)
    assertEquals(0f, result.confidence)
  }

  @Test
  fun realRecentInteractionIsOnlyALowConfidenceEstimate() {
    val result = classifier.classify(AttentionMotionSnapshot(reliable = true, fresh = true), recentInteraction = true)
    assertEquals(AttentionLabel.ATTENDED, result.label)
    assertTrue(result.confidence < 0.5f)
    assertTrue(result.reason.endsWith("estimate_not_gaze"))
  }

  @Test
  fun stableFaceDownAndCoveredScreenAreLabelledAsEstimates() {
    val reducer = AttentionMotionReducer()
    repeat(12) { index -> reducer.accelerometer(index * 200L, 0f, 0f, -9.81f) }
    val snapshot = reducer.snapshot(2_200L, true)
    assertTrue(snapshot.faceDown)
    val result = classifier.classify(snapshot)
    assertEquals(AttentionLabel.PASSIVE, result.label)
    assertEquals("face_down_estimate_not_gaze", result.reason)
    reducer.proximity(2_200L, true)
    reducer.accelerometer(3_200L, 0f, 0f, 9.81f)
    assertFalse(reducer.snapshot(3_200L, true).faceDown)
    assertTrue(reducer.snapshot(3_200L, true).proximityNear)
  }

  @Test
  fun singleFrameAndBriefPhoneFlipsCannotClaimStableFaceDown() {
    val reducer = AttentionMotionReducer()
    reducer.accelerometer(1_000L, 0f, 0f, -9.81f)
    assertFalse(reducer.snapshot(1_000L, true).reliable)
    repeat(10) { index -> reducer.accelerometer(1_200L + index * 200L, 0f, 0f, 9.81f) }
    reducer.accelerometer(3_200L, 0f, 0f, -9.81f)
    assertFalse(reducer.snapshot(3_200L, true).faceDown)
  }

  @Test
  fun sensorPauseOrLongGapDropsPriorOrientationAndProximity() {
    val reducer = AttentionMotionReducer()
    reducer.proximity(0L, true)
    repeat(12) { index -> reducer.accelerometer(index * 200L, 0f, 0f, -9.81f) }
    assertTrue(reducer.snapshot(2_200L, true).faceDown)
    assertFalse(reducer.snapshot(5_000L, true).reliable)
    reducer.accelerometer(5_000L, 0f, 0f, 9.81f)
    assertFalse(reducer.snapshot(5_000L, true).proximityNear)
    assertFalse(reducer.snapshot(5_000L, true).reliable)
    reducer.reset()
    assertFalse(reducer.snapshot(5_200L, true).fresh)
  }

  @Test
  fun nonFiniteAndOutOfOrderReadingsDoNotReplaceValidObservations() {
    val reducer = AttentionMotionReducer()
    repeat(12) { index -> reducer.accelerometer(index * 200L, 0f, 0f, 9.81f) }
    reducer.accelerometer(2_400L, Float.NaN, 0f, -9.81f)
    reducer.accelerometer(2_000L, 0f, 0f, -9.81f)
    val result = reducer.snapshot(2_400L, true)
    assertEquals(2_200L, result.sampledAtElapsedMs)
    assertTrue(result.still)
    assertFalse(result.faceDown)
    assertFalse(reducer.snapshot(2_000L, true).fresh)
  }

  @Test
  fun gyroscopeCapturesRotationButNeverClaimsAttention() {
    val reducer = AttentionMotionReducer()
    repeat(12) { index -> reducer.accelerometer(index * 200L, 0f, 9.81f, 0f) }
    reducer.gyroscope(2_200L, 0f, 0.8f, 0f)
    val snapshot = reducer.snapshot(2_200L, true)
    assertTrue(snapshot.moving)
    assertTrue(snapshot.heldOrientation)
    assertFalse(snapshot.still)
    assertEquals(AttentionLabel.USAGE_ONLY, classifier.classify(snapshot, foregroundMs = 5_000L).label)
  }

  @Test
  fun screenOffOverridesEverySensorAndPermissionState() {
    val result = classifier.classify(
      AttentionMotionSnapshot(screenOn = false, fresh = false),
      recentInteraction = true, usageAccessGranted = false
    )
    assertEquals(AttentionLabel.NOT_IN_USE, result.label)
    assertEquals("screen_off", result.reason)
  }

  @Test
  fun sensorOnlyMissingUsageAccessIsExplicit() {
    val result = classifier.classify(
      AttentionMotionSnapshot(reliable = true, fresh = true, still = true),
      usageAccessGranted = false, foregroundMs = 10_000L
    )
    assertEquals(AttentionLabel.UNKNOWN, result.label)
    assertEquals("motion_only_usage_access_required", result.reason)
  }
}
