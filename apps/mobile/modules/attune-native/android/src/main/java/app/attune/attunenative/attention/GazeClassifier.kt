package app.attune.attunenative.attention

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.PI

data class GazePoint(val x: Float, val y: Float, val z: Float = 0f)

/**
 * Subject-relative eye identity, with common roll-corrected image-right/down axes.
 * x/y are twice the iris displacement from the canthus midpoint divided by eye width.
 * Eyelid aperture affects openness and reliability only, never the coordinate scale.
 */
data class EyeGaze(val x: Float, val y: Float, val openness: Float)

/** Subject-relative eye blendshape differences, image-right/down positive when upright. */
data class EyeDirection(val x: Float, val y: Float)

data class GazeFrame(
  val faceCount: Int,
  val left: EyeGaze? = null,
  val right: EyeGaze? = null,
  val yaw: Float = 0f,
  val pitch: Float = 0.45f,
  val blinkLeft: Float? = null,
  val blinkRight: Float? = null,
  val lookHorizontal: Float? = null,
  val lookVertical: Float? = null,
  val leftDirection: EyeDirection? = null,
  val rightDirection: EyeDirection? = null,
  // Isotropic image units supplied by the caller; outer-canthus span and midpoint.
  val faceSpan: Float? = null,
  val faceCenterX: Float? = null,
  val faceCenterY: Float? = null,
  // Radians, atan2(subject-left outer canthus - subject-right outer canthus).
  val roll: Float? = null
)

object GazeGeometry {
  fun eye(iris: GazePoint, cornerA: GazePoint, cornerB: GazePoint, top: GazePoint, bottom: GazePoint): EyeGaze? {
    if (listOf(iris, cornerA, cornerB, top, bottom).any { !it.x.isFinite() || !it.y.isFinite() }) return null
    val width = hypot(cornerB.x - cornerA.x, cornerB.y - cornerA.y)
    if (!width.isFinite() || width < 0.018f) return null // Tiny/occluded eyes cannot justify a gaze claim.
    var ux = (cornerB.x - cornerA.x) / width
    var uy = (cornerB.y - cornerA.y) / width
    var aperture = (bottom.x - top.x) * -uy + (bottom.y - top.y) * ux
    // Anatomical upper/lower lids orient the basis, including roll beyond 90 degrees.
    // Sorting corners by image x would reverse the basis at that boundary.
    if (aperture < 0f) { ux = -ux; uy = -uy; aperture = -aperture }
    if (!aperture.isFinite() || aperture <= width * 0.06f || aperture > width) return null
    val dx = iris.x - (cornerA.x + cornerB.x) / 2f
    val dy = iris.y - (cornerA.y + cornerB.y) / 2f
    val x = (dx * ux + dy * uy) / width * 2f
    val y = (dx * -uy + dy * ux) / width * 2f
    val lidRelativeY = ((iris.x - top.x) * -uy + (iris.y - top.y) * ux) / aperture * 2f - 1f
    // Keep the iris/lid plausibility bound, but do not use it as a gaze coordinate.
    if (!x.isFinite() || !y.isFinite() || !lidRelativeY.isFinite() ||
      abs(x) > 1.15f || abs(y) > 1.15f || abs(lidRelativeY) > 1.5f) return null
    return EyeGaze(x, y, aperture / width)
  }

  fun frame(face: List<GazePoint>, faceCount: Int, blendshapes: Map<String, Float>): GazeFrame {
    if (faceCount != 1 || face.size < 478) return GazeFrame(faceCount)
    val l = eye(face[473], face[362], face[263], face[386], face[374])
    val r = eye(face[468], face[33], face[133], face[159], face[145])
    val span = hypot(face[263].x - face[33].x, face[263].y - face[33].y)
    if (!span.isFinite() || span < 0.09f) return GazeFrame(faceCount)
    val ux = (face[263].x - face[33].x) / span
    val uy = (face[263].y - face[33].y) / span
    val midX = (face[133].x + face[362].x) / 2f
    val midY = (face[133].y + face[362].y) / 2f
    val nx = face[1].x - midX
    val ny = face[1].y - midY
    val chin = (face[199].x - midX) * -uy + (face[199].y - midY) * ux
    val leftDirection = direction(blendshapes, "Left")
    val rightDirection = direction(blendshapes, "Right")
    return GazeFrame(
      faceCount, l, r,
      yaw = (nx * ux + ny * uy) / span,
      pitch = if (chin > 0.01f) (nx * -uy + ny * ux) / chin else Float.NaN,
      blinkLeft = blendshapes["eyeBlinkLeft"], blinkRight = blendshapes["eyeBlinkRight"],
      // Compatibility diagnostics only. Evidence must retain each eye separately.
      lookHorizontal = if (leftDirection != null && rightDirection != null) (leftDirection.x + rightDirection.x) / 2f else null,
      lookVertical = if (leftDirection != null && rightDirection != null) (leftDirection.y + rightDirection.y) / 2f else null,
      leftDirection = leftDirection, rightDirection = rightDirection,
      faceSpan = span,
      faceCenterX = (face[33].x + face[263].x) / 2f,
      faceCenterY = (face[33].y + face[263].y) / 2f,
      roll = atan2(uy, ux)
    )
  }

  private fun direction(blendshapes: Map<String, Float>, side: String): EyeDirection? {
    val names = listOf("eyeLookIn$side", "eyeLookOut$side", "eyeLookUp$side", "eyeLookDown$side")
    if (names.any { blendshapes[it]?.let { value -> value.isFinite() && value in 0f..1f } != true }) return null
    val inward = blendshapes.getValue(names[0])
    val outward = blendshapes.getValue(names[1])
    return EyeDirection(if (side == "Left") outward - inward else inward - outward,
      blendshapes.getValue(names[3]) - blendshapes.getValue(names[2]))
  }
}

/** Measurement validity only. Personal gaze offsets, vergence and pose belong to calibration. */
object GazeQuality {
  fun rejection(frame: GazeFrame): String? {
    if (frame.faceCount == 0) return "no_face_detected"
    if (frame.faceCount < 0) return "invalid_face_count"
    if (frame.faceCount != 1) return "multiple_faces"
    val l = frame.left ?: return "eyes_not_reliable"
    val r = frame.right ?: return "eyes_not_reliable"
    if (listOf(l.x, l.y, l.openness, r.x, r.y, r.openness, frame.yaw, frame.pitch).any { !it.isFinite() } ||
      listOf(l, r).any { abs(it.x) > 1.15f || abs(it.y) > 1.15f || it.openness !in 0f..1f }) return "invalid_landmarks"
    val blink = listOfNotNull(frame.blinkLeft, frame.blinkRight)
    if (blink.any { !it.isFinite() || it !in 0f..1f }) return "invalid_eye_scores"
    // Missing blink coefficients can use the measured aperture of both visible eyes.
    if (blink.any { it > 0.55f } || minOf(l.openness, r.openness) < 0.1f) return "eyes_closed_or_occluded"
    val lb = frame.leftDirection ?: return "eye_direction_unavailable"
    val rb = frame.rightDirection ?: return "eye_direction_unavailable"
    if ((listOf(lb.x, lb.y, rb.x, rb.y) + listOfNotNull(frame.lookHorizontal, frame.lookVertical))
        .any { !it.isFinite() || it !in -1f..1f }) return "invalid_eye_direction"
    if (frame.faceSpan?.let { !it.isFinite() || it < 0.09f || it > hypot(1f, 1f) } == true ||
      listOfNotNull(frame.faceCenterX, frame.faceCenterY).any { !it.isFinite() || it !in 0f..1f } ||
      frame.roll?.let { !it.isFinite() || abs(it) > PI.toFloat() } == true) return "invalid_face_geometry"
    return null
  }
}

/** Conservative heuristics over actual model output; confidence is evidence strength, not calibrated accuracy. */
class GazeClassifier(private val calibratedEvidence: ((GazeFrame) -> AttentionClassification)? = null) {
  companion object { const val VERSION = "gaze-heuristic-3" }
  private var lastTime: Long? = null
  private var candidate: AttentionLabel? = null
  private var candidateSince = 0L
  private var candidateFrames = 0
  private var stable: AttentionClassification? = null

  fun reset() { lastTime = null; candidate = null; candidateFrames = 0; stable = null }

  fun classify(frame: GazeFrame, elapsedMs: Long): AttentionClassification {
    val priorTime = lastTime
    if (elapsedMs < 0 || (priorTime != null && elapsedMs <= priorTime)) {
      candidate = null; candidateFrames = 0; stable = null
      return result(AttentionLabel.UNKNOWN, 0f, "gaze_timestamp_invalid")
    }
    if (priorTime != null && elapsedMs - priorTime > CameraEvidenceClock.MAX_AGE_MS) reset()
    lastTime = elapsedMs
    val evidence = evidence(frame)
    if (evidence.label == AttentionLabel.UNKNOWN) {
      candidate = null; candidateFrames = 0; stable = null
      return evidence
    }
    if (candidate != evidence.label) {
      stable = null
      candidate = evidence.label; candidateSince = elapsedMs; candidateFrames = 1
    } else candidateFrames = minOf(2, candidateFrames + 1)
    if (candidateFrames >= 2 && elapsedMs - candidateSince >= 400L) stable = evidence
    // A first contradictory frame immediately withdraws the old claim; never let smoothing
    // keep saying 'looking' after the eyes have moved away (or the reverse).
    return stable?.takeIf { it.label == evidence.label }
      ?: result(AttentionLabel.UNKNOWN, 0.25f, "gaze_transition")
  }

  private fun evidence(frame: GazeFrame): AttentionClassification {
    GazeQuality.rejection(frame)?.let { return result(AttentionLabel.UNKNOWN, 0f, it) }
    calibratedEvidence?.let { return it(frame) }
    val l = frame.left!!
    val r = frame.right!!
    val lb = frame.leftDirection!!
    val rb = frame.rightDirection!!
    // These deadbands ignore tiny neutral jitter, not meaningful opposing directions.
    if (opposed(l.x, r.x, 0.08f, 0.08f) || opposed(l.y, r.y, 0.04f, 0.04f))
      return result(AttentionLabel.UNKNOWN, 0.15f, "eyes_disagree")
    if (opposed(lb.x, rb.x, 0.08f, 0.08f) || opposed(lb.y, rb.y, 0.08f, 0.08f))
      return result(AttentionLabel.UNKNOWN, 0.15f, "eye_directions_disagree")
    if (listOf(l to lb, r to rb).any { (iris, blend) ->
        opposed(iris.x, blend.x, 0.08f, 0.08f) || opposed(iris.y, blend.y, 0.04f, 0.08f)
      }) return result(AttentionLabel.UNKNOWN, 0.15f, "eye_direction_conflict")

    // Width-normalized vertical thresholds are deliberately separate from blend coefficients.
    // All four observations must agree; pooled means are diagnostics, never evidence.
    val directions = listOf(EyeDirection(l.x / 0.43f, l.y / 0.24f), EyeDirection(r.x / 0.43f, r.y / 0.24f),
      EyeDirection(lb.x / 0.4f, lb.y / 0.47f), EyeDirection(rb.x / 0.4f, rb.y / 0.47f))
    val away = directions.map { it.x * it.x + it.y * it.y > 1f }
    if (away.any { it } && (!away.all { it } || !directions.indices.all { i ->
        (i + 1 until directions.size).all { j -> sameDirection(directions[i], directions[j]) }
      })) return result(AttentionLabel.UNKNOWN, 0.2f, "eye_direction_conflict")
    // Uncalibrated pose cannot establish away, or rule out eye compensation.
    if (abs(frame.yaw) >= 0.19f || frame.pitch !in 0.23f..0.67f)
      return result(AttentionLabel.UNKNOWN, 0.2f, "head_pose_uncertain")
    if (away.all { it }) return result(AttentionLabel.PASSIVE, 0.72f, "eyes_looking_away")
    val centered = listOf(l, r).all { ellipse(it.x, it.y, 0.34f, 0.19f) < 1f } &&
      listOf(lb, rb).all { ellipse(it.x, it.y, 0.30f, 0.36f) < 1f }
    return if (centered) result(AttentionLabel.ATTENDED, 0.72f, "eyes_toward_screen")
      else result(AttentionLabel.UNKNOWN, 0.25f, "gaze_near_boundary")
  }

  private fun opposed(a: Float, b: Float, aDeadband: Float, bDeadband: Float) =
    abs(a) > aDeadband && abs(b) > bDeadband && a * b < 0f
  private fun sameDirection(a: EyeDirection, b: EyeDirection) =
    a.x * b.x + a.y * b.y >= 0.7f * hypot(a.x, a.y) * hypot(b.x, b.y)
  private fun result(label: AttentionLabel, confidence: Float, reason: String) = AttentionClassification(label, confidence, "camera", reason)
  private fun ellipse(x: Float, y: Float, width: Float, height: Float) = (x / width) * (x / width) + (y / height) * (y / height)
}
