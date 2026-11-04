package app.attune.attunenative.focus

import app.attune.attunenative.context.ContextSnapshot
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class FocusDeletionSafetyTest {
  private val now = Instant.parse("2026-11-02T09:05:00Z").toEpochMilli()
  private val schedule = FocusSchedule.create(listOf(TimetableEntry("class", 1, 540, 600, "Class")),
    "2026-10-01", "2026-11-30", "UTC", "s")
  private val sample = FocusSample("on", now, FocusFeatures.at(now, "UTC", true, ContextSnapshot(now)), true, FocusLabelSource.USER_ON)

  @Test fun deletionDisablesAndRemovesTimetableSamplesModelAndReplayMarkers() {
    val state = FocusState(enabled = true, schedule = schedule, samples = listOf(sample),
      model = FocusModel.train(listOf(sample), now), suppressions = listOf(FocusSuppression("old", now + 10_000)),
      cooldownUntilMs = now + 30_000)
    val cleared = FocusDeletionSafety.reset(state, "new_rules", "UTC")
    assertFalse(cleared.enabled)
    assertNull(cleared.schedule)
    assertTrue(cleared.samples.isEmpty())
    assertTrue(cleared.model.trees.isEmpty())
    assertEquals(0, cleared.model.trainedAtMs)
    assertTrue(cleared.suppressions.isEmpty())
    assertEquals(0, cleared.cooldownUntilMs)
    assertEquals("new_rules", cleared.ruleRevision)
    assertTrue(FocusLearning.priors(cleared.schedule, now).isEmpty())
    assertNull(FocusTimetable.nextBoundary(cleared.schedule, now, false, cleared.timeZone))
  }

  @Test fun deletionKeepsAutomaticOwnershipEvidenceUntilRealRestorationSucceeds() {
    val own = FocusOwnedSession("automatic", "automatic", now + 60_000, now, snapshotAtMs = now + 1)
    val cleared = FocusDeletionSafety.reset(FocusState(enabled = true, owned = own), "new", "UTC")
    assertEquals(own, cleared.owned)
    assertTrue(FocusDeletionSafety.restoreAutomaticOnly(cleared.owned))
    assertFalse(FocusDeletionSafety.succeeded(true, cleared.owned))
    assertFalse(FocusDeletionSafety.succeeded(true, own.copy(phase = "restoring")))
    assertTrue(FocusDeletionSafety.succeeded(true, null))
  }

  @Test fun deletionPreservesManualSessionAndDoesNotRequestItsRestoration() {
    val manual = FocusOwnedSession("manual", "manual", 0, now, snapshotAtMs = now + 1)
    val cleared = FocusDeletionSafety.reset(FocusState(enabled = true, owned = manual), "new", "UTC")
    assertEquals(manual, cleared.owned)
    assertFalse(FocusDeletionSafety.restoreAutomaticOnly(cleared.owned))
    assertTrue(FocusDeletionSafety.succeeded(true, cleared.owned))
  }

  @Test fun deletionWriteFailureIsNeverReportedAsSuccess() {
    assertFalse(FocusDeletionSafety.succeeded(false, null))
    assertFalse(FocusDeletionSafety.succeeded(false, FocusOwnedSession("manual", "manual", 0, now)))
  }
}
