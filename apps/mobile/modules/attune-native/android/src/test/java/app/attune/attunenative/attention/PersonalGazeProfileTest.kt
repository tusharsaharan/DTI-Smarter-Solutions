package app.attune.attunenative.attention

import org.junit.Assert.*
import org.junit.Test

internal fun readingFrame(dx: Float = 0f, dy: Float = 0f): GazeFrame = GazeFrame(
  1, EyeGaze(-.08f + dx, .12f + dy, .25f), EyeGaze(.08f + dx, .10f + dy, .25f),
  yaw = 0f, pitch = .25f, blinkLeft = .1f, blinkRight = .1f,
  lookHorizontal = dx, lookVertical = .65f + dy,
  leftDirection = EyeDirection(-.03f + dx, .65f + dy), rightDirection = EyeDirection(.03f + dx, .65f + dy),
  faceSpan = .35f, faceCenterX = .5f, faceCenterY = .4f, roll = 0f
)

internal fun screenTargets(): List<List<GazeFrame>> = listOf(0f to 0f, -.06f to -.04f, .06f to -.04f, -.06f to .04f, .06f to .04f)
  .map { (x, y) -> List(10) { readingFrame(x + (it % 3 - 1) * .002f, y) } }

class PersonalGazeProfileTest {
  private fun profile() = PersonalGazeProfile.fit(screenTargets(), "front:270", 1000L)!!

  @Test fun calibratesDownwardReadingBaselineAndNaturalVergenceWithoutWideningOtherDirections() {
    val p = profile()
    for (frame in screenTargets().flatten()) assertEquals(AttentionLabel.ATTENDED, p.classify(frame).label)
    assertEquals(AttentionLabel.PASSIVE, p.classify(readingFrame(dx = -.4f)).label)
    assertEquals(AttentionLabel.PASSIVE, p.classify(readingFrame(dx = .4f)).label)
    assertEquals(AttentionLabel.PASSIVE, p.classify(readingFrame(dy = -.4f)).label)
    assertEquals(AttentionLabel.PASSIVE, p.classify(readingFrame(dy = .3f)).label)
  }

  @Test fun singleChannelAndOpposingChannelEvidenceAbstain() {
    val p = profile()
    val f = readingFrame()
    assertEquals(AttentionLabel.UNKNOWN, p.classify(f.copy(leftDirection = EyeDirection(.7f, .65f), rightDirection = EyeDirection(.7f, .65f))).label)
    assertEquals(AttentionLabel.UNKNOWN, p.classify(readingFrame(dx = -.4f).copy(leftDirection = EyeDirection(.7f, .65f), rightDirection = EyeDirection(.7f, .65f))).label)
    assertEquals(AttentionLabel.UNKNOWN, p.classify(f.copy(left = EyeGaze(.7f, .12f, .25f))).label)
  }

  @Test fun opposingEyesInsideIndependentRangesStillAbstainRelativeToPersonalVergence() {
    val f = readingFrame()
    val contradictory = f.copy(left = f.left!!.copy(x = f.left.x + .09f), right = f.right!!.copy(x = f.right.x - .09f),
      leftDirection = f.leftDirection!!.copy(x = f.leftDirection.x + .09f), rightDirection = f.rightDirection!!.copy(x = f.rightDirection.x - .09f))
    assertEquals("calibrated_eyes_disagree", profile().classify(contradictory).reason)
    assertEquals(AttentionLabel.ATTENDED, profile().classify(f).label)
  }

  @Test fun newPhonePositionAndMissingContextCannotReuseCalibration() {
    val p = profile()
    for (f in listOf(readingFrame().copy(pitch = .5f), readingFrame().copy(faceSpan = .6f),
      readingFrame().copy(faceCenterX = .8f), readingFrame().copy(roll = .5f), readingFrame().copy(faceSpan = null))) {
      assertEquals(AttentionLabel.UNKNOWN, p.classify(f).label)
    }
  }

  @Test fun blinkOcclusionAndMultipleFacesCannotBecomeCalibratedLooking() {
    val p = profile()
    for (f in listOf(readingFrame().copy(blinkLeft = .9f), readingFrame().copy(right = null),
      readingFrame().copy(faceCount = 2), readingFrame().copy(leftDirection = EyeDirection(Float.NaN, 0f)))) {
      assertEquals(AttentionLabel.UNKNOWN, p.classify(f).label)
    }
  }

  @Test fun boundedFitRejectsIncompleteNoisyAndHeadMovedTargets() {
    assertNull(PersonalGazeProfile.fit(screenTargets().take(4), "front", 1000))
    assertNull(PersonalGazeProfile.fit(screenTargets().map { it.take(5) }, "front", 1000))
    assertNull(PersonalGazeProfile.fit(screenTargets().map { frames -> frames.mapIndexed { i, f -> f.copy(yaw = if (i % 2 == 0) .5f else -.5f) } }, "front", 1000))
    val moved = screenTargets().toMutableList().apply { this[4] = this[4].map { it.copy(pitch = .55f) } }
    assertNull(PersonalGazeProfile.fit(moved, "front", 1000))
  }

  @Test fun corruptExpiredAndFutureProfilesAreRejected() {
    val p = profile()
    assertTrue(p.valid(1000))
    assertFalse(p.valid(999))
    assertFalse(p.valid(1001 + PersonalGazeProfile.MAX_AGE_MS))
    assertFalse(p.copy(radii = List(PersonalGazeProfile.DIMENSIONS) { 100f }).valid(1000))
    assertFalse(p.copy(centers = List(PersonalGazeProfile.DIMENSIONS) { Float.NaN }).valid(1000))
    assertFalse(p.copy(centers = emptyList()).valid(1000))
  }

  @Test fun normalClassificationNeverLearnsFromItsOwnPredictions() {
    val p = profile()
    val before = p.copy(centers = p.centers.toList(), radii = p.radii.toList())
    repeat(200) { p.classify(readingFrame(dx = .6f)) }
    assertEquals(before, p)
  }
}
