package app.attune.attunenative.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollegeNotificationContextTest {
  @Test fun confirmedCollegeUpdateReachesTheRealAdaptationPolicyWithoutAModelOrKnownSender() {
    val rules = refine("Aaj class nahi hai")
    val meaning = NotificationSemanticFusion.combine(rules, null)
    val score = NotificationScorer().score(NotificationSignal(SenderTier.UNKNOWN, meaning.urgency,
      NotificationFeedback.MARKED_NOT_IMPORTANT, meaning))
    assertTrue(score.points >= NotificationAdaptationPolicy.HIGH_SCORE_THRESHOLD)
    val allowed = NotificationAdaptationInput(score.points, false, false, true, true, true, !meaning.abstain)
    assertEquals(NotificationAdaptationAction.REPOST_ATTUNE, NotificationAdaptationPolicy.decide(allowed).action)
    assertEquals(NotificationAdaptationAction.KEEP_ORIGINAL,
      NotificationAdaptationPolicy.decide(allowed.copy(canPostAttuneNotification = false)).action)
    assertEquals(NotificationAdaptationAction.KEEP_ORIGINAL,
      NotificationAdaptationPolicy.decide(allowed.copy(hasOpenOriginalFallback = false)).action)
  }

  private fun refine(text: String): NotificationMeaningEvidence =
    CollegeNotificationContext.refine(text, NotificationMeaningAnalyzer.analyze(text))

  private fun assertSchedule(text: String, reason: String) {
    val result = refine(text)
    assertEquals(text, NotificationIntent.TIME_SENSITIVE, result.intent)
    assertFalse(text, result.abstain)
    assertTrue(text, result.urgency.timeSensitive)
    assertEquals("context_college", result.source)
    assertFalse(result.canRefineWithModel)
    assertTrue(text, reason in result.reasonCodes)
  }

  @Test
  fun englishUpcomingCancellationsRemainImportantScheduleUpdates() {
    listOf(
      "Tomorrow's exam is cancelled.",
      "Today's viva has been cancelled.",
      "The practical exam tomorrow is canceled.",
      "Today's lecture was cancelled."
    ).forEach { assertSchedule(it, "college_current_schedule_cancellation") }
  }

  @Test
  fun hinglishUpcomingCancellationsRemainImportantScheduleUpdates() {
    listOf(
      "Aaj viva 9 baje cancel hai",
      "Kal exam cancel hai",
      "Aaj practical cancel ho gaya hai",
      "Kal lecture cancel hogi"
    ).forEach { assertSchedule(it, "college_current_schedule_cancellation") }
  }

  @Test
  fun currentVenueTimeAndRescheduleChangesAreImportant() {
    listOf(
      "Today's exam room has changed to room 204.",
      "Viva tomorrow has been moved from 9am to 10am.",
      "Aaj lab ka room badal gaya hai, 204 mein aao.",
      "Tomorrow's lecture is rescheduled to 11am."
    ).forEach { assertSchedule(it, "college_current_schedule_change") }
  }

  @Test
  fun upcomingAssertedAcademicSchedulesHaveCurrentEvidence() {
    listOf(
      "Exam tomorrow.",
      "Class starts today at 9am.",
      "Aaj viva 9 baje hai",
      "Kal maths class 9 baje hogi",
      "Lecture will be held next Monday at 10am.",
      "Attendance is at 9am tomorrow."
    ).forEach { assertSchedule(it, "college_current_schedule") }
  }

  @Test
  fun newUpcomingRegistrationAndSubmissionDeadlinesAreImportant() {
    listOf(
      "Registration closes today at 5pm.",
      "The assignment deadline is tomorrow at 9am.",
      "Submit the assignment before 9am tomorrow."
    ).forEach {
      val result = refine(it)
      assertEquals(it, NotificationIntent.TIME_SENSITIVE, result.intent)
      assertTrue(it, result.urgency.deadline)
      assertTrue(it, result.urgency.timeSensitive)
      assertFalse(it, result.abstain)
    }
  }

  @Test
  fun narrowNoEventGrammarMayCrossOnlyTheNegationScopeGuard() {
    listOf("No class today", "Aaj class nahi hai", "Kal maths class nahi hogi", "No lecture tomorrow.")
      .forEach {
        assertSchedule(it, "college_current_schedule_cancellation")
        assertTrue("college_explicit_no_event" in refine(it).reasonCodes)
      }
  }

  @Test
  fun undatedDefiniteChangesAreKeptWithoutRealertPermission() {
    listOf("Exam cancelled.", "Viva room changed to room 204.", "Class rescheduled to 11am.")
      .forEach {
        val result = refine(it)
        assertEquals(it, NotificationIntent.UNCERTAIN, result.intent)
        assertTrue(it, result.abstain)
        assertFalse(it, result.urgency.timeSensitive)
        assertFalse(it, result.canRefineWithModel)
        assertTrue(it, "college_schedule_change_date_needed" in result.reasonCodes)
      }
  }

  @Test
  fun academicMaterialsMemesAndPastedReferencesDoNotBecomeScheduleFacts() {
    listOf(
      "Exam notes for tomorrow at 9am.", "Old exam papers from last year.",
      "A meme about tomorrow's viva at 9am.", "Example: class starts tomorrow at 9am.",
      "Pasted reference: exam tomorrow at 9am.", "Readings for tomorrow's lecture.",
      "Sample exam paper for today's class.", "I hate the exam tomorrow."
    ).forEach { text ->
      val base = NotificationMeaningAnalyzer.analyze(text)
      assertEquals(text, base, CollegeNotificationContext.refine(text, base))
    }
  }

  @Test
  fun flexibleDeadlinesDoNotBecomeNewInterruptions() {
    listOf(
      "Assignment deadline is tomorrow, but no rush.",
      "Exam registration deadline is flexible, register whenever you can.",
      "Aaj assignment aaram se submit karo, not urgent."
    ).forEach { text ->
      val base = NotificationMeaningAnalyzer.analyze(text)
      assertEquals(text, base, CollegeNotificationContext.refine(text, base))
    }
  }

  @Test
  fun questionsConditionsRumoursAndQuotedDatesCannotOverrideScope() {
    listOf(
      "Is the exam tomorrow at 9am?", "If class is cancelled today, call me.",
      "Shayad aaj viva 9 baje cancel hai", "Someone said class is cancelled tomorrow.",
      "Forwarded: Exam tomorrow at 9am.", "\"Exam tomorrow at 9am\"",
      "> No class today", "Agar kal class nahi hogi, call karo."
    ).forEach { text ->
      val base = NotificationMeaningAnalyzer.analyze(text)
      assertEquals(text, base, CollegeNotificationContext.refine(text, base))
    }
  }

  @Test
  fun pastAndAmbiguousKalDoNotBecomeUpcomingSchedules() {
    listOf(
      "Kal class nahi thi", "Kal viva 9 baje tha", "Yesterday's exam was cancelled.",
      "Class was moved last week.", "Today's viva already finished at 9am.",
      "Kal exam 9 baje", "Exam was at 9am today."
    ).forEach { text ->
      val result = refine(text)
      assertFalse(text, result.source == "context_college" && !result.abstain)
    }
  }

  @Test
  fun doubleNegativesAndClassRepresentativeStatementsAreNotNoEventFacts() {
    listOf(
      "Class is not cancelled today.", "Aaj class cancel nahi hai.",
      "Aaj class nahi nahi hai", "No class rep has announced the exam today.",
      "No class has been announced for tomorrow.", "It is not true that there is no class today.",
      "No class today unless the teacher arrives."
    ).forEach { text ->
      val base = NotificationMeaningAnalyzer.analyze(text)
      assertEquals(text, base, CollegeNotificationContext.refine(text, base))
    }
  }

  @Test
  fun everyOtherGuardWinsEvenForAnOtherwiseClearCollegeCancellation() {
    val reasons = listOf(
      "missing_message_content", "message_exceeds_analysis_window", "forwarded_context_unverified",
      "quoted_context_unverified", "hypothetical_context_unverified", "ambiguous_reference",
      "thread_context_needed", "mixed_promotion_and_personal_context", "past_or_negated_urgency",
      "conflicting_action_status", "language_requires_semantic_model"
    )
    reasons.forEach { reason ->
      val base = NotificationMeaningEvidence(NotificationIntent.UNCERTAIN, UrgencySignals(), 0.2f, true, listOf(reason))
      assertEquals(reason, base, CollegeNotificationContext.refine("No class today", base))
      assertEquals(reason, base, CollegeNotificationContext.refine("Aaj viva 9 baje cancel hai", base))
    }
  }

  @Test
  fun promotionsKeepTheirEvidenceAcrossAcademicDeadlinesAndScheduleChanges() {
    val promotion = NotificationMeaningEvidence(
      NotificationIntent.PROMOTION, UrgencySignals(directMention = true), 0.8f, false,
      listOf("upstream_promotion"), canRefineWithModel = false
    )
    listOf(
      "College registration closes today at 9am.",
      "Viva tomorrow has been moved from 9am to 10am.",
      "Aaj viva 9 baje cancel hai",
      "Aaj class nahi hai",
      "Class has been cancelled."
    ).forEach { text ->
      assertEquals(text, promotion, CollegeNotificationContext.refine(text, promotion))
    }
  }

  @Test
  fun safetyPromotionsAndUnsupportedLanguagesRemainWithTheirOriginalEvidence() {
    val safety = NotificationMeaningAnalyzer.analyze("I cannot breathe. Class is cancelled today.")
    assertEquals(safety, CollegeNotificationContext.refine("I cannot breathe. Class is cancelled today.", safety))
    val promotion = NotificationMeaningEvidence(NotificationIntent.PROMOTION, UrgencySignals(), 0.8f, false, listOf("commercial_solicitation"))
    assertEquals(promotion, CollegeNotificationContext.refine("College registration closes today at 9am.", promotion))
    val hindi = "आज viva 9 baje cancel hai"
    val base = NotificationMeaningAnalyzer.analyze(hindi)
    assertEquals(base, CollegeNotificationContext.refine(hindi, base))
  }
}
