package app.attune.attunenative.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationSemanticsTest {
  @Test fun standaloneUrgencyPrefixModifiesOnlyAnActiveNonflexibleRequest() {
    assertTrue(NotificationMeaningAnalyzer.analyze("Urgent, call me now").urgency.explicitUrgency)
    assertTrue(NotificationMeaningAnalyzer.analyze("Urgent, call me now").urgency.callbackRequest)
    assertEquals(0, NotificationMeaningAnalyzer.analyze("Urgent, exam is cancelled").urgency.score)
    assertFalse(NotificationMeaningAnalyzer.analyze("Urgent, call whenever you have time, no hurry").urgency.explicitUrgency)
  }
  private fun analyze(text: String, vararg prior: String) = NotificationMeaningAnalyzer.analyze(text, prior.toList())

  @Test fun currentRequestCombinesIntentWithTimeInsteadOfCountingWords() {
    val evidence = analyze("Urgent: call me now")
    assertEquals(NotificationIntent.ACTION_REQUIRED, evidence.intent)
    assertFalse(evidence.abstain)
    assertTrue(evidence.urgency.explicitUrgency)
    assertTrue(evidence.urgency.callbackRequest)
    assertTrue(evidence.urgency.timeSensitive)
    assertEquals("context_rules", evidence.source)
  }

  @Test fun directEmergencyWithoutAnUrgencyKeywordIsStillProtected() {
    listOf(
      "I cannot breathe", "He isn't breathing", "Dad has collapsed outside the door",
      "The building is on fire", "Someone is following me", "Papa behosh ho gaye ambulance bulao abhi",
      "Saans nahi aa rahi jaldi aao"
    ).forEach { message ->
      val evidence = analyze(message)
      assertEquals(message, NotificationIntent.SAFETY_CRITICAL, evidence.intent)
      assertFalse(message, evidence.abstain)
      assertTrue(message, evidence.urgency.explicitUrgency)
      assertFalse(message, evidence.canRefineWithModel)
    }
  }

  @Test fun NegatingASafetyStateDoesNotAssertAnEmergency() {
    listOf("I am not unconscious", "Please don't call ambulance", "I cannot attend the meeting", "I don't need an ambulance").forEach { message ->
      val evidence = analyze(message)
      assertTrue(message, evidence.intent != NotificationIntent.SAFETY_CRITICAL)
      assertEquals(message, 0, evidence.urgency.score)
      assertTrue(message, evidence.abstain)
      assertFalse(message, evidence.canRefineWithModel)
    }
  }

  @Test fun negationCancellationAndResolutionDoNotContributeUrgency() {
    listOf(
      "No emergency", "Not urgent", "Meeting is cancelled", "Emergency resolved everyone safe",
      "Kal wala test cancel ho gaya", "Assignment already submitted", "We had an emergency yesterday"
    ).forEach { message ->
      val evidence = analyze(message)
      assertEquals(message, 0, evidence.urgency.score)
      assertFalse(message, evidence.canRefineWithModel)
      assertTrue(message, evidence.intent in setOf(NotificationIntent.CANCELLED_OR_RESOLVED, NotificationIntent.INFORMATIONAL))
    }
  }

  @Test fun aCancellationBeingDeniedIsNotTreatedAsACancellation() {
    val evidence = analyze("The meeting is not cancelled")
    assertEquals(NotificationIntent.UNCERTAIN, evidence.intent)
    assertTrue(evidence.abstain)
    assertFalse(evidence.canRefineWithModel)
  }

  @Test fun urgencyNegatedInOneClauseDoesNotSuppressASeparateImmediateRequest() {
    val evidence = analyze("There is no emergency, but please call me now")
    assertEquals(NotificationIntent.ACTION_REQUIRED, evidence.intent)
    assertFalse(evidence.urgency.explicitUrgency)
    assertTrue(evidence.urgency.callbackRequest)
    assertTrue(evidence.urgency.timeSensitive)
    assertFalse(evidence.abstain)
    assertFalse(evidence.canRefineWithModel)
  }

  @Test fun aResolvedMatterDoesNotEraseAnIndependentCurrentNeed() {
    val evidence = analyze("No need to submit the form, but bring medicine now")
    assertEquals(NotificationIntent.ACTION_REQUIRED, evidence.intent)
    assertFalse(evidence.abstain)
    assertTrue(evidence.urgency.timeSensitive)
  }

  @Test fun contradictionsAboutTheSameActionRequireKeepingTheOriginal() {
    listOf(
      "No need to submit the form. Submit the form now.",
      "Call me now. Do not call me.",
      "The exam is cancelled but come to the exam now"
    ).forEach { message ->
      val evidence = analyze(message)
      assertEquals(message, NotificationIntent.UNCERTAIN, evidence.intent)
      assertTrue(message, evidence.abstain)
      assertFalse(message, evidence.canRefineWithModel)
    }
  }

  @Test fun requestWithADeadlineNeedsNoUrgentKeyword() {
    listOf(
      "Please submit the signed form before 5pm",
      "Your approval is needed before the meeting starts in five minutes",
      "Assignment aaj raat tak jama karna hai"
    ).forEach { message ->
      val evidence = analyze(message)
      assertEquals(message, NotificationIntent.ACTION_REQUIRED, evidence.intent)
      assertTrue(message, evidence.urgency.deadline)
      assertFalse(message, evidence.urgency.explicitUrgency)
      assertFalse(message, evidence.abstain)
    }
  }

  @Test fun imminentChangeIsDistinguishedFromMerelyMentioningAnExam() {
    val changed = analyze("Exam hall changed to C204 and starts in five minutes")
    assertEquals(NotificationIntent.TIME_SENSITIVE, changed.intent)
    assertTrue(changed.urgency.timeSensitive)
    assertFalse(changed.abstain)
    val timetable = analyze("Here is the exam timetable")
    assertEquals(0, timetable.urgency.score)
    assertTrue(timetable.abstain)
  }

  @Test fun routineRequestsWithoutADeadlineMustNotBeSilentlyDigested() {
    listOf("Please send the slides", "Call me when you have time", "Jaldi nahi hai, jab time mile bhej dena", "Call me not now but tomorrow", "Not urgent call me when you can").forEach { message ->
      val evidence = analyze(message)
      assertEquals(message, NotificationIntent.ACTION_REQUIRED, evidence.intent)
      assertTrue(message, evidence.abstain)
      assertFalse(message, evidence.urgency.explicitUrgency)
      assertFalse(message, evidence.urgency.timeSensitive)
    }
  }

  @Test fun promotionWordsAndCountdownDoNotPretendToBeAPersonalEmergency() {
    listOf("URGENT flash sale ends tonight buy now", "Limited time offer, shop now", "Can't breathe? Buy our product now").forEach { message ->
      val evidence = analyze(message)
      assertEquals(message, NotificationIntent.PROMOTION, evidence.intent)
      assertEquals(message, 0, evidence.urgency.score)
    }
  }

  @Test fun anActualPresentSymptomIsNotHiddenByAMarketingPhrase() {
    val evidence = analyze("I cannot breathe. Please buy our inhaler now")
    assertEquals(NotificationIntent.SAFETY_CRITICAL, evidence.intent)
    assertFalse(evidence.abstain)
  }

  @Test fun mixedPromotionsAndIndependentActionAreUncertain() {
    val evidence = analyze("Flash sale ends tonight. Submit the assignment before 5pm")
    assertEquals(NotificationIntent.UNCERTAIN, evidence.intent)
    assertTrue(evidence.abstain)
    assertFalse(evidence.canRefineWithModel)
  }

  @Test fun quotationOrForwardingAloneCannotTriggerAPriorityRepost() {
    listOf("\"Urgent call me now\"", "He said 'urgent call me now'", "> Urgent call me now", "Forwarded: urgent call me now").forEach { message ->
      val evidence = analyze(message)
      assertTrue(message, evidence.abstain)
      assertEquals(message, 0, evidence.urgency.score)
      assertFalse(message, evidence.canRefineWithModel)
    }
  }

  @Test fun unquotedNewActionCanStillBeRecognizedBesideAnOldQuote() {
    val evidence = analyze("> Urgent submit tonight\nEmergency resolved everyone safe")
    assertEquals(NotificationIntent.CANCELLED_OR_RESOLVED, evidence.intent)
    assertEquals(0, evidence.urgency.score)
    assertFalse(evidence.abstain)
  }

  @Test fun aPriorUrgentMessageDoesNotTurnAContextlessReplyIntoAnUrgentRequest() {
    val where = analyze("Where?", "I cannot breathe")
    assertEquals(NotificationIntent.UNCERTAIN, where.intent)
    assertTrue(where.abstain)
    assertEquals(0, where.urgency.score)
    assertFalse(where.canRefineWithModel)
    val thanks = analyze("Thanks", "Please help, I cannot breathe")
    assertTrue(thanks.abstain)
    assertFalse(thanks.canRefineWithModel)
  }

  @Test fun aCurrentSelfContainedActionIsNotNegatedByAnOlderThreadMessage() {
    val evidence = analyze("Call me now", "Don't call me today")
    assertEquals(NotificationIntent.ACTION_REQUIRED, evidence.intent)
    assertFalse(evidence.abstain)
  }

  @Test fun aPronounWithoutItsActionObjectIsPreservedForTheOriginalApp() {
    val evidence = analyze("Submit it now", "Form or assignment?")
    assertTrue(evidence.abstain)
    assertFalse(evidence.canRefineWithModel)
  }

  @Test fun pastEmergencyOrHypotheticalInstructionsAreNotCurrentDanger() {
    listOf("I was unable to breathe yesterday", "If someone cannot breathe call an ambulance", "Imagine if the building is on fire", "Tutorial: if a patient is not breathing call an ambulance").forEach { message ->
      val evidence = analyze(message)
      assertTrue(message, evidence.intent != NotificationIntent.SAFETY_CRITICAL)
      assertEquals(message, 0, evidence.urgency.score)
      assertFalse(message, evidence.canRefineWithModel)
    }
  }

  @Test fun describingAPastEventDoesNotMakeAnAttachedNewRequestDisposable() {
    val evidence = analyze("Yesterday dad collapsed please call me")
    assertEquals(NotificationIntent.ACTION_REQUIRED, evidence.intent)
    assertTrue(evidence.abstain)
    assertFalse(evidence.urgency.explicitUrgency)
    assertFalse(evidence.urgency.timeSensitive)
    assertFalse(evidence.canRefineWithModel)
  }

  @Test fun resolutionPlusAnUnrecognizedCurrentClauseStillRequiresContext() {
    val evidence = analyze("The exam is cancelled. The meeting now starts in five minutes")
    assertTrue(evidence.abstain)
    assertEquals(0, evidence.urgency.score)
  }

  @Test fun currentNegativeSymptomSurvivesAnUnrelatedReassurance() {
    val evidence = analyze("Everyone is safe but I cannot breathe")
    assertEquals(NotificationIntent.SAFETY_CRITICAL, evidence.intent)
    assertFalse(evidence.abstain)
  }

  @Test fun onlyRealMentionsAreCountedInsteadOfEmailAddresses() {
    val email = analyze("Send the form to person@example.com before 5pm")
    assertFalse(email.urgency.directMention)
    val mention = analyze("@Aman send the form before 5pm")
    assertTrue(mention.urgency.directMention)
  }

  @Test fun unknownMeaningOrOtherLanguagesMayUseTheActualSemanticModel() {
    listOf("The world feels upside down", "कृपया यह संदेश पढ़ें").forEach { message ->
      val evidence = analyze(message)
      assertTrue(evidence.abstain)
      assertTrue(evidence.canRefineWithModel)
      assertEquals(0, evidence.urgency.score)
    }
  }

  @Test fun missingOrTruncatedContentCannotSafelyBeRanked() {
    listOf("", "x".repeat(4_097)).forEach { message ->
      val evidence = analyze(message)
      assertTrue(evidence.abstain)
      assertFalse(evidence.canRefineWithModel)
    }
  }

  @Test fun evidenceContainsOnlyDerivedFieldsAndNoRawPrivateMessage() {
    val secret = "@Aman please submit the private-form-735 before 5pm"
    val evidence = analyze(secret)
    assertFalse(evidence.toString().contains("private-form-735"))
    assertTrue(evidence.confidence in 0f..1f)
  }
}
