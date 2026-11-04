package app.attune.attunenative.attention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class GazeClassifierTest {
  private fun neutral() = GazeFrame(1, EyeGaze(0f, 0.025f, 0.25f), EyeGaze(0f, 0.025f, 0.25f),
    blinkLeft = 0f, blinkRight = 0f, lookHorizontal = 0f, lookVertical = 0.1f,
    leftDirection = EyeDirection(0f, 0.1f), rightDirection = EyeDirection(0f, 0.1f))
  private fun GazeFrame.withDirections(left: EyeDirection, right: EyeDirection = left) = copy(
    leftDirection = left, rightDirection = right,
    lookHorizontal = (left.x + right.x) / 2f, lookVertical = (left.y + right.y) / 2f)
  private fun away() = neutral().copy(left = EyeGaze(0.55f, 0f, 0.25f), right = EyeGaze(0.55f, 0f, 0.25f))
    .withDirections(EyeDirection(0.65f, 0f))
  private fun stabilize(classifier: GazeClassifier, frame: GazeFrame, since: Long): AttentionClassification {
    classifier.classify(frame, since)
    return classifier.classify(frame, since + 500)
  }

  @Test fun corroboratedEyeOnlyLookAwayWithdrawsOldLookingClaimImmediately() {
    val classifier = GazeClassifier()
    assertEquals(AttentionLabel.ATTENDED, stabilize(classifier, neutral(), 100).label)
    val away = away()
    assertEquals(AttentionLabel.UNKNOWN, classifier.classify(away, 1100).label)
    assertEquals(AttentionLabel.PASSIVE, classifier.classify(away, 1600).label)
    assertEquals(AttentionLabel.UNKNOWN, classifier.classify(neutral(), 2100).label)
    assertEquals(AttentionLabel.ATTENDED, classifier.classify(neutral(), 2600).label)
  }

  @Test fun conflictingEyesNeverAverageToLooking() {
    val conflicting = neutral().copy(left = EyeGaze(-0.4f, 0f, 0.25f), right = EyeGaze(0.4f, 0f, 0.25f))
    assertEquals("eyes_disagree", stabilize(GazeClassifier(), conflicting, 0).reason)
  }

  @Test fun bilateralBlendCancellationCannotBecomeLooking() {
    for ((left, right) in listOf(EyeDirection(-0.6f, 0f) to EyeDirection(0.6f, 0f),
      EyeDirection(0f, -0.6f) to EyeDirection(0f, 0.6f))) {
      val frame = neutral().withDirections(left, right)
      assertEquals(0f, frame.lookHorizontal!!, 0f)
      assertEquals(0f, frame.lookVertical!!, 0f)
      val result = stabilize(GazeClassifier(), frame, 0)
      assertEquals(AttentionLabel.UNKNOWN, result.label)
      assertEquals("eye_directions_disagree", result.reason)
    }
  }

  @Test fun aSingleChannelCannotForceAwayOrRetainLooking() {
    val frames = listOf(
      neutral().withDirections(EyeDirection(0.65f, 0f)),
      neutral().copy(left = EyeGaze(0.55f, 0f, 0.25f), right = EyeGaze(0.55f, 0f, 0.25f)),
      // Synthetic reproduction of the observed failure mode: centered irises and strong down blends.
      neutral().copy(left = EyeGaze(-0.068f, -0.02f, 0.25f), right = EyeGaze(0.074f, -0.01f, 0.25f), pitch = 0.246f)
        .withDirections(EyeDirection(0f, 0.711f)))
    for (frame in frames) {
      val classifier = GazeClassifier()
      assertEquals(AttentionLabel.ATTENDED, stabilize(classifier, neutral(), 0).label)
      assertEquals("eye_direction_conflict", classifier.classify(frame, 1000).reason)
      assertEquals(AttentionLabel.UNKNOWN, classifier.classify(frame, 1500).label)
      assertEquals(AttentionLabel.UNKNOWN, classifier.classify(neutral(), 2000).label)
      assertEquals(AttentionLabel.ATTENDED, classifier.classify(neutral(), 2500).label)
    }
  }

  @Test fun oppositeAndPerpendicularChannelsAreUnknownEvenWhenBothHaveAwayMagnitude() {
    val verticalIris = neutral().copy(left = EyeGaze(0f, 0.3f, 0.25f), right = EyeGaze(0f, 0.3f, 0.25f))
    for (frame in listOf(away().withDirections(EyeDirection(-0.65f, 0f)),
      verticalIris.withDirections(EyeDirection(0f, -0.65f)),
      away().withDirections(EyeDirection(0f, 0.65f)))) {
      val result = stabilize(GazeClassifier(), frame, 0)
      assertEquals(AttentionLabel.UNKNOWN, result.label)
      assertEquals("eye_direction_conflict", result.reason)
    }
  }

  @Test fun corroborationIsRequiredForEachEyeIndividually() {
    val frame = away().copy(right = neutral().right)
    assertEquals("eye_direction_conflict", stabilize(GazeClassifier(), frame, 0).reason)
    val opposedButIndividuallyCentered = neutral().copy(left = EyeGaze(-0.2f, 0f, 0.25f), right = EyeGaze(0.2f, 0f, 0.25f))
    assertEquals("eyes_disagree", stabilize(GazeClassifier(), opposedButIndividuallyCentered, 0).reason)
    // Aggregate fields cannot substitute for either missing eye direction.
    assertEquals("eye_direction_unavailable", stabilize(GazeClassifier(), neutral().copy(rightDirection = null), 0).reason)
  }

  @Test fun aSingleContradictionRequiresTwoFreshConfirmationsInEitherDirection() {
    val toward = neutral()
    val away = away()
    for ((first, contradiction) in listOf(toward to away, away to toward)) {
      val classifier = GazeClassifier()
      val stable = stabilize(classifier, first, 0).label
      assertEquals(AttentionLabel.UNKNOWN, classifier.classify(contradiction, 1000).label)
      assertEquals(AttentionLabel.UNKNOWN, classifier.classify(first, 1500).label)
      assertEquals(stable, classifier.classify(first, 2000).label)
    }
  }

  @Test fun alternatingFramesAndDuplicateOrReorderedTimestampsCannotReuseStableEvidence() {
    val classifier = GazeClassifier()
    stabilize(classifier, neutral(), 0)
    assertEquals("gaze_timestamp_invalid", classifier.classify(neutral(), 500).reason)
    assertEquals("gaze_timestamp_invalid", classifier.classify(neutral(), 400).reason)
    assertEquals(AttentionLabel.UNKNOWN, classifier.classify(neutral(), 1000).label)
    assertEquals(AttentionLabel.ATTENDED, classifier.classify(neutral(), 1500).label)
    for (i in 1..10) {
      val frame = if (i % 2 == 0) neutral() else away()
      assertEquals(AttentionLabel.UNKNOWN, classifier.classify(frame, 1500 + i * 500L).label)
    }
  }

  @Test fun invalidAndMissingEvidenceRequireFreshConfirmation() {
    for (invalid in listOf(neutral().copy(faceCount = 0), neutral().copy(blinkRight = 0.9f),
      neutral().copy(right = null), neutral().copy(lookVertical = Float.NaN),
      neutral().copy(leftDirection = null), neutral().copy(rightDirection = EyeDirection(Float.NaN, 0f)))) {
      val classifier = GazeClassifier()
      stabilize(classifier, neutral(), 0)
      assertEquals(AttentionLabel.UNKNOWN, classifier.classify(invalid, 1000).label)
      assertEquals(AttentionLabel.UNKNOWN, classifier.classify(neutral(), 1500).label)
      assertEquals(AttentionLabel.ATTENDED, classifier.classify(neutral(), 2000).label)
    }
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
    val frame = neutral().copy(left = EyeGaze(0.1f, 0.11f, 0.22f), right = EyeGaze(0.12f, 0.10f, 0.21f))
      .withDirections(EyeDirection(0.08f, 0.28f), EyeDirection(0.1f, 0.27f))
    assertEquals(AttentionLabel.ATTENDED, stabilize(GazeClassifier(), frame, 0).label)
  }

  @Test fun headTurnCannotIndependentlyEstablishAway() {
    assertEquals("head_pose_uncertain", stabilize(GazeClassifier(), neutral().copy(yaw = 0.55f), 0).reason)
    assertEquals("head_pose_uncertain", stabilize(GazeClassifier(), away().copy(pitch = 0.8f), 0).reason)
    assertEquals("eyes_looking_away", stabilize(GazeClassifier(), away(), 0).reason)
  }

  @Test fun compensatedModerateHeadTurnStaysUnknownAndDiagonalEyesDoNotLookCentered() {
    val compensated = neutral().copy(yaw = 0.3f, left = EyeGaze(-0.2f, 0.025f, 0.25f), right = EyeGaze(-0.2f, 0.025f, 0.25f))
    assertEquals("head_pose_uncertain", stabilize(GazeClassifier(), compensated, 0).reason)
    val diagonal = neutral().copy(left = EyeGaze(0.32f, 0.20f, 0.25f), right = EyeGaze(0.32f, 0.20f, 0.25f))
      .withDirections(EyeDirection(0.42f, 0.35f))
    assertEquals(AttentionLabel.PASSIVE, stabilize(GazeClassifier(), diagonal, 0).label)
  }

  @Test fun missedFramesResetEvidenceAndInvalidDataIsUnknown() {
    val classifier = GazeClassifier()
    stabilize(classifier, neutral(), 0)
    assertEquals(AttentionLabel.UNKNOWN, classifier.classify(neutral(), 4000).label)
    assertEquals(AttentionLabel.UNKNOWN, classifier.classify(neutral().copy(yaw = Float.NaN), 4500).label)
    assertEquals(AttentionLabel.UNKNOWN, classifier.classify(neutral().copy(leftDirection = null), 5000).label)
    assertEquals(AttentionLabel.UNKNOWN, classifier.classify(neutral().copy(faceCount = 2), 5500).label)
  }

  @Test fun geometryPreservesEyeDirectionDespiteOppositeCornerOrderAndRoll() {
    for (angle in listOf(0f, 0.3f, 1.5708f, 1.7f, -2f)) {
      fun point(x: Float, y: Float) = GazePoint(x * cos(angle) - y * sin(angle), x * sin(angle) + y * cos(angle))
      val a = point(0.2f, 0.3f); val b = point(0.3f, 0.3f)
      val iris = point(0.27f, 0.305f); val top = point(0.25f, 0.2875f); val bottom = point(0.25f, 0.3125f)
      val left = GazeGeometry.eye(iris, a, b, top, bottom)!!
      val right = GazeGeometry.eye(iris, b, a, top, bottom)!!
      assertEquals(0.4f, left.x, 0.001f)
      assertEquals(0.1f, left.y, 0.001f)
      assertEquals(left.x, right.x, 0.001f)
      assertEquals(left.y, right.y, 0.001f)
      assertNull(GazeGeometry.eye(iris.copy(x = Float.NaN), a, b, top, bottom))
    }
  }

  @Test fun fixedIrisDoesNotMoveWhenLidsMoveButOcclusionStillRejects() {
    val a = GazePoint(0.2f, 0.3f); val b = GazePoint(0.3f, 0.3f)
    val iris = GazePoint(0.265f, 0.302f)
    val bottom = GazePoint(0.25f, 0.3125f)
    val open = GazeGeometry.eye(iris, a, b, GazePoint(0.25f, 0.2875f), bottom)!!
    val narrowed = GazeGeometry.eye(iris, a, b, GazePoint(0.25f, 0.294f), bottom)!!
    assertEquals(0.3f, open.x, 0.001f)
    assertEquals(0.04f, open.y, 0.001f)
    assertEquals(open.x, narrowed.x, 0.001f)
    assertEquals(open.y, narrowed.y, 0.001f)
    assertTrue(narrowed.openness < open.openness)
    assertNull(GazeGeometry.eye(iris, a, b, GazePoint(0.25f, 0.309f), bottom))
    assertNull(GazeGeometry.eye(iris.copy(y = 0.34f), a, b, GazePoint(0.25f, 0.2875f), bottom))
  }

  private fun face(left: EyeDirection, right: EyeDirection, roll: Float = 0f): List<GazePoint> {
    val points = MutableList(478) { GazePoint(0.45f, 0.5f) }
    points[33] = GazePoint(0.25f, 0.35f); points[133] = GazePoint(0.35f, 0.35f)
    points[159] = GazePoint(0.30f, 0.3375f); points[145] = GazePoint(0.30f, 0.3625f)
    points[468] = GazePoint(0.30f + right.x * 0.05f, 0.35f + right.y * 0.05f)
    points[362] = GazePoint(0.55f, 0.35f); points[263] = GazePoint(0.65f, 0.35f)
    points[386] = GazePoint(0.60f, 0.3375f); points[374] = GazePoint(0.60f, 0.3625f)
    points[473] = GazePoint(0.60f + left.x * 0.05f, 0.35f + left.y * 0.05f)
    points[1] = GazePoint(0.45f, 0.45f); points[199] = GazePoint(0.45f, 0.60f)
    return points.map {
      val x = it.x - 0.45f; val y = it.y - 0.35f
      GazePoint(0.45f + x * cos(roll) - y * sin(roll), 0.35f + x * sin(roll) + y * cos(roll))
    }
  }

  private fun blends(left: EyeDirection, right: EyeDirection) = mapOf(
    "eyeLookInLeft" to maxOf(0f, -left.x), "eyeLookOutLeft" to maxOf(0f, left.x),
    "eyeLookInRight" to maxOf(0f, right.x), "eyeLookOutRight" to maxOf(0f, -right.x),
    "eyeLookUpLeft" to maxOf(0f, -left.y), "eyeLookDownLeft" to maxOf(0f, left.y),
    "eyeLookUpRight" to maxOf(0f, -right.y), "eyeLookDownRight" to maxOf(0f, right.y),
    "eyeBlinkLeft" to 0.12f, "eyeBlinkRight" to 0.34f)

  @Test fun fullFaceMapsSubjectEyesAndBlendAxesInAllFourDirections() {
    for (axis in listOf(EyeDirection(1f, 0f), EyeDirection(-1f, 0f), EyeDirection(0f, 1f), EyeDirection(0f, -1f))) {
      val left = EyeDirection(axis.x * 0.2f, axis.y * 0.2f)
      val right = EyeDirection(axis.x * 0.1f, axis.y * 0.1f)
      for (roll in listOf(0f, 0.3f, 1.7f, -2f)) {
        val frame = GazeGeometry.frame(face(left, right, roll), 1, blends(left, right))
        assertNull(GazeQuality.rejection(frame))
        assertEquals(left.x, frame.left!!.x, 0.001f)
        assertEquals(left.y, frame.left.y, 0.001f)
        assertEquals(right.x, frame.right!!.x, 0.001f)
        assertEquals(right.y, frame.right.y, 0.001f)
        assertEquals(left, frame.leftDirection)
        assertEquals(right, frame.rightDirection)
        assertEquals((left.x + right.x) / 2f, frame.lookHorizontal!!, 0.001f)
        assertEquals((left.y + right.y) / 2f, frame.lookVertical!!, 0.001f)
        assertEquals(0.12f, frame.blinkLeft!!, 0f)
        assertEquals(0.34f, frame.blinkRight!!, 0f)
        assertEquals(0.4f, frame.faceSpan!!, 0.001f)
        assertEquals(0.45f, frame.faceCenterX!!, 0.001f)
        assertEquals(0.35f, frame.faceCenterY!!, 0.001f)
        assertEquals(roll, frame.roll!!, 0.001f)
        assertEquals(0f, frame.yaw, 0.001f)
        assertEquals(0.4f, frame.pitch, 0.001f)
      }
    }
  }

  @Test fun missingOrInvalidRawScoresInvalidateThatEyeInsteadOfInventingNeutral() {
    val center = EyeDirection(0f, 0f)
    for (badValue in listOf(null, Float.NaN, -0.1f, 1.1f)) {
      val scores = blends(center, center).toMutableMap()
      if (badValue == null) scores.remove("eyeLookUpRight") else scores["eyeLookUpRight"] = badValue
      val frame = GazeGeometry.frame(face(center, center), 1, scores)
      assertEquals(center, frame.leftDirection)
      assertNull(frame.rightDirection)
      assertNull(frame.lookHorizontal)
      assertNull(frame.lookVertical)
      assertEquals("eye_direction_unavailable", GazeQuality.rejection(frame))
    }
  }

  @Test fun qualityRejectsInvalidMeasurementsWithoutDefiningPersonalDirectionOrPose() {
    val valid = neutral()
    val invalid = listOf(
      valid.copy(faceCount = 0) to "no_face_detected",
      valid.copy(faceCount = -1) to "invalid_face_count",
      valid.copy(faceCount = 2) to "multiple_faces",
      valid.copy(left = null) to "eyes_not_reliable",
      valid.copy(right = EyeGaze(Float.NaN, 0f, 0.25f)) to "invalid_landmarks",
      valid.copy(left = EyeGaze(1.2f, 0f, 0.25f)) to "invalid_landmarks",
      valid.copy(left = EyeGaze(0f, 1.2f, 0.25f)) to "invalid_landmarks",
      valid.copy(left = EyeGaze(0f, 0f, 1.1f)) to "invalid_landmarks",
      valid.copy(right = EyeGaze(0f, 0f, 0.09f)) to "eyes_closed_or_occluded",
      valid.copy(blinkRight = 0.9f) to "eyes_closed_or_occluded",
      valid.copy(blinkLeft = Float.NaN) to "invalid_eye_scores",
      valid.copy(blinkRight = -0.1f) to "invalid_eye_scores",
      valid.copy(leftDirection = null) to "eye_direction_unavailable",
      valid.copy(rightDirection = EyeDirection(Float.POSITIVE_INFINITY, 0f)) to "invalid_eye_direction",
      valid.copy(leftDirection = EyeDirection(0f, 1.1f)) to "invalid_eye_direction",
      valid.copy(pitch = Float.NaN) to "invalid_landmarks",
      valid.copy(faceSpan = 0.08f) to "invalid_face_geometry",
      valid.copy(faceCenterX = -0.1f) to "invalid_face_geometry",
      valid.copy(faceCenterY = Float.NaN) to "invalid_face_geometry",
      valid.copy(roll = 4f) to "invalid_face_geometry")
    for ((frame, reason) in invalid) assertEquals(reason, GazeQuality.rejection(frame))
    assertNull(GazeQuality.rejection(valid.copy(blinkLeft = null, blinkRight = null)))
    assertNull(GazeQuality.rejection(valid.copy(left = EyeGaze(-0.6f, 0.3f, 0.25f), right = EyeGaze(0.6f, -0.3f, 0.25f),
      yaw = 0.8f, pitch = 1.2f).withDirections(EyeDirection(-0.8f, 0.6f), EyeDirection(0.8f, -0.6f))))
  }

  @Test fun calibratedEvidenceRunsAfterQualityAndRetainsTemporalWithdrawal() {
    var calls = 0
    val classifier = GazeClassifier {
      calls++
      AttentionClassification(AttentionLabel.ATTENDED, 0.8f, "camera", "calibrated_test")
    }
    val offset = neutral().copy(left = EyeGaze(-0.6f, 0.3f, 0.25f), right = EyeGaze(0.6f, -0.3f, 0.25f), yaw = 0.8f, pitch = 1.2f)
      .withDirections(EyeDirection(-0.8f, 0.6f), EyeDirection(0.8f, -0.6f))
    assertEquals(AttentionLabel.UNKNOWN, classifier.classify(offset, 0).label)
    assertEquals("calibrated_test", classifier.classify(offset, 500).reason)
    assertEquals(2, calls)
    assertEquals("eyes_closed_or_occluded", classifier.classify(offset.copy(blinkLeft = 0.9f), 1000).reason)
    assertEquals(2, calls)
    assertEquals(AttentionLabel.UNKNOWN, classifier.classify(offset, 1500).label)
    assertEquals(AttentionLabel.ATTENDED, classifier.classify(offset, 2000).label)
    assertEquals(4, calls)
  }

  @Test fun runtimeNeverReturnsStaleOrStoppedGaze() {
    val previous = CameraAttentionRuntime.status
    try {
      val result = AttentionClassification(AttentionLabel.ATTENDED, 0.7f, "camera", "eyes_toward_screen")
      CameraAttentionRuntime.status = CameraRuntimeStatus(running = true, classification = result,
        observationElapsedMs = 100L, completionElapsedMs = 200L, validUntilElapsedMs = 2100L)
      assertEquals(result, CameraAttentionRuntime.currentClassification(500))
      assertNull(CameraAttentionRuntime.currentClassification(199))
      assertNull(CameraAttentionRuntime.currentClassification(2101))
      assertEquals("UNKNOWN", CameraAttentionRuntime.status.toMap(2101)["label"])
      assertEquals("gaze_unavailable", CameraAttentionRuntime.status.toMap(2101)["status"])
      assertEquals(9600L, CameraAttentionRuntime.event(500, 10000)["timestampMs"])
      assertEquals(11600L, CameraAttentionRuntime.event(500, 10000)["validUntilMs"])
      CameraAttentionRuntime.status = CameraAttentionRuntime.status.copy(running = false)
      assertNull(CameraAttentionRuntime.currentClassification(500))
    } finally { CameraAttentionRuntime.status = previous }
  }
}
