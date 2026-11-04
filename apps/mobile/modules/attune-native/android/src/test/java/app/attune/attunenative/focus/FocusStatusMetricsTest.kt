package app.attune.attunenative.focus

import org.junit.Assert.*
import org.junit.Test

class FocusStatusMetricsTest {
  @Test fun missingPredictionDoesNotInventZeroProbabilityOrConfidence() {
    val metrics = FocusStatusMetrics.from(null)
    assertTrue(metrics.containsKey("probability"))
    assertTrue(metrics.containsKey("confidence"))
    assertNull(metrics["probability"])
    assertNull(metrics["confidence"])
  }

  @Test fun actualPredictionKeepsItsNumericEstimatedMetrics() {
    val prediction = FocusPrediction("k", "reason", "personalized_local_gbdt", 0.65, 0.84, 100_000,
      FocusFeatures(List(FocusFeatures.SIZE) { 0.0 }))
    val metrics = FocusStatusMetrics.from(prediction)
    assertEquals(0.84, metrics["probability"] as Double, 0.0)
    assertEquals(0.65, metrics["confidence"] as Double, 0.0)
  }
}
