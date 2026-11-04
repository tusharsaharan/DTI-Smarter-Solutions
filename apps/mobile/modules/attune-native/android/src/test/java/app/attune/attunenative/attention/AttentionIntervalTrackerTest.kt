package app.attune.attunenative.attention

import org.junit.Assert.*
import org.junit.Test

class AttentionIntervalTrackerTest {
  private val attended = AttentionClassification(AttentionLabel.ATTENDED, .8f, "camera", "eyes_on_screen")
  private fun tick(time: Long, classification: AttentionClassification = attended, foreground: Boolean = true, day: Long = 0L) =
    AttentionTick(time, time, day, foreground, classification)

  @Test fun onlyConsecutiveForegroundSamplesAccumulate() {
    val tracker = AttentionIntervalTracker()
    assertNull(tracker.advance(tick(0)))
    assertEquals(1000L, tracker.advance(tick(1000))!!.durationMs)
    assertNull(tracker.advance(tick(2000, foreground = false)))
    assertNull(tracker.advance(tick(3000)))
    assertEquals(1000L, tracker.advance(tick(4000))!!.durationMs)
  }

  @Test fun neverExtrapolatesAcrossMissingFramesOrServiceRestarts() {
    val tracker = AttentionIntervalTracker()
    tracker.advance(tick(0))
    assertNull(tracker.advance(tick(10000)))
    tracker.reset()
    assertNull(tracker.advance(tick(11000)))
  }

  @Test fun lookingAwayTransitionIsUnknownInsteadOfAttended() {
    val tracker = AttentionIntervalTracker()
    tracker.advance(tick(0))
    val away = attended.copy(label = AttentionLabel.PASSIVE)
    assertEquals(AttentionLabel.UNKNOWN, tracker.advance(tick(1000, away))!!.classification.label)
    assertEquals(AttentionLabel.PASSIVE, tracker.advance(tick(2000, away))!!.classification.label)
  }

  @Test fun rejectsClockJumpsAndMidnightBoundaries() {
    val tracker = AttentionIntervalTracker()
    tracker.advance(tick(0))
    assertNull(tracker.advance(tick(1000).copy(wallMs = 5000)))
    assertNull(tracker.advance(tick(2000, day = 2000)))
  }
}
