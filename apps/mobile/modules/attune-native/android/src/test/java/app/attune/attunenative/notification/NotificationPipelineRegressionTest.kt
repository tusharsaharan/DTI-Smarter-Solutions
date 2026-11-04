package app.attune.attunenative.notification

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real parsing/scoring/policy composition on the JVM; Android delivery remains device-tested. */
class NotificationPipelineRegressionTest {
  private val now = 1_800_000_000_000L
  private val scorer = NotificationScorer()

  private data class Result(
    val parsed: ParsedNotification,
    val meaning: NotificationMeaningEvidence,
    val score: NotificationScore,
    val decision: NotificationAdaptationDecision
  )

  private fun pipeline(
    current: String,
    prior: List<String> = emptyList(),
    model: SemanticIntentMatch? = null,
    feedback: NotificationFeedback = NotificationFeedback.NONE,
    protected: Boolean = false,
    canPost: Boolean = true,
    canOpen: Boolean = true
  ): Result {
    val messages = (prior + current).mapIndexed { index, text ->
      ParsedNotificationMessage(text, "Asha", "sender-1", now - (prior.size - index) * 1_000L)
    }
    val parsed = NotificationParser.parseSnapshot(
      NotificationExtrasSnapshot(
        title = "Urgent exam by 5pm",
        text = prior.firstOrNull(),
        bigText = prior.joinToString("\n"),
        conversationTitle = "Class group",
        isGroupConversation = true,
        messages = messages
      ),
      packageName = "com.chat", receivedAtMs = now
    )
    val rules = NotificationMeaningAnalyzer.analyze(parsed.latestTextForScoring, parsed.conversationContext.map { it.text })
    val meaning = NotificationSemanticFusion.combine(rules, model)
    val score = scorer.score(NotificationSignal(SenderTier.UNKNOWN, meaning.urgency, feedback, meaning))
    val decision = NotificationAdaptationPolicy.decide(
      NotificationAdaptationInput(
        score.points, false, protected, parsed.hasDisplayContent, canOpen, canPost,
        interpretationCertain = !meaning.abstain && parsed.latestTextForScoring.isNotBlank()
      )
    )
    return Result(parsed, meaning, score, decision)
  }

  private fun assertKept(result: Result) =
    assertEquals(NotificationAdaptationAction.KEEP_ORIGINAL, result.decision.action)

  @Test
  fun latestCancellationSupersedesTheOldDeadlineWithoutInheritingTitleOrThreadUrgency() {
    val result = pipeline(
      "No need to submit the form", listOf("Urgent: submit the form before 5pm"),
      SemanticIntentMatch(NotificationIntent.TIME_SENSITIVE, .99f, .4f)
    )
    assertEquals("No need to submit the form", result.parsed.latestTextForScoring)
    assertEquals(NotificationIntent.CANCELLED_OR_RESOLVED, result.meaning.intent)
    assertEquals(0, result.score.urgencyScore)
    assertTrue(result.score.points < NotificationAdaptationPolicy.LOW_SCORE_THRESHOLD)
    assertEquals(NotificationAdaptationAction.CANCEL_TO_DIGEST, result.decision.action)
  }

  @Test
  fun aReplacementDeadlineStillProtectsTheNewActionDespiteNegativeSenderFeedback() {
    val result = pipeline(
      "The 5pm deadline is cancelled. Submit the form by 8pm instead",
      feedback = NotificationFeedback.MARKED_NOT_IMPORTANT
    )
    assertEquals(NotificationIntent.ACTION_REQUIRED, result.meaning.intent)
    assertTrue(result.meaning.urgency.deadline)
    assertFalse(result.meaning.abstain)
    assertTrue(result.score.points >= 65)
    assertEquals(NotificationAdaptationAction.REPOST_ATTUNE, result.decision.action)
  }

  @Test
  fun presentNegativeSymptomsAreDifferentFromRecoveryQuotesAndConditionalInstructions() {
    listOf("I cannot breathe", "I cannot breathe anymore", "He is not breathing anymore").forEach { text ->
      val result = pipeline(text, feedback = NotificationFeedback.MARKED_NOT_IMPORTANT)
      assertEquals(text, NotificationIntent.SAFETY_CRITICAL, result.meaning.intent)
      assertTrue(text, result.score.points >= 90)
      assertEquals(text, NotificationAdaptationAction.REPOST_ATTUNE, result.decision.action)
    }
    listOf(
      "I was unable to breathe earlier. I am breathing normally now",
      "\"I cannot breathe\"",
      "If someone cannot breathe, call an ambulance",
      "If someone cannot breathe; call an ambulance",
      "Agar kabhi saans nahi aa rahi, ambulance bulao"
    ).forEach { text ->
      val result = pipeline(text, model = SemanticIntentMatch(NotificationIntent.SAFETY_CRITICAL, .99f, .5f))
      assertTrue(text, result.meaning.intent != NotificationIntent.SAFETY_CRITICAL)
      assertTrue(text, result.decision.action != NotificationAdaptationAction.REPOST_ATTUNE)
    }
  }

  @Test
  fun hypotheticalScopeEndsAtTheSentenceBoundaryBeforeANewPresentDanger() {
    val result = pipeline("If someone cannot breathe, call an ambulance. I cannot breathe now")
    assertEquals(NotificationIntent.SAFETY_CRITICAL, result.meaning.intent)
    assertEquals(NotificationAdaptationAction.REPOST_ATTUNE, result.decision.action)
  }

  @Test
  fun laterExplicitBreathingRecoveryRequiresContextInsteadOfAnAutomaticRepostOrDigest() {
    listOf(
      "I cannot breathe but I am breathing normally now",
      "He is not breathing. He is breathing again now",
      "I am struggling to breathe, I am breathing fine now"
    ).forEach { text ->
      val result = pipeline(text, model = SemanticIntentMatch(NotificationIntent.SAFETY_CRITICAL, .99f, .5f))
      assertEquals(text, NotificationIntent.UNCERTAIN, result.meaning.intent)
      assertTrue(text, result.meaning.abstain)
      assertFalse(text, result.meaning.canRefineWithModel)
      assertTrue(text, "conflicting_safety_status" in result.meaning.reasonCodes)
      assertKept(result)
    }
  }

  @Test
  fun breathingRecoveryDoesNotResolveADifferentDangerOrANewCurrentSymptom() {
    listOf(
      "I cannot breathe but the homework is fixed",
      "I cannot breathe but I am not breathing normally",
      "I am breathing fine but now I have chest pain",
      "I cannot breathe. I am breathing normally now, but I have chest pain",
      "I am breathing normally but now I cannot breathe",
      "I have chest pain and I cannot breathe, I am breathing normally now"
    ).forEach { text ->
      val result = pipeline(text)
      assertEquals(text, NotificationIntent.SAFETY_CRITICAL, result.meaning.intent)
      assertFalse(text, result.meaning.abstain)
      assertEquals(text, NotificationAdaptationAction.REPOST_ATTUNE, result.decision.action)
    }
  }

  @Test
  fun negativeSubjectsDenyAdverseStatesWithoutTurningAmbiguousGrammarIntoPriority() {
    listOf(
      "No one is unconscious",
      "No one in the room is unconscious",
      "Nobody here is unconscious",
      "None of our teammates is unconscious",
      "No one in the building is on fire",
      "No one who heard the alarm is unconscious",
      "No one will be unconscious",
      "No one said she is unconscious"
    ).forEach { text ->
      val result = pipeline(text, model = SemanticIntentMatch(NotificationIntent.SAFETY_CRITICAL, .999f, .99f))
      assertEquals(text, NotificationIntent.UNCERTAIN, result.meaning.intent)
      assertTrue(text, result.meaning.abstain)
      assertFalse(text, result.meaning.canRefineWithModel)
      assertTrue(text, "safety_subject_scope_requires_context" in result.meaning.reasonCodes)
      assertKept(result)
    }
  }

  @Test
  fun negativeSubjectsOfBreathingCapabilityAndSeparateCurrentDangerStillProtectSafety() {
    listOf(
      "No one can breathe",
      "Nobody can breathe",
      "No one in the room can breathe",
      "No one is breathing",
      "No one will help me, I cannot breathe",
      "No one is unconscious, but I cannot breathe"
    ).forEach { text ->
      val result = pipeline(text, model = SemanticIntentMatch(NotificationIntent.PROMOTION, .999f, .99f))
      assertEquals(text, NotificationIntent.SAFETY_CRITICAL, result.meaning.intent)
      assertFalse(text, result.meaning.abstain)
      assertTrue(text, result.score.points >= 90)
      assertEquals(text, NotificationAdaptationAction.REPOST_ATTUNE, result.decision.action)
    }
  }

  @Test
  fun englishAndHinglishPersonalDeadlinesDifferFromCommercialCountdowns() {
    listOf("Please submit the signed form before 5pm", "Form aaj raat tak jama karna hai").forEach { text ->
      val result = pipeline(text, model = SemanticIntentMatch(NotificationIntent.ACTION_REQUIRED, .86f, .2f))
      assertEquals(text, NotificationIntent.ACTION_REQUIRED, result.meaning.intent)
      assertTrue(text, result.meaning.urgency.deadline)
      assertEquals(text, NotificationAdaptationAction.REPOST_ATTUNE, result.decision.action)
    }
    val marketing = pipeline(
      "URGENT flash sale ends tonight buy now",
      model = SemanticIntentMatch(NotificationIntent.PROMOTION, .86f, .2f)
    )
    assertEquals(NotificationIntent.PROMOTION, marketing.meaning.intent)
    assertEquals(0, marketing.score.urgencyScore)
    assertEquals(NotificationAdaptationAction.CANCEL_TO_DIGEST, marketing.decision.action)
  }

  @Test
  fun unavailableModelMissingContentAndIncompleteContextKeepTheOriginal() {
    listOf(
      "" to listOf("Urgent call me now"),
      "Where?" to listOf("I cannot breathe"),
      "Thanks" to listOf("Submit the form before 5pm"),
      "Submit it now" to listOf("Which form?"),
      "कृपया यह संदेश पढ़ें" to emptyList(),
      "An unfamiliar message" to emptyList(),
      "Flash sale ends tonight buy now" to emptyList(),
      "Please send the slides" to emptyList(),
      "No need to submit the form. Submit the form now" to emptyList()
    ).forEach { (text, prior) ->
      val result = pipeline(text, prior)
      assertTrue(text, result.meaning.abstain)
      assertKept(result)
      assertEquals(text, "context_rules", result.meaning.source)
    }
  }

  @Test
  fun protectedNotificationsAndUnavailableDeliveryPathsKeepTheOriginalAtAnyPriority() {
    assertKept(pipeline("I cannot breathe", protected = true))
    assertKept(pipeline("I cannot breathe", canPost = false))
    assertKept(pipeline("I cannot breathe", canOpen = false))
    assertKept(pipeline("The meeting is cancelled", protected = true))
  }

  @Test
  fun evenNearCertainNeuralSuggestionsPreserveMessagesWithoutCurrentActionEvidence() {
    listOf("The room has blue curtains", "There are chairs beside the window", "कृपया यह संदेश पढ़ें").forEach { text ->
      listOf(NotificationIntent.SAFETY_CRITICAL, NotificationIntent.TIME_SENSITIVE, NotificationIntent.ACTION_REQUIRED).forEach { intent ->
        val result = pipeline(text, model = SemanticIntentMatch(intent, .999f, .99f))
        assertTrue("$text / $intent", result.meaning.abstain)
        assertEquals("$text / $intent", "context_rules", result.meaning.source)
        assertEquals("$text / $intent", 0, result.score.urgencyScore)
        assertKept(result)
      }
    }
  }

  @Test
  fun safetyAndDeadlineFloorsSurviveBothScoringAndLaterFeedback() {
    listOf(
      "I cannot breathe" to 90,
      "The 5pm deadline is cancelled. Submit the form by 8pm instead" to 65
    ).forEach { (text, floor) ->
      val result = pipeline(text, feedback = NotificationFeedback.MARKED_NOT_IMPORTANT)
      assertTrue(text, result.score.points >= floor)
      val record = record(result)
      assertTrue(text, record.withFeedback(NotificationFeedback.MARKED_NOT_IMPORTANT).score >= floor)
      assertTrue(text, record.withFeedback(NotificationFeedback.DISMISSED).score >= floor)
    }
  }

  @Test
  fun legacyRecordsDoNotInventSemanticCertaintyOrPriorityFloors() {
    val legacy = NotificationRecord("legacy", "com.chat", now, SenderTier.UNKNOWN, 0, 14, 14, emptyList())
    assertEquals("UNCERTAIN", legacy.semanticIntent)
    assertEquals("legacy_rules", legacy.semanticSource)
    assertEquals(0f, legacy.semanticConfidence, 0f)
    assertEquals(0, legacy.withFeedback(NotificationFeedback.MARKED_NOT_IMPORTANT).score)
    val result = pipeline("An unfamiliar message")
    assertTrue(result.meaning.abstain)
    assertKept(result)
  }

  @Test
  fun negativeFeedbackCannotLeakBetweenGroupsSharingASender() {
    val oldGroup = NotificationRecord(
      "old-group", "com.chat", now - 1_000L, SenderTier.UNKNOWN, 0, 14, 14, emptyList(),
      feedback = NotificationFeedback.MARKED_NOT_IMPORTANT,
      senderKey = "same-sender", conversationKey = "weekend-group"
    )
    assertEquals(
      NotificationFeedback.NONE,
      LocalNotificationFeedbackResolver.resolve(listOf(oldGroup), "same-sender", "class-group", now)
    )
    assertEquals(
      NotificationFeedback.MARKED_NOT_IMPORTANT,
      LocalNotificationFeedbackResolver.resolve(listOf(oldGroup), "same-sender", "weekend-group", now)
    )
    assertEquals(
      NotificationFeedback.NONE,
      LocalNotificationFeedbackResolver.resolve(listOf(oldGroup), "same-sender", null, now)
    )
    val direct = oldGroup.copy(id = "direct", conversationKey = null, feedback = NotificationFeedback.REPLIED)
    assertEquals(
      NotificationFeedback.REPLIED,
      LocalNotificationFeedbackResolver.resolve(listOf(oldGroup, direct), "same-sender", null, now)
    )
  }

  @Test
  fun responseHistoryIgnoresStaleAndFutureRecordsAndDoesNotTreatTierTagsAsResponseFeedback() {
    val ageLimit = 14L * 24 * 60 * 60 * 1_000
    fun historical(id: String, at: Long, feedback: NotificationFeedback) = NotificationRecord(
      id, "com.chat", at, SenderTier.UNKNOWN, 0, 14, 14, emptyList(),
      feedback = feedback, conversationKey = "class-group"
    )
    val history = listOf(
      historical("stale", now - ageLimit - 1L, NotificationFeedback.MARKED_NOT_IMPORTANT),
      historical("future", now + 1L, NotificationFeedback.MARKED_IMPORTANT),
      historical("tier", now, NotificationFeedback.FAMILY),
      historical("boundary", now - ageLimit, NotificationFeedback.REPLIED)
    )
    assertEquals(
      NotificationFeedback.REPLIED,
      LocalNotificationFeedbackResolver.resolve(history, null, "class-group", now)
    )
    assertEquals(
      NotificationFeedback.NONE,
      LocalNotificationFeedbackResolver.resolve(history.filterNot { it.id == "boundary" }, null, "class-group", now)
    )
  }

  @Test
  fun uncertainOrLowConfidenceStoredSemanticsCannotInventAPriorityFloorDuringFeedback() {
    val uncertain = NotificationRecord(
      "uncertain", "com.chat", now, SenderTier.UNKNOWN, 0, 14, 14,
      listOf("current_action_with_time_constraint"),
      adaptationReason = "meaning_uncertain_preserve_original",
      semanticIntent = "SAFETY_CRITICAL", semanticSource = "context_rules", semanticConfidence = .86f
    )
    assertEquals(0, uncertain.withFeedback(NotificationFeedback.MARKED_NOT_IMPORTANT).score)
    val lowConfidence = uncertain.copy(adaptationReason = "not_evaluated", semanticConfidence = .2f)
    assertEquals(0, lowConfidence.withFeedback(NotificationFeedback.MARKED_NOT_IMPORTANT).score)
  }

  @Test
  fun rawThreadAndLiveDisplayContentNeverEnterDerivedHistory() {
    val privatePrior = "PRIVATE_THREAD_2401: submit the form before 5pm"
    val privateCurrent = "PRIVATE_CURRENT_9812: I cannot breathe"
    val result = pipeline(privateCurrent, listOf(privatePrior))
    val file = File.createTempFile("attune-semantic-privacy", ".json")
    try {
      file.delete()
      FileNotificationRecordStore(file, nowMs = { now }).upsert(record(result).copy(
        sender = "PRIVATE_SENDER_5892", conversation = "PRIVATE_GROUP_2943", text = privateCurrent
      ))
      val persisted = file.readText()
      listOf("PRIVATE_THREAD_2401", "PRIVATE_CURRENT_9812", "PRIVATE_SENDER_5892", "PRIVATE_GROUP_2943").forEach { secret ->
        assertFalse(secret, persisted.contains(secret))
      }
      assertFalse(persisted.contains("conversationContext"))
      assertFalse(persisted.contains("latestTextForScoring"))
      val restored = FileNotificationRecordStore(file, nowMs = { now }).all().single()
      assertEquals(result.meaning.intent.name, restored.semanticIntent)
      assertEquals(result.meaning.source, restored.semanticSource)
      assertEquals(result.meaning.confidence, restored.semanticConfidence, 0f)
    } finally {
      file.delete()
      File(file.absolutePath + ".tmp").delete()
    }
  }

  private fun record(result: Result) = NotificationRecord(
    id = "ranked-1", packageName = "com.chat", postedAtMs = now,
    senderTier = SenderTier.UNKNOWN, urgencyScore = result.score.urgencyScore,
    baseScore = result.score.basePoints, score = result.score.points, reasonCodes = result.score.reasonCodes,
    semanticIntent = result.meaning.intent.name, semanticSource = result.meaning.source,
    semanticConfidence = result.meaning.confidence
  )
}
