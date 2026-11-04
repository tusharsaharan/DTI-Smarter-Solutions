package app.attune.attunenative.focus

import app.attune.attunenative.context.ContextSnapshot
import org.junit.Assert.*
import org.junit.Test

class FocusCountdownSafetyTest {
  private val now = 1_793_610_000_000L
  private val elapsed = 5_000_000L
  private val features = FocusFeatures.at(now, "UTC", true, ContextSnapshot(now))
  private val prediction = FocusPrediction("occurrence", "reviewed_timetable_prediction", "timetable_prior_local_gbdt",
    0.7, 0.9, now + 3_600_000L, features)
  private val pending = FocusPending("token", now, elapsed, prediction, "rules")

  private fun check(delay: Long = 60_000L, token: String = "token", enabled: Boolean = true,
    visible: Boolean = true, access: Boolean = true, revision: String = "rules",
    current: FocusPrediction? = prediction, wallOffset: Long = 0, elapsedOffset: Long = 0): FocusDeadline =
    FocusCountdownSafety.check(pending, token, now + delay + wallOffset, elapsed + delay + elapsedOffset,
      enabled, visible, access, revision, current)

  @Test fun full60SecondMonotonicWarningIsRequired() {
    assertEquals(60_000L, pending.deadlineAtMs - pending.createdAtMs)
    assertEquals(60_000L, pending.deadlineElapsedMs - pending.createdElapsedMs)
    assertEquals(FocusDeadline.WAIT, check(59_999))
    assertEquals(FocusDeadline.APPLY, check(60_000))
    assertEquals(FocusDeadline.APPLY, check(75_000))
    assertEquals(FocusDeadline.DROP, check(75_001))
  }

  @Test fun staleInexactDeadlineDropsRatherThanStartingSilentDnd() {
    assertEquals(FocusDeadline.DROP, check(600_000))
    assertEquals(FocusDeadline.DROP, check(3_600_001))
  }

  @Test fun permissionsBlockedWarningAndDismissedNotificationFailClosed() {
    assertEquals(FocusDeadline.DROP, check(access = false))
    assertEquals(FocusDeadline.DROP, check(visible = false))
    assertEquals(FocusDeadline.DROP, check(enabled = false))
  }

  @Test fun anotherTokenCannotSkipOrApplyAndProcessRestartHasNoPendingToSkip() {
    assertFalse(FocusCountdownSafety.canSkip(pending, "older-token", now + 10_000, elapsed + 10_000))
    assertFalse(FocusCountdownSafety.canSkip(null, "token", now + 10_000, elapsed + 10_000))
    assertEquals(FocusDeadline.DROP, check(token = "older-token"))
    assertTrue(FocusCountdownSafety.canSkip(pending, "token", now + 10_000, elapsed + 10_000))
  }

  @Test fun changedRulesOccurrenceSourceOrFreshEvidenceInvalidatesCountdown() {
    assertEquals(FocusDeadline.DROP, check(revision = "changed"))
    assertEquals(FocusDeadline.DROP, check(current = prediction.copy(occurrenceKey = "other")))
    assertEquals(FocusDeadline.DROP, check(current = prediction.copy(source = "personalized_calendar_local_gbdt")))
    assertEquals(FocusDeadline.DROP, check(current = prediction.copy(features = features.copy(values = features.values.toMutableList().apply { this[9] = 1.0 }))))
    assertEquals(FocusDeadline.DROP, check(current = null))
    assertEquals(FocusDeadline.DROP, check(current = prediction.copy(endAtMs = prediction.endAtMs + 60_000)))
  }

  @Test fun clockJumpsFutureCreatedTimeAndRebootElapsedRejectAction() {
    assertEquals(FocusDeadline.DROP, check(wallOffset = 3_000))
    assertEquals(FocusDeadline.DROP, check(wallOffset = -3_000))
    assertEquals(FocusDeadline.DROP, check(elapsedOffset = 3_000))
    assertEquals(FocusDeadline.DROP, check(delay = -1))
    assertEquals(FocusDeadline.DROP, check(elapsedOffset = -elapsed))
    assertFalse(FocusCountdownSafety.canSkip(pending, "token", now - 1, elapsed + 1))
    assertFalse(FocusCountdownSafety.canSkip(pending, "token", now + 10_000, elapsed + 13_000))
  }

  @Test fun staleSkipIsIgnoredRatherThanTrainingNewContext() {
    assertFalse(FocusCountdownSafety.canSkip(pending, "token", now + 75_001, elapsed + 75_001))
    assertFalse(FocusCountdownSafety.canSkip(pending, "token", now + 10_000, elapsed - 1))
  }

  @Test fun probabilityRefreshDoesNotResetOrReplaceSameCountdown() {
    val refreshed = prediction.copy(probability = 0.89, confidence = 0.69)
    assertEquals(FocusDeadline.WAIT, check(delay = 30_000, current = refreshed))
    assertEquals(FocusDeadline.APPLY, check(delay = 60_000, current = refreshed))
    assertEquals(now + 60_000, pending.deadlineAtMs)
  }

  @Test fun postingLatencyCannotCountAsVisibleCountdown() {
    val posting = pending.copy(confirmed = false)
    assertEquals(FocusDeadline.WAIT, FocusCountdownSafety.check(posting, "token", now + 60_000, elapsed + 60_000,
      true, true, true, "rules", prediction))
    val confirmed = posting.copy(createdAtMs = now + 10_000, createdElapsedMs = elapsed + 10_000, confirmed = true)
    assertEquals(FocusDeadline.WAIT, FocusCountdownSafety.check(confirmed, "token", now + 69_999, elapsed + 69_999,
      true, true, true, "rules", prediction))
    assertEquals(FocusDeadline.APPLY, FocusCountdownSafety.check(confirmed, "token", now + 70_000, elapsed + 70_000,
      true, true, true, "rules", prediction))
  }

  @Test fun decisionMustBeRecheckedAfterSlowPreparation() {
    assertEquals(FocusDeadline.APPLY, check(60_000))
    assertEquals(FocusDeadline.DROP, check(60_000 + 20_000))
  }
}
