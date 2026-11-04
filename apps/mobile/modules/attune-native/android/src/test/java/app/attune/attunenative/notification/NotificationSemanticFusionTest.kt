package app.attune.attunenative.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Policy boundaries using injected derived matches; these do not claim model accuracy. */
class NotificationSemanticFusionTest {
  private val safetyMatch = SemanticIntentMatch(NotificationIntent.SAFETY_CRITICAL, .97f, .30f)

  @Test
  fun scopedNegationCancellationAndQuotationCannotBeOverriddenByASimilarEmbedding() {
    listOf(
      "Please do not call an ambulance",
      "The meeting is cancelled",
      "Emergency resolved everyone safe",
      "I cannot breathe but I am breathing normally now",
      "\"I cannot breathe\"",
      "> I cannot breathe",
      "Forwarded: I cannot breathe",
      "If someone cannot breathe, call an ambulance",
      "Agar kabhi saans nahi aa rahi, ambulance bulao"
    ).forEach { text ->
      val rules = NotificationMeaningAnalyzer.analyze(text)
      assertFalse(text, rules.canRefineWithModel)
      val fused = NotificationSemanticFusion.combine(rules, safetyMatch)
      assertEquals(text, rules, fused)
      assertTrue(text, fused.intent != NotificationIntent.SAFETY_CRITICAL)
      assertEquals(text, "context_rules", fused.source)
      assertFalse(text, "semantic_intent_match" in fused.reasonCodes)
    }
  }

  @Test
  fun contextlessRepliesAndConflictingActionStatusRejectEvenAConfidentModelMatch() {
    listOf(
      "Where?" to listOf("Please help, I cannot breathe"),
      "Thanks" to listOf("Submit the assignment before 5pm"),
      "Submit it now" to listOf("The form or the assignment?"),
      "No need to submit the form. Submit the form now" to emptyList()
    ).forEach { (text, context) ->
      val rules = NotificationMeaningAnalyzer.analyze(text, context)
      val fused = NotificationSemanticFusion.combine(rules, safetyMatch)
      assertTrue(text, fused.abstain)
      assertFalse(text, fused.canRefineWithModel)
      assertEquals(text, rules, fused)
    }
  }

  @Test
  fun lowPriorityDisagreementAbstainsInBothDirections() {
    val promotion = NotificationMeaningAnalyzer.analyze("Flash sale ends tonight buy now")
    val highVsLow = NotificationSemanticFusion.combine(promotion, safetyMatch)
    assertTrue(highVsLow.abstain)
    assertTrue("meaning_evidence_disagrees" in highVsLow.reasonCodes)
    assertEquals("context_rules", highVsLow.source)

    val request = NotificationMeaningAnalyzer.analyze("Send the signed form before 5pm")
    val lowVsHigh = NotificationSemanticFusion.combine(
      request, SemanticIntentMatch(NotificationIntent.PROMOTION, .95f, .40f)
    )
    assertTrue(lowVsHigh.abstain)
    assertTrue("meaning_evidence_disagrees" in lowVsHigh.reasonCodes)
    assertEquals(request.urgency, lowVsHigh.urgency)
    assertEquals("context_rules", lowVsHigh.source)
  }

  @Test
  fun absentModelCannotAuthorizeSuppressingARefinableLowPriorityMessage() {
    listOf("Flash sale ends tonight buy now", "Thank you", "An unfamiliar message").forEach { text ->
      val rules = NotificationMeaningAnalyzer.analyze(text)
      assertTrue(text, rules.canRefineWithModel)
      val fused = NotificationSemanticFusion.combine(rules, null)
      assertTrue(text, fused.abstain)
      assertEquals(text, rules.source, fused.source)
      assertEquals(rules.confidence, fused.confidence, 0f)
      assertTrue(text, "semantic_model_abstained" in fused.reasonCodes)
    }
  }

  @Test
  fun positiveModelEvidenceHasItsActualSourceAndEvidenceStrength() {
    val rules = NotificationMeaningAnalyzer.analyze("Please submit the signed form before 5pm")
    val match = SemanticIntentMatch(NotificationIntent.ACTION_REQUIRED, .81f, .12f)
    val fused = NotificationSemanticFusion.combine(rules, match)
    assertEquals(NotificationIntent.ACTION_REQUIRED, fused.intent)
    assertFalse(fused.abstain)
    assertFalse(fused.canRefineWithModel)
    assertEquals("on_device_semantic_and_context", fused.source)
    assertEquals(match.similarity, fused.confidence, 0f)
    assertTrue(fused.confidence in 0f..1f)
    assertTrue(fused.urgency.deadline)
  }

  @Test
  fun contextualModelEvidenceIsVisibleWithoutChangingLatestMessageGuards() {
    val rules = NotificationMeaningAnalyzer.analyze("Please?")
    val match = SemanticIntentMatch(NotificationIntent.ACTION_REQUIRED, .81f, .31f, contextual = true)

    val fused = NotificationSemanticFusion.combine(rules, match)

    assertEquals(NotificationIntent.ACTION_REQUIRED, fused.intent)
    assertFalse(fused.abstain)
    assertTrue("semantic_conversation_context" in fused.reasonCodes)
    assertEquals("on_device_semantic_and_context", fused.source)
  }

  @Test
  fun aKnownCurrentEmergencyRemainsProtectedWhenTheModelIsUnavailableOrContradictsIt() {
    listOf("I cannot breathe", "I cannot breathe anymore", "He is not breathing anymore").forEach { text ->
      val rules = NotificationMeaningAnalyzer.analyze(text)
      listOf(null, SemanticIntentMatch(NotificationIntent.PROMOTION, .99f, .5f)).forEach { model ->
        val fused = NotificationSemanticFusion.combine(rules, model)
        assertEquals(text, NotificationIntent.SAFETY_CRITICAL, fused.intent)
        assertFalse(text, fused.abstain)
        assertEquals(text, rules, fused)
        assertEquals(text, "context_rules", fused.source)
      }
    }
  }

  @Test
  fun anExplicitNegatedEmergencyDiffersFromAContextlessNegativeReply() {
    val status = NotificationMeaningAnalyzer.analyze("No emergency")
    assertEquals(NotificationIntent.INFORMATIONAL, status.intent)
    assertFalse(status.abstain)
    assertFalse(status.canRefineWithModel)
    assertEquals(0, status.urgency.score)
    assertEquals(status, NotificationSemanticFusion.combine(status, safetyMatch))

    val reply = NotificationMeaningAnalyzer.analyze("No", listOf("Do we need to call an ambulance?"))
    assertTrue(reply.abstain)
    assertFalse(reply.canRefineWithModel)
    assertEquals(reply, NotificationSemanticFusion.combine(reply, safetyMatch))
  }

  @Test
  fun highConfidenceModelSuggestionsCannotInventPriorityFromUncertainRules() {
    val rules = NotificationMeaningAnalyzer.analyze("The room has blue curtains")
    assertEquals(NotificationIntent.UNCERTAIN, rules.intent)
    assertTrue(rules.canRefineWithModel)
    listOf(NotificationIntent.SAFETY_CRITICAL, NotificationIntent.TIME_SENSITIVE, NotificationIntent.ACTION_REQUIRED).forEach { intent ->
      val fused = NotificationSemanticFusion.combine(rules, SemanticIntentMatch(intent, .999f, .99f))
      assertTrue(intent.name, fused.abstain)
      assertEquals(intent.name, NotificationIntent.UNCERTAIN, fused.intent)
      assertEquals(intent.name, "context_rules", fused.source)
      assertEquals(rules.confidence, fused.confidence, 0f)
      assertEquals(0, fused.urgency.score)
      assertTrue(intent.name, "semantic_suggestion_lacks_current_action_evidence" in fused.reasonCodes)
    }
  }

  @Test
  fun confirmingAnOrdinaryRequestCannotInventAnEmergencyOrDeadline() {
    val rules = NotificationMeaningAnalyzer.analyze("Please send the report")
    assertEquals(NotificationIntent.ACTION_REQUIRED, rules.intent)
    assertFalse(rules.urgency.deadline)
    assertFalse(rules.urgency.timeSensitive)
    listOf(NotificationIntent.SAFETY_CRITICAL, NotificationIntent.TIME_SENSITIVE).forEach { intent ->
      val fused = NotificationSemanticFusion.combine(rules, SemanticIntentMatch(intent, .999f, .99f))
      assertTrue(intent.name, fused.abstain)
      assertEquals(rules.urgency, fused.urgency)
      assertTrue(intent.name, "semantic_suggestion_lacks_current_action_evidence" in fused.reasonCodes)
    }
  }
}
