package app.attune.attunenative.attention

import kotlin.math.abs

/** Bounded, immutable summaries of explicitly labelled screen targets. No face mesh or samples. */
data class PersonalGazeProfile(
  val cameraKey: String,
  val createdAtMs: Long,
  val centers: List<Float>,
  val radii: List<Float>
) {
  fun valid(nowMs: Long): Boolean = cameraKey.isNotBlank() && createdAtMs > 0 &&
    nowMs >= createdAtMs && nowMs - createdAtMs <= MAX_AGE_MS &&
    centers.size == DIMENSIONS && radii.size == DIMENSIONS &&
    centers.all { it.isFinite() } && radii.indices.all { radii[it].isFinite() && radii[it] in MIN_RADII[it]..MAX_RADII[it] } &&
    centers.take(8).all { abs(it) <= 1.5f } && centers[10] in 0.09f..1f

  fun classify(frame: GazeFrame): AttentionClassification {
    GazeQuality.rejection(frame)?.let { return result(AttentionLabel.UNKNOWN, it) }
    val values = features(frame) ?: return result(AttentionLabel.UNKNOWN, "calibration_position_unavailable")
    val d = values.indices.map { (values[it] - centers[it]) / radii[it] }
    // A new posture/distance/orientation is not evidence of looking away.
    if ((8..13).any { abs(d[it]) > 1f }) return result(AttentionLabel.UNKNOWN, "outside_calibrated_position")
    if ((0..7).all { abs(d[it]) <= 1f }) {
      if ((14 until DIMENSIONS).any { abs(d[it]) > 1f }) return result(AttentionLabel.UNKNOWN, "calibrated_eyes_disagree")
      return result(AttentionLabel.ATTENDED, "calibrated_eyes_toward_screen")
    }
    // Require both irises AND the corresponding per-eye blend directions to agree.
    // These are correlated model outputs, not independent votes or calibrated probability.
    val away = listOf(listOf(0, 2, 4, 6), listOf(1, 3, 5, 7)).any { axis ->
      axis.all { d[it] > 1.35f } || axis.all { d[it] < -1.35f }
    }
    return if (away) result(AttentionLabel.PASSIVE, "calibrated_eyes_looking_away")
      else result(AttentionLabel.UNKNOWN, "calibrated_eye_direction_uncertain")
  }

  companion object {
    const val DIMENSIONS = 22
    const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
    // x/y iris coordinates, x/y per-eye blends, yaw/pitch, face span, centre x/y, roll.
    private val MIN_RADII = listOf(.075f, .045f, .075f, .045f, .07f, .07f, .07f, .07f, .10f, .10f, .04f, .10f, .10f, .18f,
      .08f, .06f, .10f, .10f, .12f, .12f, .12f, .12f)
    private val MAX_RADII = listOf(.45f, .30f, .45f, .30f, .45f, .45f, .45f, .45f, .16f, .16f, .18f, .16f, .16f, .30f,
      .22f, .18f, .30f, .30f, .40f, .40f, .40f, .40f)

    fun features(frame: GazeFrame): List<Float>? {
      val l = frame.left ?: return null
      val r = frame.right ?: return null
      val lb = frame.leftDirection ?: return null
      val rb = frame.rightDirection ?: return null
      return listOf(l.x, l.y, r.x, r.y, lb.x, lb.y, rb.x, rb.y, frame.yaw, frame.pitch,
        frame.faceSpan ?: return null, frame.faceCenterX ?: return null,
        frame.faceCenterY ?: return null, frame.roll ?: return null,
        l.x - r.x, l.y - r.y, lb.x - rb.x, lb.y - rb.y,
        l.x - lb.x, l.y - lb.y, r.x - rb.x, r.y - rb.y).takeIf { values -> values.all { it.isFinite() } }
    }

    /** Fit exactly five screen targets. Validation observations never enter this function. */
    fun fit(targets: List<List<GazeFrame>>, cameraKey: String, nowMs: Long): PersonalGazeProfile? {
      if (targets.size != 5 || targets.any { it.size !in 6..24 }) return null
      val batches = targets.map { frames -> frames.map { frame ->
        if (GazeQuality.rejection(frame) != null) return null
        features(frame) ?: return null
      } }
      val centers = mutableListOf<Float>()
      val radii = mutableListOf<Float>()
      for (i in 0 until DIMENSIONS) {
        val medians = batches.map { quantile(it.map { f -> f[i] }, .5f) }
        val lo = batches.minOf { quantile(it.map { f -> f[i] }, .1f) }
        val hi = batches.maxOf { quantile(it.map { f -> f[i] }, .9f) }
        val center = (lo + hi) / 2f
        // Limited noise allowance, never widen the envelope to make a negative check pass.
        val padding = if (i in 8..13) MIN_RADII[i] else MIN_RADII[i] * .5f
        val radius = maxOf(MIN_RADII[i], (hi - lo) / 2f + padding)
        if (radius > MAX_RADII[i]) return null
        if (batches.any { batch ->
          val v = batch.map { it[i] }
          quantile(v, .9f) - quantile(v, .1f) > MAX_RADII[i]
        }) return null
        if (i in 8..13 && medians.max() - medians.min() > MIN_RADII[i]) return null
        centers += center
        radii += if (i == 10) maxOf(MIN_RADII[i], minOf(radius, center * .25f)) else radius
      }
      return PersonalGazeProfile(cameraKey, nowMs, centers.toList(), radii.toList()).takeIf { it.valid(nowMs) }
    }

    private fun quantile(values: List<Float>, q: Float): Float {
      val ordered = values.sorted()
      return ordered[((ordered.size - 1) * q).toInt()]
    }

    private fun result(label: AttentionLabel, reason: String) =
      AttentionClassification(label, if (label == AttentionLabel.UNKNOWN) .15f else .72f, "camera", reason)
  }
}
