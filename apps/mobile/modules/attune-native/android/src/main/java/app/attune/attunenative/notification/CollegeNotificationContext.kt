package app.attune.attunenative.notification

import java.text.Normalizer
import java.util.Locale

/**
 * College-specific evidence about the CURRENT body, never a chat/group name. A cancellation can
 * itself change where a student must be today; resolving a request does not make that update idle.
 * This is bounded language evidence, not a learned model or an accuracy probability.
 */
object CollegeNotificationContext {
  private val academic = Regex("\\b(?:exams?|examinations?|vivas?|practicals?|lectures?|classes|class|labs?|attendance|registrations?|submissions?|assignments?|deadlines?|timetable)\\b")
  private val change = Regex("\\b(?:cancel(?:led|ed)?|reschedul(?:ed|e)|postpon(?:ed|e)|shift(?:ed)?|moved|relocated|chang(?:ed|e)|revised|extended|advanced|preponed|badal|badla|badli)\\b")
  private val cancellation = Regex("\\b(?:cancelled|canceled)\\b|\\bcancel\\s+(?:hai|hoga|hogi|ho\\s+(?:gaya|gayi))\\b")
  private val deadline = Regex("\\b(?:deadline|due|last\\s+(?:date|day)|registration\\s+closes?|submission\\s+closes?)\\b|\\b(?:submit|upload|register|jama)\\b.{0,45}\\b(?:by|before|tak|today|tonight|aaj)\\b")
  private val clock = Regex("\\b(?:[01]?\\d|2[0-3])(?::[0-5]\\d)?\\s*(?:am|pm|baje)\\b|\\b(?:at|by|before|from|until)\\s+(?:[01]?\\d|2[0-3])(?::[0-5]\\d)?\\b|\\b(?:[01]?\\d|2[0-3]):[0-5]\\d\\b")
  private val scheduleVerb = Regex("\\b(?:starts?|begins?|scheduled|held|conducted|commences?|shuru|hoga|hogi|honge|hai)\\b")
  private val directDay = Regex("\\b(?:today|tonight|tomorrow|aaj)\\b")
  private val kal = Regex("\\bkal\\b")
  private val futureHindi = Regex("\\b(?:hai|hoga|hogi|honge|hongee|rahega|rahegi)\\b")
  private val futureSchedule = Regex("\\b(?:will|upcoming|next)\\b")
  private val futureDate = Regex("\\b(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday|week|month|semester|january|february|march|april|may|june|july|august|september|october|november|december)\\b|\\b\\d{1,2}[/.-]\\d{1,2}(?:[/.-]\\d{2,4})?\\b")
  private val past = Regex("\\b(?:yesterday|ago|earlier|previously|already|tha|thi|thaa|thii|finished|ended|completed)\\b|\\blast\\s+(?:night|week|month|year|semester)\\b|\\bho\\s+chuk[ai]\\b")
  private val pastVerb = Regex("\\b(?:was|were|had)\\b")
  private val negative = Regex("\\b(?:no|not|never|nahi|nahin|nhi|mat|without|isn't|aren't|wasn't|weren't|won't|isnt|arent|wont|didn't|didnt|don't|dont)\\b")
  private val unverified = Regex("\\b(?:forwarded|fwd|rumou?rs?|apparently|maybe|perhaps|probably|possibly|might|unconfirmed|shayad|suna|suni|if|unless|whether|agar|suppose|hypothetical)\\b|\\b(?:someone\\s+said|heard\\s+that|not\\s+confirmed|i\\s+think|i\\s+guess|ho\\s+sakta|for\\s+example|what\\s+if)\\b")
  private val material = Regex("\\b(?:notes?|readings?|slides?|syllabus|textbooks?|solutions?|resources?|memes?|jokes?|photos?|reels?|pasted|copied|template|example|tutorial|reference)\\b|\\b(?:old|previous|past|sample|practice|model)\\s+(?:exam\\s+)?(?:papers?|questions?)\\b")
  private val flexible = Regex("\\b(?:whenever|anytime|sometime|flexible|indefinitely|fursat)\\b|\\b(?:no\\s+(?:hurry|rush)|not\\s+urgent|take\\s+your\\s+time|when\\s+you\\s+have\\s+time|jab\\s+time\\s+mile|aaram\\s+se)\\b")
  private val nonAssertion = Regex("\\b(?:tell|ask|check|know|find|discuss|about|regarding|hope|hate|love|fear|worry|studying|prepare|revision)\\b|^(?:is|are|will|does|do|did|when|where|why|can|could|kya|kab|kahan|kaise)\\b")
  private val mixed = Regex("\\b(?:but|however|although|lekin|magar)\\b")
  private val terseSchedule = Regex("^(?:(?:the|our|my|your|college|university|maths?|physics|chemistry|biology|english|computer|science|dti)\\s+){0,3}(?:exams?|examinations?|viva|practical|lecture|class|lab)(?:\\s+(?:is|are|will|be|scheduled|for|on|today|tonight|tomorrow|aaj|kal|hai|hoga|hogi|honge))+[.!]?$" )

  // These full-body grammars deliberately exclude 'No class rep has announced ...', broad
  // negation, follow-up clauses and double negatives. Only these may cross a negation-scope guard.
  private val noEnglishEvent = Regex("^(?:(?:today|tonight|tomorrow)\\s*[:,]?\\s+)?(?:there\\s+(?:is|will\\s+be)\\s+)?no\\s+(?:(?:maths?|physics|chemistry|biology|english|computer|science|dti)\\s+){0,2}(?:class(?:es)?|lectures?|labs?|vivas?|exams?|practicals?)(?:\\s+(?:today|tonight|tomorrow))?(?:\\s+at\\s+\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)?)?[.!]*$")
  private val noHindiEvent = Regex("^(?:aaj|kal)\\s+(?:(?:maths?|physics|chemistry|biology|english|computer|science|dti)\\s+){0,2}(?:class|lecture|lab|viva|exam|practical)\\s+(?:nahi|nahin|nhi)\\s+(?:hai|hogi|hoga|honge)[.!]*$")
  private val guarded = setOf(
    "missing_message_content", "message_exceeds_analysis_window", "forwarded_context_unverified",
    "quoted_context_unverified", "thread_context_needed", "ambiguous_reference",
    "mixed_promotion_and_personal_context", "action_object_needs_context", "past_or_negated_urgency",
    "acknowledgment_requires_thread_context", "conflicting_action_status", "conflicting_safety_status",
    "safety_subject_scope_requires_context", "hypothetical_context_unverified",
    "negation_scope_needs_context", "clauses_interpreted_separately", "language_requires_semantic_model"
  )

  fun refine(text: String, base: NotificationMeaningEvidence): NotificationMeaningEvidence {
    // Academic timing cannot override an already established safety or promotion intent.
    if (text.isBlank() || text.length > 4_096 ||
      base.intent == NotificationIntent.SAFETY_CRITICAL || base.intent == NotificationIntent.PROMOTION) return base
    val value = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
      .replace('’', '\'').replace(Regex("\\s+"), " ").trim()
    if (!academic.containsMatchIn(value)) return base
    if (value.any { it.isLetter() && it.code > 0x024F } || value.contains('?') ||
      value.contains('"') || value.contains('`') || value.contains('“') || value.contains('”') ||
      Regex("(?:^|\\s)[>'‘]|['’](?:$|\\s)").containsMatchIn(value)) return base
    if (unverified.containsMatchIn(value) || material.containsMatchIn(value) || flexible.containsMatchIn(value) ||
      nonAssertion.containsMatchIn(value) || mixed.containsMatchIn(value) || past.containsMatchIn(value)) return base

    val noEvent = noEnglishEvent.matches(value) || noHindiEvent.matches(value)
    val blocked = base.reasonCodes.any { it in guarded && !(noEvent && it == "negation_scope_needs_context") }
    if (blocked || (negative.containsMatchIn(value) && !noEvent)) return base

    val changed = change.containsMatchIn(value)
    val cancelled = noEvent || cancellation.containsMatchIn(value)
    val dated = directDay.containsMatchIn(value) ||
      (kal.containsMatchIn(value) && futureHindi.containsMatchIn(value)) ||
      (futureSchedule.containsMatchIn(value) && futureDate.containsMatchIn(value))
    if (pastVerb.containsMatchIn(value) && !(changed && dated)) return base
    val constrained = deadline.containsMatchIn(value)
    val scheduled = changed || noEvent || constrained || clock.containsMatchIn(value) ||
      scheduleVerb.containsMatchIn(value) || terseSchedule.matches(value)
    if (!scheduled) return base

    if (!dated) {
      // A definite room/time/cancellation update must not be silently filed away, but without
      // a current/upcoming anchor this layer cannot justify interrupting the student again.
      if (!changed && !noEvent) return base
      return base.copy(
        intent = NotificationIntent.UNCERTAIN, urgency = UrgencySignals(), confidence = 0.4f,
        abstain = true, source = "context_college", canRefineWithModel = false,
        reasonCodes = (base.reasonCodes + "college_schedule_change_date_needed").distinct()
      )
    }
    val reason = when {
      cancelled -> "college_current_schedule_cancellation"
      changed -> "college_current_schedule_change"
      constrained -> "college_current_deadline"
      else -> "college_current_schedule"
    }
    return base.copy(
      intent = NotificationIntent.TIME_SENSITIVE,
      urgency = UrgencySignals(timeSensitive = true, deadline = constrained, directMention = base.urgency.directMention),
      confidence = 0.83f, abstain = false, source = "context_college", canRefineWithModel = false,
      reasonCodes = (base.reasonCodes.filterNot { noEvent && it == "negation_scope_needs_context" } + reason +
        if (noEvent) listOf("college_explicit_no_event") else emptyList()).distinct()
    )
  }
}
