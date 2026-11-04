package app.attune.attunenative.attention

import kotlin.math.sqrt

/** Reduced device motion only. These signals cannot determine where somebody is looking. */
data class AttentionMotionSnapshot(
  val screenOn: Boolean = true,
  val sampledAtElapsedMs: Long = 0L,
  val reliable: Boolean = false,
  val fresh: Boolean = false,
  val faceDown: Boolean = false,
  val proximityNear: Boolean = false,
  val still: Boolean = false,
  val moving: Boolean = false,
  val heldOrientation: Boolean = false
) {
  fun toMap(): Map<String, Any?> = mapOf(
    "screenOn" to screenOn,
    "sampledAtElapsedMs" to sampledAtElapsedMs,
    "reliable" to reliable,
    "fresh" to fresh,
    "faceDown" to faceDown,
    "proximityNear" to proximityNear,
    "still" to still,
    "moving" to moving,
    "heldOrientation" to heldOrientation,
    "limitation" to "motion_estimate_not_gaze"
  )
}

/** Bounded 3-second sensor reducer; timestamps are monotonic, independent of wall clock changes. */
class AttentionMotionReducer {
  private data class Acceleration(val at: Long, val x: Float, val y: Float, val z: Float)
  private val acceleration = ArrayDeque<Acceleration>()
  private var gyroAt = Long.MIN_VALUE
  private var rotationRate = 0f
  private var nearSince: Long? = null
  private var proximityAt = Long.MIN_VALUE
  private var faceDownSince: Long? = null

  fun reset() {
    acceleration.clear()
    gyroAt = Long.MIN_VALUE
    rotationRate = 0f
    nearSince = null
    proximityAt = Long.MIN_VALUE
    faceDownSince = null
  }

  fun accelerometer(atElapsedMs: Long, x: Float, y: Float, z: Float) {
    if (atElapsedMs < 0L || !x.isFinite() || !y.isFinite() || !z.isFinite()) return
    val last = acceleration.lastOrNull()
    if (last != null && atElapsedMs <= last.at) return
    if (last != null && atElapsedMs - last.at > FRESHNESS_MS) reset()
    acceleration.addLast(Acceleration(atElapsedMs, x, y, z))
    while (acceleration.size > 24 || (acceleration.firstOrNull()?.at ?: atElapsedMs) < atElapsedMs - WINDOW_MS) {
      acceleration.removeFirst()
    }
    // Screen normal points out of +Z. Persist the condition before calling it face-down.
    if (z < -7.5f && kotlin.math.abs(x) < 5f && kotlin.math.abs(y) < 5f) {
      if (faceDownSince == null) faceDownSince = atElapsedMs
    } else faceDownSince = null
  }

  fun gyroscope(atElapsedMs: Long, x: Float, y: Float, z: Float) {
    if (atElapsedMs < 0L || atElapsedMs <= gyroAt || !x.isFinite() || !y.isFinite() || !z.isFinite()) return
    gyroAt = atElapsedMs
    rotationRate = sqrt(x * x + y * y + z * z)
  }

  fun proximity(atElapsedMs: Long, near: Boolean) {
    if (atElapsedMs < 0L || atElapsedMs < proximityAt) return
    proximityAt = atElapsedMs
    if (near) {
      if (nearSince == null) nearSince = atElapsedMs
    } else nearSince = null
  }

  fun snapshot(nowElapsedMs: Long, screenOn: Boolean): AttentionMotionSnapshot {
    val last = acceleration.lastOrNull()
    val fresh = last != null && nowElapsedMs >= last.at && nowElapsedMs - last.at <= FRESHNESS_MS
    val reliable = fresh && acceleration.size >= 8 && last!!.at - acceleration.first().at >= 1_200L
    if (!screenOn || !reliable) return AttentionMotionSnapshot(
      screenOn = screenOn, sampledAtElapsedMs = last?.at ?: 0L, reliable = false, fresh = fresh
    )
    val meanX = acceleration.map { it.x }.average()
    val meanY = acceleration.map { it.y }.average()
    val meanZ = acceleration.map { it.z }.average()
    val variance = acceleration.sumOf {
      val dx = it.x - meanX
      val dy = it.y - meanY
      val dz = it.z - meanZ
      dx * dx + dy * dy + dz * dz
    } / acceleration.size
    val gyroFresh = nowElapsedMs >= gyroAt && gyroAt >= 0L && nowElapsedMs - gyroAt <= FRESHNESS_MS
    val still = variance < 0.16 && (!gyroFresh || rotationRate < 0.12f)
    return AttentionMotionSnapshot(
      screenOn = true,
      sampledAtElapsedMs = last!!.at,
      reliable = true,
      fresh = true,
      faceDown = still && (faceDownSince?.let { nowElapsedMs - it >= 1_200L } == true),
      // Proximity is an on-change sensor; no events while unchanged is normal. Reset on suspend.
      proximityNear = nearSince?.let { nowElapsedMs >= it && nowElapsedMs - it >= 1_000L } == true,
      still = still,
      moving = variance > 0.64 || (gyroFresh && rotationRate > 0.3f),
      heldOrientation = kotlin.math.abs(meanZ) < 7.5
    )
  }

  companion object {
    const val FRESHNESS_MS = 2_000L
    private const val WINDOW_MS = 3_000L
  }
}

class SensorAttentionClassifier {
  /** recentInteraction must come from an actual, fresh touch/typing event; motion is not interaction. */
  fun classify(
    snapshot: AttentionMotionSnapshot,
    recentInteraction: Boolean = false,
    usageAccessGranted: Boolean = true,
    foregroundMs: Long = 0L
  ): AttentionClassification {
    if (!snapshot.screenOn) return AttentionClassification(AttentionLabel.NOT_IN_USE, 0.99f, "screen", "screen_off")
    if (!snapshot.reliable || !snapshot.fresh) {
      return AttentionClassification(AttentionLabel.UNKNOWN, 0f, "sensor_fusion", "motion_unavailable_or_stale")
    }
    if (snapshot.faceDown) return AttentionClassification(AttentionLabel.PASSIVE, 0.8f, "sensor_fusion", "face_down_estimate_not_gaze")
    if (snapshot.proximityNear) return AttentionClassification(AttentionLabel.PASSIVE, 0.65f, "sensor_fusion", "covered_screen_estimate_not_gaze")
    if (recentInteraction) return AttentionClassification(AttentionLabel.ATTENDED, 0.45f, "sensor_fusion", "recent_interaction_estimate_not_gaze")
    if (!usageAccessGranted) return AttentionClassification(AttentionLabel.UNKNOWN, 0.15f, "sensor_fusion", "motion_only_usage_access_required")
    // A still phone can be used for reading/video; a moving phone can be carried unseen.
    val reason = when {
      snapshot.moving -> "phone_moving_attention_unknown"
      snapshot.still -> "phone_still_attention_unknown"
      else -> "motion_estimate_not_gaze"
    }
    return AttentionClassification(
      if (foregroundMs > 0L) AttentionLabel.USAGE_ONLY else AttentionLabel.UNKNOWN,
      0.25f, "sensor_fusion", reason
    )
  }
}
