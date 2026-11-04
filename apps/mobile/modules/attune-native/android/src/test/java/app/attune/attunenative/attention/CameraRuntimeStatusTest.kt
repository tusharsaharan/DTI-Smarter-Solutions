package app.attune.attunenative.attention

import org.junit.Assert.*
import org.junit.Test

class CameraRuntimeStatusTest {
  private val previous = CameraRuntimeStatus(running = true,
    classification = AttentionClassification(AttentionLabel.ATTENDED, 0.8f, "camera", "eyes_toward_screen"),
    inferenceMs = 35, captureToResultMs = 50, observationElapsedMs = 100,
    completionElapsedMs = 150, validUntilElapsedMs = 2100, timestampBasis = "sensor_realtime")

  @Test fun expiredInferenceRetainsItsActualLatencyAndNeverPreviousClassification() {
    val time = CameraEvidenceTime(1000, 1010, 1_000_000_000, "sensor_realtime")
    val rejected = previous.rejected("gaze_inference_expired", time, 3200, 2100)
    assertEquals(2100L, rejected.inferenceMs)
    assertEquals(2200L, rejected.captureToResultMs)
    assertEquals(1000L, rejected.observationElapsedMs)
    assertEquals(3000L, rejected.validUntilElapsedMs)
    assertNull(rejected.freshClassification(3200))
    assertEquals("UNKNOWN", rejected.toMap(3200)["label"])
    assertEquals("gaze_inference_expired", rejected.toMap(3200)["reason"])
    assertEquals(1L, rejected.framesRejected)
  }

  @Test fun preInferenceRejectionAndStopClearSampleTimingAndLatency() {
    for (status in listOf(previous.rejected("camera_capture_mismatch"), previous.stopped())) {
      assertNull(status.classification)
      assertNull(status.inferenceMs)
      assertNull(status.captureToResultMs)
      assertNull(status.observationElapsedMs)
      assertNull(status.completionElapsedMs)
      assertNull(status.validUntilElapsedMs)
      assertNull(status.timestampBasis)
      assertEquals("UNKNOWN", status.toMap(200)["label"])
    }
    assertFalse(previous.stopped().running)
    assertEquals("camera_disconnected", previous.stopped("camera_disconnected").error)
  }

  @Test fun calibrationChangesInvalidateInFlightClassificationsAndDiagnosticsStayOutOfEvents() {
    val status = previous.copy(calibrationRevision = GazeCalibrationRuntime.revision - 1,
      gazeDiagnostics = mapOf("leftX" to .1f))
    assertNull(status.freshClassification(200))
    assertFalse(status.toMap(200).containsKey("gazeDiagnostics"))
    assertNull(status.toMap(200, diagnostics = true)["gazeDiagnostics"])
    assertNull(status.stopped().gazeDiagnostics)
    assertNull(status.rejected("camera_capture_expired").gazeDiagnostics)
  }
}
