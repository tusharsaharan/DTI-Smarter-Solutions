package app.attune.attunenative.attention

/** Capture timestamps use the sensor clock only when Camera2 declares REALTIME. */
data class CameraEvidenceTime(
  val observedElapsedMs: Long,
  val receivedElapsedMs: Long,
  val imageTimestampNs: Long,
  val basis: String
) {
  val validUntilElapsedMs: Long get() = observedElapsedMs + CameraEvidenceClock.MAX_AGE_MS

  fun isFresh(nowElapsedMs: Long): Boolean = nowElapsedMs >= receivedElapsedMs &&
    nowElapsedMs >= observedElapsedMs && nowElapsedMs - observedElapsedMs <= CameraEvidenceClock.MAX_AGE_MS
}

data class CameraTimeResult(val time: CameraEvidenceTime? = null, val rejection: String? = null)

object CameraEvidenceClock {
  const val MAX_AGE_MS = 2_000L

  /**
   * Each image must match the timestamp of the one outstanding capture. UNKNOWN sensor
   * clocks are not offset-calibrated from callback delivery: that would hide queue latency.
   * The request's elapsed time is instead a conservative lower bound on exposure time.
   */
  fun resolve(
    requestedElapsedMs: Long,
    receivedElapsedMs: Long,
    captureTimestampNs: Long,
    imageTimestampNs: Long,
    realtimeSensorClock: Boolean,
    previousImageTimestampNs: Long? = null
  ): CameraTimeResult {
    fun reject(reason: String) = CameraTimeResult(rejection = reason)
    if (requestedElapsedMs < 0 || receivedElapsedMs < requestedElapsedMs ||
      receivedElapsedMs > Long.MAX_VALUE - MAX_AGE_MS) return reject("camera_clock_invalid")
    if (captureTimestampNs <= 0 || captureTimestampNs != imageTimestampNs) return reject("camera_capture_mismatch")
    if (previousImageTimestampNs != null && imageTimestampNs / 1_000_000L <= previousImageTimestampNs / 1_000_000L) {
      return reject("camera_timestamp_out_of_order")
    }
    if (receivedElapsedMs - requestedElapsedMs > MAX_AGE_MS) return reject("camera_capture_expired")
    val observed = if (realtimeSensorClock) imageTimestampNs / 1_000_000L else requestedElapsedMs
    if (observed < requestedElapsedMs || observed > receivedElapsedMs) return reject("camera_clock_mismatch")
    return CameraTimeResult(time = CameraEvidenceTime(
      observed, receivedElapsedMs, imageTimestampNs,
      if (realtimeSensorClock) "sensor_realtime" else "capture_request_bound"
    ))
  }
}
