package app.attune.attunenative.attention

import org.junit.Assert.*
import org.junit.Test

class CameraEvidenceClockTest {
  @Test fun realtimeUsesExposureRatherThanDeliveryTimeAndIncludesInferenceInExpiry() {
    val time = CameraEvidenceClock.resolve(1000, 1400, 1_100_000_000, 1_100_000_000, true).time!!
    assertEquals(1100L, time.observedElapsedMs)
    assertEquals(3100L, time.validUntilElapsedMs)
    assertTrue(time.isFresh(3100))
    assertFalse(time.isFresh(3101))
    assertFalse(time.isFresh(1399))
  }

  @Test fun unknownSensorClockUsesRequestBoundAndNeverPretendsCallbackTimeIsCaptureTime() {
    val time = CameraEvidenceClock.resolve(1000, 1400, 98_000_000_000, 98_000_000_000, false).time!!
    assertEquals(1000L, time.observedElapsedMs)
    assertEquals("capture_request_bound", time.basis)
    assertFalse(time.isFresh(3001))
  }

  @Test fun oldRequestsMismatchedImagesAndFutureOrPreRequestTimestampsAreRejected() {
    assertEquals("camera_capture_expired", CameraEvidenceClock.resolve(1000, 3001, 2_000_000_000, 2_000_000_000, true).rejection)
    assertEquals("camera_capture_mismatch", CameraEvidenceClock.resolve(1000, 1400, 1_100_000_000, 1_200_000_000, true).rejection)
    assertEquals("camera_clock_mismatch", CameraEvidenceClock.resolve(1000, 1400, 1_500_000_000, 1_500_000_000, true).rejection)
    assertEquals("camera_clock_mismatch", CameraEvidenceClock.resolve(1000, 1400, 900_000_000, 900_000_000, true).rejection)
  }

  @Test fun duplicateMillisecondAndOutOfOrderImagesCannotReachVideoInference() {
    for (previous in listOf(1_100_000_001L, 1_200_000_000L)) {
      assertEquals("camera_timestamp_out_of_order", CameraEvidenceClock.resolve(
        1000, 1400, 1_100_000_002, 1_100_000_002, true, previous
      ).rejection)
    }
  }

  @Test fun invalidElapsedClockAndMissingSensorTimestampFailClosed() {
    assertNull(CameraEvidenceClock.resolve(-1, 100, 100, 100, false).time)
    assertNull(CameraEvidenceClock.resolve(1000, 999, 100, 100, false).time)
    assertNull(CameraEvidenceClock.resolve(1000, Long.MAX_VALUE, 100, 100, false).time)
    assertNull(CameraEvidenceClock.resolve(1000, 1100, 0, 0, false).time)
  }
}
