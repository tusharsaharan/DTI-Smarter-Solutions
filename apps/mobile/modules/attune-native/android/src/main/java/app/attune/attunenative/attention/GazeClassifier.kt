package app.attune.attunenative.attention

import kotlin.math.abs
import kotlin.math.hypot

data class GazePoint(val x: Float, val y: Float, val z: Float = 0f)

/** Eye-local coordinates: both eyes use the same image-space axis, even under head roll. */
data class EyeGaze(val x: Float, val y: Float, val openness: Float)

data class GazeFrame(
  val faceCount: Int,
  val left: EyeGaze? = null,
  val right: EyeGaze? = null,
  val yaw: Float = 0f,
  val pitch: Float = 0.45f,
  val blinkLeft: Float? = null,
  val blinkRight: Float? = null,
  val lookHorizontal: Float? = null,
  val lookVertical: Float? = null
)

object GazeGeometry {
  fun eye(iris: GazePoint, cornerA: GazePoint, cornerB: GazePoint, top: GazePoint, bottom: GazePoint): EyeGaze? {
    if (listOf(iris, cornerA, cornerB, top, bottom).any { !it.x.isFinite() || !it.y.isFinite() }) return null
    val a = if (cornerA.x <= cornerB.x) cornerA else cornerB
    val b = if (cornerA.x <= cornerB.x) cornerB else cornerA
    val width = hypot(b.x - a.x, b.y - a.y)
    if (width < 0.018f) return null // Tiny/occluded eyes cannot justify a gaze claim.
    val ux = (b.x - a.x) / width
    val uy = (b.y - a.y) / width
    val aperture = (bottom.x - top.x) * -uy + (bottom.y - top.y) * ux
    if (aperture <= width * 0.06f) return null
    val x = ((iris.x - a.x) * ux + (iris.y - a.y) * uy) / width * 2f - 1f
    val y = ((iris.x - top.x) * -uy + (iris.y - top.y) * ux) / aperture * 2f - 1f
    if (!x.isFinite() || !y.isFinite() || abs(x) > 1.15f || abs(y) > 1.5f) return null
    return EyeGaze(x, y, aperture / width)
  }

  fun frame(face: List<GazePoint>, faceCount: Int, blendshapes: Map<String, Float>): GazeFrame {
    if (faceCount != 1 || face.size < 478) return GazeFrame(faceCount)
    val l = eye(face[468], face[33], face[133], face[159], face[145])
    val r = eye(face[473], face[362], face[263], face[386], face[374])
    val span = hypot(face[263].x - face[33].x, face[263].y - face[33].y)
    if (!span.isFinite() || span < 0.09f) return GazeFrame(faceCount)
    val ux = (face[263].x - face[33].x) / span
    val uy = (face[263].y - face[33].y) / span
    val midX = (face[133].x + face[362].x) / 2f
    val midY = (face[133].y + face[362].y) / 2f
    val nx = face[1].x - midX
    val ny = face[1].y - midY
    val chin = (face[199].x - midX) * -uy + (face[199].y - midY) * ux
    val required = listOf("eyeLookInLeft", "eyeLookOutLeft", "eyeLookInRight", "eyeLookOutRight", "eyeLookUpLeft", "eyeLookUpRight", "eyeLookDownLeft", "eyeLookDownRight")
    val validBlend = required.all { blendshapes[it]?.let { value -> value.isFinite() && value in 0f..1f } == true }
    return GazeFrame(
      faceCount, l, r,
      yaw = (nx * ux + ny * uy) / span,
      pitch = if (chin > 0.01f) (nx * -uy + ny * ux) / chin else Float.NaN,
      blinkLeft = blendshapes["eyeBlinkLeft"], blinkRight = blendshapes["eyeBlinkRight"],
      lookHorizontal = if (validBlend) ((blendshapes.getValue("eyeLookInLeft") + blendshapes.getValue("eyeLookOutRight")) - (blendshapes.getValue("eyeLookOutLeft") + blendshapes.getValue("eyeLookInRight"))) / 2f else null,
      // Blendshape vertical is up-positive; iris vertical is down-positive. Only magnitudes are compared.
      lookVertical = if (validBlend) ((blendshapes.getValue("eyeLookUpLeft") + blendshapes.getValue("eyeLookUpRight")) - (blendshapes.getValue("eyeLookDownLeft") + blendshapes.getValue("eyeLookDownRight"))) / 2f else null
    )
  }
}

/** Conservative heuristics over actual model output; confidence is evidence strength, not calibrated accuracy. */
class GazeClassifier {
  private var lastTime: Long? = null
  private var candidate: AttentionLabel? = null
  private var candidateSince = 0L
  private var candidateFrames = 0
  private var stable: AttentionClassification? = null

  fun reset() { lastTime = null; candidate = null; candidateFrames = 0; stable = null }

  fun classify(frame: GazeFrame, elapsedMs: Long): AttentionClassification {
    val priorTime = lastTime
    if (priorTime != null && (elapsedMs <= priorTime || elapsedMs - priorTime > 2_000L)) reset()
    lastTime = elapsedMs
    val evidence = evidence(frame)
    if (evidence.label == AttentionLabel.UNKNOWN) {
      candidate = null; candidateFrames = 0; stable = null
      return evidence
    }
    if (candidate != evidence.label) {
      candidate = evidence.label; candidateSince = elapsedMs; candidateFrames = 1
    } else candidateFrames += 1
    if (candidateFrames >= 2 && elapsedMs - candidateSince >= 400L) stable = evidence
    // A first contradictory frame immediately withdraws the old claim; never let smoothing
    // keep saying 'looking' after the eyes have moved away (or the reverse).
    return stable?.takeIf { it.label == evidence.label }
      ?: result(AttentionLabel.UNKNOWN, 0.25f, "gaze_transition")
  }

  private fun evidence(frame: GazeFrame): AttentionClassification {
    if (frame.faceCount == 0) return result(AttentionLabel.UNKNOWN, 0.1f, "no_face_detected")
    if (frame.faceCount != 1) return result(AttentionLabel.UNKNOWN, 0.1f, "multiple_faces")
    val l = frame.left ?: return result(AttentionLabel.UNKNOWN, 0.1f, "eyes_not_reliable")
    val r = frame.right ?: return result(AttentionLabel.UNKNOWN, 0.1f, "eyes_not_reliable")
    if (listOf(l.x, l.y, l.openness, r.x, r.y, r.openness, frame.yaw, frame.pitch).any { !it.isFinite() }) return result(AttentionLabel.UNKNOWN, 0f, "invalid_landmarks")
    val blink = listOfNotNull(frame.blinkLeft, frame.blinkRight)
    if (blink.any { !it.isFinite() || it !in 0f..1f }) return result(AttentionLabel.UNKNOWN, 0f, "invalid_eye_scores")
    if (blink.any { it > 0.55f } || minOf(l.openness, r.openness) < 0.1f) return result(AttentionLabel.UNKNOWN, 0.15f, "eyes_closed_or_occluded")
    // Eye disagreement usually means occlusion, reflection, or tracking jitter, not centered gaze.
    if (abs(l.x - r.x) > 0.45f || abs(l.y - r.y) > 0.85f) return result(AttentionLabel.UNKNOWN, 0.15f, "eyes_disagree")
    val bx = frame.lookHorizontal
    val by = frame.lookVertical
    if (bx == null || by == null || !bx.isFinite() || !by.isFinite()) return result(AttentionLabel.UNKNOWN, 0.1f, "eye_direction_unavailable")
    val gx = (l.x + r.x) / 2f
    val gy = (l.y + r.y) / 2f
    val turned = abs(frame.yaw) > 0.24f || frame.pitch !in 0.18f..0.72f
    val compensating = frame.yaw * gx < -0.025f || (frame.pitch !in 0.23f..0.67f && (abs(gy) > 0.25f || abs(by) > 0.15f))
    // Never average iris away with a neutral head pose; eye-only looks matter independently.
    val eyeAway = ellipse(gx, gy, 0.43f, 0.95f) > 1f || ellipse(bx, by, 0.4f, 0.47f) > 1f
    if (compensating && (turned || eyeAway)) return result(AttentionLabel.UNKNOWN, 0.2f, "head_eye_direction_conflict")
    if (abs(frame.yaw) > 0.5f || frame.pitch !in 0.06f..0.88f) return result(AttentionLabel.PASSIVE, 0.65f, "head_turned_away")
    // Without personal calibration a moderate head turn can be compensated by the eyes.
    // Require eye evidence too; a head-pose threshold alone would reject screen reading.
    if (turned && !eyeAway) return result(AttentionLabel.UNKNOWN, 0.2f, "head_pose_uncertain")
    if (eyeAway) return result(AttentionLabel.PASSIVE, 0.72f, "eyes_looking_away")
    val centered = ellipse(gx, gy, 0.34f, 0.75f) < 1f && ellipse(bx, by, 0.30f, 0.36f) < 1f && abs(frame.yaw) < 0.19f && frame.pitch in 0.23f..0.67f
    return if (centered) result(AttentionLabel.ATTENDED, 0.72f, "eyes_toward_screen") else result(AttentionLabel.UNKNOWN, 0.25f, "gaze_near_boundary")
  }

  private fun result(label: AttentionLabel, confidence: Float, reason: String) = AttentionClassification(label, confidence, "camera", reason)
  private fun ellipse(x: Float, y: Float, width: Float, height: Float) = (x / width) * (x / width) + (y / height) * (y / height)
}
