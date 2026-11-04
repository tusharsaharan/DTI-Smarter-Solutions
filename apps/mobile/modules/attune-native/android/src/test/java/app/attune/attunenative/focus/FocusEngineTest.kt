package app.attune.attunenative.focus

import app.attune.attunenative.context.ContextSnapshot
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class FocusEngineTest {
  private val now = Instant.parse("2026-11-02T09:05:00Z").toEpochMilli()
  private val entry = TimetableEntry("lecture", 1, 540, 600, "Lecture")
  private fun schedule(from: String = "2026-10-01", until: String = "2026-11-30") =
    FocusSchedule.create(listOf(entry), from, until, "UTC", "r1")
  private fun context(at: Long = now) = ContextSnapshot(at)

  @Test fun validityIsRequiredInclusiveAndAtMost180Days() {
    assertTrue(schedule().validAt(Instant.parse("2026-11-30T23:59:59Z").toEpochMilli()))
    assertFalse(schedule().validAt(Instant.parse("2026-12-01T00:00:00Z").toEpochMilli()))
    assertFalse(schedule().validAt(Instant.parse("2026-09-30T23:59:59Z").toEpochMilli()))
    FocusSchedule.create(listOf(entry), "2026-01-01", "2026-06-29", "UTC", "r")
    assertThrows(IllegalArgumentException::class.java) { FocusSchedule.create(listOf(entry), "2026-01-01", "2026-06-30", "UTC", "r") }
    assertThrows(IllegalArgumentException::class.java) { schedule("", "2026-11-30") }
    assertThrows(IllegalArgumentException::class.java) { schedule("2026-12-01", "2026-11-30") }
    assertThrows(IllegalArgumentException::class.java) { FocusSchedule.create(emptyList(), "2026-11-01", "2026-11-30", "UTC", "r") }
  }

  @Test fun reviewedTimezoneAndDateSpecificBoundsDetermineOccurrence() {
    val local = FocusSchedule.create(listOf(entry.copy(startDate = "2026-11-02", endDate = "2026-11-02")),
      "2026-11-01", "2026-11-30", "Asia/Kolkata", "r")
    assertNotNull(FocusTimetable.current(local, Instant.parse("2026-11-02T03:35:00Z").toEpochMilli()))
    assertNull(FocusTimetable.current(local, now))
    assertTrue(FocusTimetable.occurrences(local, LocalDate.parse("2026-11-09")).isEmpty())
  }

  @Test fun nextTimetableBoundaryIsRealFutureStartOrEndNotPolling() {
    val before = Instant.parse("2026-11-02T08:00:00Z").toEpochMilli()
    assertEquals(Instant.parse("2026-11-02T09:00:00Z").toEpochMilli(), FocusTimetable.nextBoundary(schedule(), before, false, "UTC"))
    assertEquals(Instant.parse("2026-11-02T10:00:00Z").toEpochMilli(), FocusTimetable.nextBoundary(schedule(), now, false, "UTC"))
    assertEquals(Instant.parse("2026-11-02T09:30:00Z").toEpochMilli(), FocusTimetable.nextBoundary(null, now, true, "UTC"))
    assertNull(FocusTimetable.nextBoundary(null, now, false, "UTC"))
  }

  @Test fun futureValidityDoesNotGenerateFutureTrainingLabels() {
    assertTrue(FocusLearning.priors(schedule("2026-11-03"), now).isEmpty())
    val rows = FocusLearning.priors(schedule(), now)
    assertTrue(rows.isNotEmpty())
    assertTrue(rows.size <= 128)
    assertTrue(rows.all { it.atMs <= now && it.source == FocusLabelSource.TIMETABLE_PRIOR })
    assertTrue(rows.any { it.focused })
    assertTrue(rows.any { !it.focused })
  }

  @Test fun expiresOrReplacedScheduleDropsPriorsButRetainsRecentExplicitFeedback() {
    val priors = FocusLearning.priors(schedule(), now)
    val choice = FocusSample("on", now, FocusFeatures.at(now, "UTC", true, context()), true, FocusLabelSource.USER_ON)
    assertEquals(listOf(choice), FocusLearning.usable(priors + choice, schedule().copy(revision = "new"), now))
    assertEquals(listOf(choice), FocusLearning.usable(priors + choice, schedule(until = "2026-11-01"), now))
  }

  @Test fun staleFutureMalformedAndDuplicateSamplesAreRejectedAndBounded() {
    val x = FocusFeatures.at(now, "UTC", false, context())
    val good = FocusSample("good", now, x, true, FocusLabelSource.USER_ON)
    val stale = good.copy(id = "old", atMs = now - FocusLearning.RETENTION_MS - 1)
    val future = good.copy(id = "future", atMs = now + 1)
    val invalid = good.copy(id = "invalid", features = FocusFeatures(listOf(Double.NaN)))
    assertEquals(listOf(good), FocusLearning.usable(listOf(stale, future, invalid, good, good), null, now))
    assertEquals(0L, FocusModel.train(listOf(stale, future, invalid), now).trainedAtMs)
    val choices = (1..300).map { good.copy(id = "c$it", atMs = now - it) }
    val bounded = FocusLearning.usable(FocusLearning.priors(schedule(), now) + choices, schedule(), now)
    assertEquals(256, bounded.size)
    assertTrue(bounded.all { it.source == FocusLabelSource.USER_ON })
  }

  @Test fun modelActuallyFitsBoundedStumpsAndReportsSources() {
    val rows = FocusLearning.priors(schedule(), now)
    val model = FocusModel.train(rows, now)
    assertEquals(24, model.trees.size)
    assertEquals(rows.size, model.priorCount)
    assertEquals(0, model.feedbackCount)
    val prediction = FocusPredictor.predict(schedule(), model, now, "UTC", context())
    assertNotNull("Reviewed occurrence should produce a fitted prior prediction", prediction)
    assertEquals("timetable_prior_local_gbdt", prediction!!.source)
    assertTrue(prediction.confidence <= 0.90)
    assertTrue(model.probability(FocusFeatures.at(now, "UTC", true, context())) >
      model.probability(FocusFeatures.at(now + 3 * 60 * 60_000L, "UTC", false, context(now + 3 * 60 * 60_000L))))
  }

  @Test fun oneNegativeUserChoiceOutweighsWeakTimetablePriors() {
    val rows = FocusLearning.priors(schedule(), now)
    val x = FocusFeatures.at(now, "UTC", true, context())
    val baseline = FocusModel.train(rows, now).probability(x)
    for (source in listOf(FocusLabelSource.USER_OFF, FocusLabelSource.USER_SKIP)) {
      val trained = FocusModel.train(rows + FocusSample("off", now, x, false, source), now)
      assertEquals(1, trained.feedbackCount)
      assertTrue("Explicit $source must lower prediction", trained.probability(x) < baseline - 0.2)
      assertTrue("Explicit $source should veto same-context automatic focus", trained.probability(x) < 0.72)
    }
  }

  @Test fun positiveFeedbackCanLearnTimeWithoutTimetableAndOffCanAdaptIt() {
    val x = FocusFeatures.at(now, "UTC", false, context())
    val rows = (1..4).map { FocusSample("on$it", now - it, x, true, FocusLabelSource.USER_ON) } +
      (1..4).map { FocusSample("off$it", now - it, FocusFeatures.at(now + 4 * 60 * 60_000L, "UTC", false, null), false, FocusLabelSource.USER_OFF) }
    val model = FocusModel.train(rows, now)
    val prediction = FocusPredictor.predict(null, model, now, "UTC", context())
    assertNotNull(prediction)
    assertEquals("personalized_time_local_gbdt", prediction!!.source)
    val negative = (1..5).map { FocusSample("veto$it", now, x, false, FocusLabelSource.USER_OFF) }
    assertTrue(FocusModel.train(rows + negative, now).probability(x) < model.probability(x))
  }

  @Test fun priorsCannotExtrapolateAutomationOutsideAnOccurrence() {
    val model = FocusModel.train(FocusLearning.priors(schedule(), now), now)
    val outside = now + 2 * 60 * 60_000L
    assertNull(FocusPredictor.predict(null, model.copy(intercept = 20.0, trees = emptyList()), outside, "UTC", context(outside)))
    assertNull(FocusPredictor.predict(schedule(), model.copy(intercept = 20.0, trees = emptyList()),
      Instant.parse("2026-12-01T09:05:00Z").toEpochMilli(), "UTC", context(Instant.parse("2026-12-01T09:05:00Z").toEpochMilli())))
  }

  @Test fun deniedCalendarFalseBusyStaleAndFutureContextAreNotFreshSignals() {
    assertEquals(0.0, FocusFeatures.at(now, "UTC", false, ContextSnapshot(now, calendarBusy = true)).values[9], 0.0)
    assertEquals(1.0, FocusFeatures.at(now, "UTC", false, ContextSnapshot(now, calendarBusy = true, calendarAccessGranted = true)).values[9], 0.0)
    assertEquals(0.0, FocusFeatures.at(now, "UTC", false, ContextSnapshot(now - 120_001, calendarBusy = true, calendarAccessGranted = true)).values[9], 0.0)
    assertEquals(0.0, FocusFeatures.at(now, "UTC", false, ContextSnapshot(now + 1, calendarBusy = true, calendarAccessGranted = true)).values[9], 0.0)
    val model = FocusModel.train(FocusLearning.priors(schedule(), now), now)
    assertNull(FocusPredictor.predict(schedule(), model, now, "UTC", context(now - 120_001)))
    assertNull(FocusPredictor.predict(schedule(), model, now, "UTC", context(now + 1)))
  }

  @Test fun emptyOrInvalidModelNeverInventsFocusAndSingleClassFitRemainsFinite() {
    assertNull(FocusPredictor.predict(schedule(), FocusModel.train(emptyList(), now), now, "UTC", context()))
    val x = FocusFeatures.at(now, "UTC", true, context())
    val model = FocusModel.train(listOf(FocusSample("a", now, x, true, FocusLabelSource.USER_ON)), now)
    assertTrue(model.intercept.isFinite())
    assertTrue(model.probability(x).isFinite())
    assertEquals(0.0, model.probability(FocusFeatures(emptyList())), 0.0)
  }

  @Test fun calendarPredictionUsesStable15MinuteOccurrenceAcrossWarningAndDeadline() {
    val busy = ContextSnapshot(now, calendarBusy = true, calendarAccessGranted = true)
    val x = FocusFeatures.at(now, "UTC", false, busy)
    val model = FocusModel.train((1..4).map { FocusSample("on$it", now - it, x, true, FocusLabelSource.USER_ON) }, now)
    val first = FocusPredictor.predict(null, model, now, "UTC", busy)!!
    val later = FocusPredictor.predict(null, model, now + 60_000, "UTC", busy.copy(capturedAtMs = now + 60_000))!!
    assertEquals("personalized_calendar_local_gbdt", first.source)
    assertEquals(Instant.parse("2026-11-02T09:15:00Z").toEpochMilli(), first.endAtMs)
    assertEquals(first.endAtMs, later.endAtMs)
    assertEquals(first.occurrenceKey, later.occurrenceKey)
    assertTrue(FocusCountdownSafety.canArm(first, now))
    assertEquals(FocusDeadline.APPLY, FocusCountdownSafety.check(FocusPending("p", now, 10_000, first, "r"),
      "p", now + 60_000, 70_000, true, true, true, "r", later))
  }

  @Test fun warningNearCalendarBinEndDoesNotResetTtlOrArmWithoutFullCountdown() {
    val at = Instant.parse("2026-11-02T09:13:30Z").toEpochMilli()
    val busy = ContextSnapshot(at, calendarBusy = true, calendarAccessGranted = true)
    val x = FocusFeatures.at(at, "UTC", false, busy)
    val model = FocusModel.train((1..4).map { FocusSample("on$it", at - it, x, true, FocusLabelSource.USER_ON) }, at)
    val first = FocusPredictor.predict(null, model, at, "UTC", busy)!!
    val later = FocusPredictor.predict(null, model, at + 60_000, "UTC", busy.copy(capturedAtMs = at + 60_000))!!
    assertTrue(FocusCountdownSafety.canArm(first, at))
    assertFalse(FocusCountdownSafety.canArm(later, at + 60_000))
    assertEquals(FocusDeadline.APPLY, FocusCountdownSafety.check(FocusPending("p", at, 10_000, first, "r"),
      "p", at + 60_000, 70_000, true, true, true, "r", later))
  }

  @Test fun onlyPositiveFeedbackCannotFabricateAnAllDayFocusRoutine() {
    val x = FocusFeatures.at(now, "UTC", false, context())
    val model = FocusModel.train((1..4).map { FocusSample("on$it", now - it, x, true, FocusLabelSource.USER_ON) }, now)
    assertNotNull(FocusPredictor.predict(null, model, now, "UTC", context()))
    val unsupported = now + 4 * 60 * 60_000L
    assertNull(FocusPredictor.predict(null, model, unsupported, "UTC", context(unsupported)))
    val otherDay = now + 24 * 60 * 60_000L
    assertNull(FocusPredictor.predict(null, model, otherDay, "UTC", context(otherDay)))
  }
}
