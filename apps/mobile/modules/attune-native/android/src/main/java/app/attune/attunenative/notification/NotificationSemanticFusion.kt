package app.attune.attunenative.notification

/** Scope guards win over embedding similarity; uncertain evidence never authorizes suppression. */
object NotificationSemanticFusion {
  fun combine(rules: NotificationMeaningEvidence, model: SemanticIntentMatch?): NotificationMeaningEvidence {
    if (!rules.canRefineWithModel) return rules
    if (model == null) return rules.copy(
      abstain = true, reasonCodes = (rules.reasonCodes + "semantic_model_abstained").distinct()
    )
    val lowPriority = setOf(NotificationIntent.INFORMATIONAL, NotificationIntent.PROMOTION, NotificationIntent.CANCELLED_OR_RESOLVED)
    if ((rules.intent in lowPriority && rules.intent != model.intent) ||
      (model.intent in lowPriority && rules.intent != model.intent)) {
      return rules.copy(abstain = true, reasonCodes = rules.reasonCodes + "meaning_evidence_disagrees")
    }
    // A short continuation such as "please?" can have no standalone dictionary evidence. A
    // contextual embedding may resolve it to an ordinary action, but it still cannot manufacture
    // a deadline, urgency, or safety event that is absent from the current message.
    if (rules.intent == NotificationIntent.UNCERTAIN && model.contextual &&
      model.margin >= 0.25f && model.intent in setOf(
        NotificationIntent.ACTION_REQUIRED,
        NotificationIntent.INFORMATIONAL,
        NotificationIntent.PROMOTION
      )) {
      return NotificationMeaningEvidence(
        intent = model.intent,
        urgency = rules.urgency,
        confidence = model.similarity,
        abstain = false,
        reasonCodes = (rules.reasonCodes + "contextual_continuation" +
          "semantic_${model.intent.name.lowercase()}" + "semantic_conversation_context").distinct(),
        source = "on_device_semantic_and_context",
        canRefineWithModel = false
      )
    }
    // A synthetic-data classifier can be confidently wrong. It may confirm current action
    // evidence, but may not invent danger or urgency from an otherwise uncertain message.
    val unsupportedPriority = when (model.intent) {
      NotificationIntent.SAFETY_CRITICAL -> rules.intent != NotificationIntent.SAFETY_CRITICAL
      NotificationIntent.TIME_SENSITIVE -> rules.intent != NotificationIntent.TIME_SENSITIVE &&
        !(rules.intent == NotificationIntent.ACTION_REQUIRED && (rules.urgency.deadline || rules.urgency.timeSensitive))
      NotificationIntent.ACTION_REQUIRED -> rules.intent != NotificationIntent.ACTION_REQUIRED
      else -> false
    }
    if (unsupportedPriority) return rules.copy(abstain = true,
      reasonCodes = rules.reasonCodes + "semantic_suggestion_lacks_current_action_evidence")
    val urgency = when (model.intent) {
      NotificationIntent.SAFETY_CRITICAL -> UrgencySignals(explicitUrgency = true, callbackRequest = true, timeSensitive = true)
      NotificationIntent.TIME_SENSITIVE -> rules.urgency.copy(deadline = true, timeSensitive = true)
      NotificationIntent.ACTION_REQUIRED -> rules.urgency
      else -> UrgencySignals()
    }
    return NotificationMeaningEvidence(
      intent = model.intent, urgency = urgency, confidence = model.similarity,
      abstain = model.intent == NotificationIntent.UNCERTAIN,
      reasonCodes = (rules.reasonCodes + "semantic_${model.intent.name.lowercase()}" +
        "semantic_intent_match" + if (model.contextual) "semantic_conversation_context" else "").filter { it.isNotBlank() }.distinct(),
      source = "on_device_semantic_and_context", canRefineWithModel = false
    )
  }
}
