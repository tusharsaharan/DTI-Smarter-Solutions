package app.attune.attunenative.attention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class GazeClassifierTest {
  private fun neutral() = GazeFrame(1, EyeGaze(0f, 0.1f, 0.25f), EyeGaze(0f, 0.1f, 0.25f), lookHorizontal = 0f, lookVertical = -0.1f)
  private fun stabilize(classifier: GazeClassifier, frame: GazeFrame, since: Long): AttentionClassification {
    classifier.classify(frame, since)
    return classifier.classify(frame, since + 500)
  }

  @Test fun eyeOnlyLookAwayWithdrawsOldLookingClaimImmediately() {
    val classifier = GazeClassifier()
    assertEquals(AttentionLabel.ATTENDED, stabilize(classifier, neutral(), 100).label)
    val away = neutral().copy(left = EyeGaze(0.55f, 0f, 0.25f), right = EyeGaze(0.55f, 0f, 0.25f))
    assertEquals(AttentionLabel.UNKNOWN, classifier.classify(away, 1100).label)
    assertEquals(AttentionLabel.PASSIVE, classifier.classify(away, 1600).label)
    assertEquals(AttentionLabel.UNKNOWN, classifier.classify(neutral(), 2100).label)
    assertEquals(AttentionLabel.ATTENDED, classifier.classify(neutral(), 2600).label)
  }

  @Test fun conflictingEyesNeverAverageToLooking() {
    val conflicting = neutral().copy(left = EyeGaze(-0.4f, 0f, 0.25f), right = EyeGaze(0.4f, 0f, 0.25f))
    assertEquals("eyes_disagree", stabilize(GazeClassifier(), conflicting, 0).reason)
  }

  @Test fun blinkAndOcclusionDoNotBecomeLookingAwayOrStayLooking() {
    val classifier = GazeClassifier()
    stabilize(classifier, neutral(), 0)
    val blink = classifier.classify(neutral().copy(blinkLeft = 0.9f), 1000)
    assertEquals(AttentionLabel.UNKNOWN, blink.label)
    assertEquals("eyes_closed_or_occluded", blink.reason)
    assertEquals(AttentionLabel.UNKNOWN, classifier.classify(neutral().copy(left = null), 1500).label)
  }

  @Test fun lookingAtLowerPartOfScreenRemainsAttended() {
    val frame = neutral().copy(left = EyeGaze(0.1f, 0.5f, 0.22f), right = EyeGaze(0.12f, 0.48f, 0.21f), lookVertical = -0.28f)
    assertEquals(AttentionLabel.ATTENDED, stabilize(GazeClassifier(), frame, 0).label)
  }

  @Test fun headTurnAndTrainedEyeDirectionEachDetectAway() {
    assertEquals("head_turned_away", stabilize(GazeClassifier(), neutral().copy(yaw = 0.55f), 0).reason)
    assertEquals("eyes_looking_away", stabilize(GazeClassifier(), neutral().copy(lookHorizontal = 0.65f), 0).reason)
  }

  @Test fun compensatedModerateHeadTurnStaysUnknownAndDiagonalEyesDoNotLookCentered() {
    val compensated = neutral().copy(yaw = 0.3f, left = EyeGaze(-0.2f, 0.1f, 0.25f), right = EyeGaze(-0.2f, 0.1f, 0.25f))
    assertEquals("head_eye_direction_conflict", stabilize(GazeClassifier(), compensated, 0).reason)
    val diagonal = neutral().copy(left = EyeGaze(0.32f, 0.72f, 0.25f), right = EyeGaze(0.32f, 0.72f, 0.25f))
    assertEquals(AttentionLabel.PASSIVE, stabilize(GazeClassifier(), diagonal, 0).label)
  }

  @Test fun missedFramesResetEvidenceAndInvalidDataIsUnknown() {
    val classifier = GazeClassifier()
    stabilize(classifier, neutral(), 0)
    assertEquals(AttentionLabel.UNKNOWN, classifier.classify(neutral(), 4000).label)
    assertEquals(AttentionLabel.UNKNOWN, classifier.classify(neutral().copy(yaw = Float.NaN), 4500).label)
    assertEquals(AttentionLabel.UNKNOWN, classifier.classify(neutral().copy(lookHorizontal = null), 5000).label)
    assertEquals(AttentionLabel.UNKNOWN, classifier.classify(neutral().copy(faceCount = 2), 5500).label)
  }

  @Test fun geometryPreservesEyeDirectionDespiteOppositeCornerOrderAndRoll() {
    fun point(x: Float, y: Float): GazePoint {
      val a = 0.3f
      return GazePoint(x * cos(a) - y * sin(a), x * sin(a) + y * cos(a))
    }
    val a = point(0.2f, 0.3f); val b = point(0.3f, 0.3f)
    val iris = point(0.27f, 0.3f); val top = point(0.25f, 0.2875f); val bottom = point(0.25f, 0.3125f)
    val left = GazeGeometry.eye(iris, a, b, top, bottom)!!
    val right = GazeGeometry.eye(iris, b, a, top, bottom)!!
    assertEquals(0.4f, left.x, 0.001f)
    assertEquals(left.x, right.x, 0.001f)
    assertTrue(kotlin.math.abs(left.y) < 0.001f)
    assertNull(GazeGeometry.eye(iris.copy(x = Float.NaN), a, b, top, bottom))
  }

  @Test fun runtimeNeverReturnsStaleOrStoppedGaze() {
    val previous = CameraAttentionRuntime.status
    try {
      val result = AttentionClassification(AttentionLabel.ATTENDED, 0.7f, "camera", "eyes_toward_screen")
      CameraAttentionRuntime.status = CameraRuntimeStatus(running = true, classification = result, observationElapsedMs = 100L)
      assertEquals(result, CameraAttentionRuntime.currentClassification(500))
      assertNull(CameraAttentionRuntime.currentClassification(2101))
      CameraAttentionRuntime.status = CameraAttentionRuntime.status.copy(running = false)
      assertNull(CameraAttentionRuntime.currentClassification(500))
    } finally { CameraAttentionRuntime.status = previous }
  }
}
