package app.attune.attunenative.notification

import java.text.Normalizer
import java.util.Locale

enum class NotificationIntent {
  ACTION_REQUIRED,
  SAFETY_CRITICAL,
  TIME_SENSITIVE,
  CANCELLED_OR_RESOLVED,
  INFORMATIONAL,
  PROMOTION,
  UNCERTAIN
}

/**
 * Derived evidence only: never retains message text or a thread. Confidence denotes rule evidence
 * strength, not validated probability. This fallback is NOT a neural semantic model. In particular,
 * low urgency never grants permission to hide a notification: an abstention must KEEP_ORIGINAL.
 */
data class NotificationMeaningEvidence(
  val intent: NotificationIntent,
  val urgency: UrgencySignals,
  val confidence: Float,
  val abstain: Boolean,
  val reasonCodes: List<String>,
  val source: String = "context_rules",
  val canRefineWithModel: Boolean = true
)

/**
 * Bounded English / romanized-Hindi fallback. Interpretation composes clause-level speech acts,
 * scoped negation, temporal status and quotation provenance. Unknown language or incomplete context
 * is preserved for the semantic model / original app rather than classified as an unimportant chat.
 */
object NotificationMeaningAnalyzer {
  private const val MAX_TEXT_CHARS = 4_096
  private const val MAX_CONTEXT_MESSAGES = 4
  private val tokenPattern = Regex("@[\\p{L}\\p{N}_]+|[\\p{L}\\p{N}]+(?:['’][\\p{L}]+)?")
  private val whitespace = Regex("\\s+")
  private val adversative = Regex("\\b(?:but|however|although|yet|lekin|magar|lakin)\\b")
  private val mentionPattern = Regex("(?<![\\p{L}\\p{N}_])@[\\p{L}\\p{N}_]+")
  private val clockToken = Regex("\\d{1,2}(?:am|pm)?")

  private val urgencyWords = setOf("urgent", "urgently", "asap", "immediately", "emergency", "jaldi", "abhi", "zaroori", "zaruri", "turant", "fauran")
  private val negationWords = setOf("no", "not", "never", "nahi", "nahin", "nhi", "mat", "without")
  private val historyWords = setOf("yesterday", "previously", "earlier", "already", "was", "were", "had", "tha", "thi")
  private val currentWords = setOf("now", "still", "again", "abhi", "ab", "currently", "aaj", "today")
  private val actionWords = setOf("call", "phone", "ring", "send", "submit", "upload", "sign", "bring", "help", "come", "leave", "evacuate", "approve", "pay", "confirm", "reply", "join", "attend", "collect", "pick", "bhejo", "bhejna", "bhej", "jama", "aao", "aana", "bulao", "bachao", "pahucho", "niklo", "karo", "karna", "lena", "dena")
  private val actionObjects = setOf("form", "assignment", "file", "report", "slides", "document", "application", "invoice", "medicine", "inhaler", "ambulance", "fire", "meeting", "exam", "test", "train", "flight", "bus", "gate", "class", "viva", "interview", "payment", "signature", "approval")
  private val referenceWords = setOf("it", "this", "that", "there", "same", "where", "why", "which", "yes", "no", "okay", "ok", "kahan", "kaha", "kaunsa", "wahi", "ye", "woh", "wo")
  private val replyWords = setOf("yes", "no", "okay", "ok")

  private val respiratoryStates = phrases(
    "can not breathe", "not breathing", "unable to breathe", "struggling to breathe", "difficulty breathing",
    "saans nahi aa rahi", "saans nahin aa rahi", "saans nhi aa rahi", "saans nahi le pa raha",
    "saans nahi le pa rahi"
  )
  private val breathingRecoveries = phrases("breathing normally", "breathing again", "breathing fine", "can breathe normally", "can breathe again")
  private val negativeSubjects = phrases("no one", "no person", "nobody", "none")
  private val breathingCapabilities = phrases("can breathe", "is breathing", "are breathing")
  private val dangerousStates = respiratoryStates + phrases(
    "chest pain", "having a heart attack", "having a stroke", "bleeding heavily", "unconscious",
    "has collapsed", "have collapsed", "is on fire", "building on fire", "fire in the building",
    "trapped inside", "someone is following me", "being attacked", "in immediate danger",
    "behosh ho gaye", "behosh ho gaya", "behosh hai", "aag lag gayi", "aag lagi hai"
  )
  private val safetyActions = phrases("call an ambulance", "call ambulance", "call the police", "call the fire brigade", "ambulance bulao", "police bulao", "evacuate now")
  private val callbacks = phrases("call me", "phone me", "ring me", "call back", "call karo", "phone karo", "mujhe call", "mujhe phone", "baat karo")
  private val flexible = phrases("not urgent", "no hurry", "no rush", "whenever you can", "whenever you have time", "when you have time", "take your time", "no need to rush", "jaldi nahi", "jaldi nahin", "jaldi nhi", "jab time mile", "fursat mein", "aaram se")
  private val resolutions = phrases(
    "emergency resolved", "emergency is over", "emergency over", "everyone is safe", "everyone safe",
    "all safe now", "false alarm", "no action needed", "no action required", "already submitted",
    "already paid", "already sent", "problem is resolved", "issue is resolved", "issue resolved",
    "kaam ho gaya", "sab theek hai", "sab thik hai", "khatra tal gaya", "fine now", "now fine",
    "okay now", "now okay", "breathing normally", "breathing again"
  )
  private val cancellations = phrases("is cancelled", "is canceled", "has been cancelled", "has been canceled", "exam cancelled", "exam canceled", "meeting cancelled", "meeting canceled", "cancel ho gaya", "cancel ho gayi", "cancel hai", "ignore the previous", "ignore my previous", "disregard the previous")
  private val noNeed = phrases("no need to", "do not need to", "need not", "zarurat nahi", "zaroorat nahi", "zarurat nahin")
  private val imminence = phrases("right now", "straight away", "in a minute", "in one minute", "in five minutes", "in ten minutes", "in two minutes", "within minutes", "before it closes", "last chance to submit", "aaj raat tak")
  private val pastPhrases = phrases("last night", "last week", "last month", "used to", "had an emergency", "was an emergency", "was urgent", "was cancelled", "was canceled", "ho chuka", "ho chuki")
  private val hypothetical = phrases("what if", "imagine if", "suppose", "for example", "example of", "if someone", "if you ever", "in case of", "agar kabhi")
  private val hypotheticalCueWords = setOf("if", "agar", "tutorial", "hypothetical")
  private val promotions = phrases("buy now", "shop now", "flash sale", "sale ends", "limited time offer", "limited period offer", "discount code", "coupon code", "get cashback", "claim your prize", "win a prize", "special offer", "offer ends")
  private val acknowledgments = phrases("thank you", "thanks", "good morning", "good night", "photo shared", "sent a photo", "sent a sticker", "sent a gif", "happy birthday", "shukriya", "dhanyavaad")

  fun analyze(text: CharSequence?, priorMessages: List<CharSequence> = emptyList()): NotificationMeaningEvidence {
    val raw = text?.toString()?.trim().orEmpty()
    if (raw.isBlank()) return uncertain("missing_message_content")
    if (raw.length > MAX_TEXT_CHARS) return uncertain("message_exceeds_analysis_window")
    val document = parse(raw)
    if (document.forwarded) return uncertain("forwarded_context_unverified")
    if (document.clauses.isEmpty()) return uncertain(if (document.quoted) "quoted_context_unverified" else "missing_message_content")
    val tokens = document.clauses.flatMap { it.tokens }
    // Other scripts are intentionally unsupported here; the multilingual model can still analyze them.
    if (tokens.any { token -> token.any { it.isLetter() && it.code > 0x024F } }) return uncertain("language_requires_semantic_model")
    val priorExists = priorMessages.takeLast(MAX_CONTEXT_MESSAGES).any { it.isNotBlank() }
    val concreteObject = tokens.any { it in actionObjects }
    // 'No' alone needs an antecedent; 'no emergency' has an explicit negated predicate.
    // Reply words must not turn a self-contained status statement into a contextless reply.
    val shortReference = tokens.size <= 7 && !concreteObject &&
      (tokens.any { it in referenceWords && it !in replyWords } || tokens.all { it in replyWords })
    if (shortReference && tokens.none { it in actionWords } && !isExactAcknowledgment(tokens)) {
      return uncertain(if (priorExists) "thread_context_needed" else "ambiguous_reference")
    }

    val clauses = document.clauses.map(::interpret)
    val directMention = mentionPattern.containsMatchIn(document.unquotedText)
    val promotion = clauses.any { it.promotion }
    val safety = clauses.filter { it.safety && !it.historical && !it.hypothetical }
    val presentClauses = clauses.withIndex().filter { !it.value.historical && !it.value.hypothetical }
    fun latestPosition(position: (ClauseMeaning) -> Int?): Int? = presentClauses.mapNotNull { clause ->
      position(clause.value)?.let { clause.index * MAX_TEXT_CHARS + it }
    }.lastOrNull()
    val lastBreathingDanger = latestPosition { it.lastBreathingDangerAt }
    val lastBreathingRecovery = latestPosition { it.lastBreathingRecoveryAt }
    // A subsequent explicit breathing recovery contradicts an earlier respiratory assertion.
    // Subjects/attribution may still differ, so preserve the original instead of digesting it.
    // Recovery from breathing trouble cannot resolve a different danger such as chest pain.
    if (lastBreathingDanger != null && lastBreathingRecovery != null &&
      lastBreathingRecovery > lastBreathingDanger && presentClauses.none { it.value.otherDanger }) {
      return uncertain("conflicting_safety_status")
    }
    if (safety.isEmpty() && presentClauses.any { it.value.safetySubjectNeedsContext }) {
      return uncertain("safety_subject_scope_requires_context")
    }
    // Marketing questions like 'can't breathe? buy now' are not personal reports of danger.
    if (safety.isNotEmpty() && !(promotion && safety.all { it.question })) {
      return evidence(NotificationIntent.SAFETY_CRITICAL, UrgencySignals(explicitUrgency = true, callbackRequest = clauses.any { it.callback && !it.negatedAction }, directMention = directMention, timeSensitive = true), 0.86f, false, "present_safety_concern")
    }

    val active = clauses.filter { !it.resolved && !it.historical && !it.hypothetical && !it.negatedAction }
    val requests = active.filter { it.action }
    // A leading standalone modifier belongs to the following request: 'Urgent, call me'.
    // It cannot revive cancelled, historical or explicitly nonurgent clauses.
    val prefixUrgency = document.clauses.firstOrNull()?.tokens?.let { it.isNotEmpty() && it.all { token -> token in urgencyWords } } == true
    val urgentRequests = requests.filter { (it.urgent || it.imminent || it.deadline || prefixUrgency) && !it.flexible }
    val timedChanges = active.filter { it.imminent && it.change }
    val anySuppressed = clauses.any { it.resolved || it.historical || it.negatedUrgency }
    val quotedAmbiguity = document.quoted && requests.isEmpty() && !clauses.any { it.resolved }

    val conflicted = clauses.indices.any { inactive ->
      (clauses[inactive].resolved || clauses[inactive].negatedAction) && clauses.indices.any { pending ->
        if (inactive == pending || !clauses[pending].action || clauses[pending].negatedAction) false
        else {
          val a = document.clauses[inactive].tokens
          val b = document.clauses[pending].tokens
          a.any { it in actionObjects && it in b } || (matches(a, callbacks).isNotEmpty() && matches(b, callbacks).isNotEmpty())
        }
      }
    }
    if (conflicted) return uncertain("conflicting_action_status")

    if (promotion) {
      // A transaction pitch can coexist with a real personal action. Mixed content is preserved.
      if (requests.any { !it.promotion } || timedChanges.isNotEmpty()) return uncertain("mixed_promotion_and_personal_context")
      return evidence(NotificationIntent.PROMOTION, UrgencySignals(), 0.82f, false, "commercial_solicitation")
    }

    if (urgentRequests.isNotEmpty()) {
      val contextMissing = shortReference && !concreteObject
      return evidence(
        NotificationIntent.ACTION_REQUIRED,
        UrgencySignals(
          explicitUrgency = prefixUrgency || urgentRequests.any { it.urgent },
          deadline = urgentRequests.any { it.deadline },
          directMention = directMention,
          callbackRequest = requests.any { it.callback },
          timeSensitive = prefixUrgency || urgentRequests.any { it.imminent || it.deadline || it.urgent }
        ),
        if (contextMissing) 0.48f else 0.82f,
        contextMissing,
        if (contextMissing) "action_object_needs_context" else "current_action_with_time_constraint",
        if (anySuppressed) "clauses_interpreted_separately" else "action_request"
      )
    }
    if (timedChanges.isNotEmpty()) {
      return evidence(NotificationIntent.TIME_SENSITIVE, UrgencySignals(timeSensitive = true, directMention = directMention), 0.77f, false, "imminent_event_change")
    }
    if (requests.isNotEmpty()) {
      // An ordinary request is not disposable merely because it lacks a deadline/urgency word.
      return evidence(NotificationIntent.ACTION_REQUIRED, UrgencySignals(callbackRequest = requests.any { it.callback }, directMention = directMention), 0.60f, true, if (requests.all { it.flexible }) "nonurgent_action_request" else "action_priority_needs_context", when {
        anySuppressed -> "clauses_interpreted_separately"
        tokens.any { it in historyWords } -> "past_or_negated_urgency"
        tokens.any { it in negationWords } -> "negation_scope_needs_context"
        else -> "action_request"
      })
    }
    if (clauses.any { it.resolved } && clauses.none { it.urgent && !it.resolved && !it.historical && !it.negatedUrgency }) {
      val ambiguous = shortReference || clauses.any { it.hypothetical || (!it.resolved && !it.historical && !it.negatedUrgency && !it.negatedAction) }
      return evidence(NotificationIntent.CANCELLED_OR_RESOLVED, UrgencySignals(), if (ambiguous) 0.45f else 0.82f, ambiguous, "cancellation_or_resolution")
    }
    if (clauses.all { it.historical || it.resolved || it.negatedUrgency } && anySuppressed) {
      return evidence(NotificationIntent.INFORMATIONAL, UrgencySignals(), 0.76f, false, "past_or_negated_urgency")
    }
    if (isExactAcknowledgment(tokens) && !document.quoted) {
      // 'Thanks' after a critical discussion need not mean that the danger or request is resolved.
      return evidence(NotificationIntent.INFORMATIONAL, UrgencySignals(), if (priorExists) 0.45f else 0.78f, priorExists, if (priorExists) "acknowledgment_requires_thread_context" else "routine_acknowledgment")
    }
    return uncertain(when {
      quotedAmbiguity -> "quoted_context_unverified"
      clauses.any { it.hypothetical } -> "hypothetical_context_unverified"
      tokens.any { it in negationWords } -> "negation_scope_needs_context"
      else -> "meaning_requires_semantic_model"
    })
  }

  private data class Clause(val tokens: List<String>, val question: Boolean, val conditionalContext: Boolean)
  private data class Document(val clauses: List<Clause>, val quoted: Boolean, val forwarded: Boolean, val unquotedText: String)
  private data class Span(val start: Int, val end: Int)
  private data class ClauseMeaning(
    val action: Boolean, val callback: Boolean, val urgent: Boolean, val deadline: Boolean,
    val imminent: Boolean, val safety: Boolean, val resolved: Boolean, val historical: Boolean,
    val hypothetical: Boolean, val promotion: Boolean, val flexible: Boolean,
    val negatedAction: Boolean, val negatedUrgency: Boolean, val change: Boolean, val question: Boolean,
    val lastBreathingDangerAt: Int?, val lastBreathingRecoveryAt: Int?, val otherDanger: Boolean,
    val safetySubjectNeedsContext: Boolean
  )

  private fun interpret(clause: Clause): ClauseMeaning {
    val tokens = clause.tokens
    val urgency = tokens.indices.filter { tokens[it] in urgencyWords }
    val negatedUrgency = urgency.isNotEmpty() && urgency.all { negated(tokens, it, 3) || trailingNegation(tokens, it) }
    val urgencyAsserted = urgency.any { !negated(tokens, it, 3) && !trailingNegation(tokens, it) }
    val actionPositions = tokens.indices.filter { tokens[it] in actionWords || (tokens[it] in setOf("needed", "required") && tokens.any { word -> word in actionObjects }) }
    val callbackSpans = matches(tokens, callbacks)
    val suppressedActions = actionPositions.filter { negated(tokens, it, 3) || hindiProhibition(tokens, it) || matches(tokens, noNeed).any { span -> span.end <= it && it - span.end <= 4 } }
    val negatedAction = actionPositions.isNotEmpty() && suppressedActions.size == actionPositions.size
    val action = actionPositions.any { it !in suppressedActions } || callbackSpans.any { !negated(tokens, it.start, 3) && !hindiProhibition(tokens, it.start) }
    val history = tokens.any { it in historyWords } || matches(tokens, pastPhrases).isNotEmpty()
    val current = tokens.any { it in currentWords }
    val pastContext = history && !current
    // A past event does not make a present-form request disposable. Keep that request
    // uncertain, while withholding urgency/safety claims drawn from the past event.
    val historical = pastContext && !action
    val urgent = urgencyAsserted && !pastContext
    val resolved = matches(tokens, resolutions).any { !negated(tokens, it.start, 3) } || matches(tokens, cancellations).any { !negated(tokens, it.start, 3) } || (negatedAction && (matches(tokens, noNeed).isNotEmpty() || tokens.any { it == "cancelled" || it == "canceled" || it == "cancel" }))
    val dangerSpans = matches(tokens, dangerousStates)
    val subjectScopes = dangerSpans.associateWith { subjectNegation(tokens, it.start) }
    val assertedDangers = dangerSpans.filter { span ->
      // 'not breathing' and 'can not breathe' are dangerous negative states. Negation *before*
      // the whole proposition ('not unconscious') denies it; the internal 'not' must survive.
      subjectScopes[span] == SubjectNegation.NONE && !negated(tokens, span.start, 3) && !trailingRecovery(tokens, span)
    }
    val respiratorySpans = matches(tokens, respiratoryStates)
    // A negative subject reverses a positive capability: nobody can breathe is danger,
    // while nobody is unconscious denies danger. Do not treat both as a generic 'no'.
    val capabilities = matches(tokens, breathingCapabilities)
    val quantifiedBreathingDanger = capabilities.filter { subjectNegation(tokens, it.start) == SubjectNegation.DENIED }
    val safetySubjectNeedsContext = subjectScopes.values.any { it != SubjectNegation.NONE } ||
      capabilities.any { subjectNegation(tokens, it.start) == SubjectNegation.UNCERTAIN }
    val lastBreathingDangerAt = (assertedDangers.filter { it in respiratorySpans } + quantifiedBreathingDanger).maxOfOrNull { it.start }
    val lastBreathingRecoveryAt = matches(tokens, breathingRecoveries)
      .filter { !negated(tokens, it.start, 3) && !trailingNegation(tokens, it.start) }.maxOfOrNull { it.start }
    val otherDanger = assertedDangers.any { it !in respiratorySpans } && !pastContext
    val danger = assertedDangers.isNotEmpty() || quantifiedBreathingDanger.isNotEmpty()
    val safetyAction = matches(tokens, safetyActions).any { !negated(tokens, it.start, 3) && !hindiProhibition(tokens, it.start) }
    val hypotheticalStarts = matches(tokens, NotificationMeaningAnalyzer.hypothetical).map { it.start } + tokens.indices.filter { tokens[it] in hypotheticalCueWords }
    val hypothetical = clause.conditionalContext || if (dangerSpans.isEmpty()) hypotheticalStarts.isNotEmpty() else hypotheticalStarts.any { it <= dangerSpans.minOf { span -> span.start } }
    val deadline = hasDeadline(tokens) && action && !negatedAction && !pastContext
    val imminent = !pastContext && (matches(tokens, imminence).any { !negated(tokens, it.start, 3) } || tokens.indices.any { tokens[it] in setOf("now", "abhi", "turant") && !negated(tokens, it, 3) && !trailingNegation(tokens, it) } || tokens.windowed(3).withIndex().any { (at, part) -> part[0] in setOf("in", "within", "agle") && part[1].toIntOrNull()?.let { n -> n in 1..60 } == true && part[2] in setOf("minute", "minutes", "min", "mins") && !negated(tokens, at, 3) })
    val promotion = matches(tokens, promotions).any { !negated(tokens, it.start, 3) } || (tokens.any { it in setOf("buy", "shop", "order", "claim") } && tokens.any { it in setOf("our", "offer", "sale", "discount", "coupon", "cashback", "prize", "product", "online") })
    val change = tokens.any { it in setOf("changed", "moved", "relocated", "rescheduled", "change", "badal", "badli") } && tokens.any { it in actionObjects || it in setOf("room", "hall", "venue", "location") }
    return ClauseMeaning(action, callbackSpans.isNotEmpty() && !negatedAction, urgent, deadline, imminent, (danger || safetyAction) && !pastContext, resolved, historical, hypothetical, promotion, matches(tokens, flexible).isNotEmpty(), negatedAction, negatedUrgency, change, clause.question,
      lastBreathingDangerAt.takeUnless { pastContext }, lastBreathingRecoveryAt.takeUnless { pastContext }, otherDanger, safetySubjectNeedsContext)
  }

  private fun hasDeadline(tokens: List<String>): Boolean {
    if (tokens.any { it == "deadline" || it == "due" }) return true
    if (tokens.any { it in setOf("before", "by", "until", "tak") } && tokens.any { clockToken.matches(it) || it in setOf("today", "tonight", "tomorrow", "noon", "midnight", "aaj", "kal", "raat", "viva", "exam", "meeting", "closes") }) return true
    return tokens.any { it in setOf("today", "tonight", "aaj") } && tokens.any { it in setOf("submit", "upload", "jama", "pay", "approve", "sign") }
  }

  private fun negated(tokens: List<String>, at: Int, window: Int): Boolean =
    (maxOf(0, at - window) until at).any { negative ->
      val urgencyScope = tokens[at] !in urgencyWords &&
        ((negative + 1 until at).any { tokens[it] in urgencyWords } || tokens.getOrNull(negative - 1) in urgencyWords)
      tokens[negative] in negationWords && tokens.getOrNull(negative + 1) != "only" && !urgencyScope
    }

  private fun trailingNegation(tokens: List<String>, at: Int): Boolean =
    (at + 1..minOf(tokens.lastIndex, at + 2)).any { tokens[it] in setOf("nahi", "nahin", "nhi", "not") }

  private enum class SubjectNegation { NONE, DENIED, UNCERTAIN }

  /** Bounded negative subject + optional location/adverb + predicate, never clause-wide 'no'. */
  private fun subjectNegation(tokens: List<String>, at: Int): SubjectNegation {
    val subject = matches(tokens, negativeSubjects).filter { it.end <= at }.maxByOrNull { it.end } ?: return SubjectNegation.NONE
    val bridge = tokens.subList(subject.end, at)
    if (bridge.any { it in setOf("said", "says", "think", "thinks", "believe", "believes") }) return SubjectNegation.UNCERTAIN
    // A new action/subject establishes a separate proposition, even without punctuation.
    val boundaries = setOf("i", "you", "he", "she", "they", "we", "and", "because", "while")
    if (bridge.any { it in actionWords || it in boundaries }) return SubjectNegation.NONE
    if (bridge.size > 12) return SubjectNegation.UNCERTAIN
    val copulas = setOf("is", "are", "was", "were", "be", "been")
    val modifiers = if (bridge.lastOrNull() in copulas) bridge.dropLast(1) else bridge
    val adverbs = setOf("here", "there", "now", "currently", "still", "actually", "really", "presently")
    if (modifiers.all { it in adverbs }) return SubjectNegation.DENIED
    val locations = setOf("in", "at", "inside", "outside", "near", "around", "from", "of")
    return if (modifiers.firstOrNull() in locations && modifiers.none { it in copulas || it in negationWords }) {
      SubjectNegation.DENIED
    } else SubjectNegation.UNCERTAIN
  }

  private fun hindiProhibition(tokens: List<String>, at: Int): Boolean =
    tokens.getOrNull(at + 1) == "mat" || tokens.getOrNull(at + 2) == "mat"

  private fun trailingRecovery(tokens: List<String>, span: Span): Boolean {
    val following = tokens.drop(span.end).take(4)
    // 'Cannot breathe anymore' / 'not breathing anymore' intensify a negative symptom;
    // 'anymore' does not negate the whole dangerous proposition.
    return following.take(2).any { it in setOf("resolved", "over") } || following.take(2) == listOf("no", "longer")
  }

  private fun isExactAcknowledgment(tokens: List<String>): Boolean = acknowledgments.any { it == tokens }

  private fun parse(raw: String): Document {
    val normalized = normalize(raw)
    val lines = normalized.lines()
    val forwarded = lines.any { it.trimStart().startsWith("forwarded") || it.trimStart().startsWith("fwd:") || it.trimStart().startsWith("forwarded message") }
    var quoted = false
    var quote: Char? = null
    val output = StringBuilder()
    for (line in lines) {
      if (line.trimStart().startsWith(">")) { quoted = true; output.append('\n'); continue }
      line.forEachIndexed { index, char ->
        val apostropheInWord = char == '\'' && line.getOrNull(index - 1)?.isLetter() == true && line.getOrNull(index + 1)?.isLetter() == true
        if (!apostropheInWord && char in setOf('"', '\'', '“', '”', '‘', '’', '`')) {
          quoted = true
          val closing = when (quote) { '“' -> '”'; '‘' -> '’'; else -> quote }
          if (quote == null) quote = char else if (char == closing) quote = null
          output.append(' ')
        } else if (quote == null) output.append(char)
      }
      output.append('\n')
    }
    val unquoted = output.toString()
    val clauses = mutableListOf<Clause>()
    for (sentence in unquoted.split(Regex("(?<=[?!])|[.\\n]+"))) {
      var conditionalContext = false
      for (segment in sentence.split(Regex("[,;]+"))) {
        for (part in adversative.split(segment)) {
          val tokens = tokenPattern.findAll(part).map { it.value }.toList()
          if (tokens.isNotEmpty()) {
            clauses.add(Clause(tokens, sentence.contains('?'), conditionalContext))
            // The instruction after 'if ..., call ...' is still conditional. Keep that
            // provenance across clause punctuation without spreading it to the next sentence.
            if (tokens.any { it in hypotheticalCueWords } || matches(tokens, hypothetical).isNotEmpty()) {
              conditionalContext = true
            }
          }
        }
      }
    }
    return Document(clauses, quoted, forwarded, unquoted)
  }

  private fun normalize(raw: String): String {
    var text = Normalizer.normalize(raw, Normalizer.Form.NFKC).lowercase(Locale.ROOT).replace('’', '\'').replace('‘', '\'')
    val contractions = mapOf("can't" to "can not", "cannot" to "can not", "don't" to "do not", "doesn't" to "does not", "didn't" to "did not", "isn't" to "is not", "aren't" to "are not", "wasn't" to "was not", "weren't" to "were not", "won't" to "will not", "needn't" to "need not")
    contractions.forEach { (from, to) -> text = text.replace(Regex("\\b${Regex.escape(from)}\\b"), to) }
    text = text.replace(Regex("\\b(today|tomorrow|yesterday)'s\\b"), "$1")
    // Preserve line boundaries for forwarded/reply attribution.
    return text.lines().joinToString("\n") { whitespace.replace(it, " ") }
  }

  private fun phrases(vararg values: String): List<List<String>> = values.map { it.split(' ') }

  private fun matches(tokens: List<String>, patterns: List<List<String>>): List<Span> = buildList {
    patterns.forEach { pattern ->
      if (pattern.size <= tokens.size) for (start in 0..tokens.size - pattern.size) {
        if (pattern.indices.all { tokens[start + it] == pattern[it] }) add(Span(start, start + pattern.size))
      }
    }
  }

  private fun uncertain(reason: String) = evidence(NotificationIntent.UNCERTAIN, UrgencySignals(), 0.2f, true, reason)

  private fun evidence(intent: NotificationIntent, urgency: UrgencySignals, confidence: Float, abstain: Boolean, vararg reasons: String): NotificationMeaningEvidence {
    val guarded = setOf("missing_message_content", "message_exceeds_analysis_window", "forwarded_context_unverified", "quoted_context_unverified", "thread_context_needed", "ambiguous_reference", "mixed_promotion_and_personal_context", "action_object_needs_context", "past_or_negated_urgency", "acknowledgment_requires_thread_context", "conflicting_action_status", "conflicting_safety_status", "safety_subject_scope_requires_context", "hypothetical_context_unverified", "negation_scope_needs_context", "clauses_interpreted_separately")
    return NotificationMeaningEvidence(intent, urgency, confidence, abstain, reasons.toList(), canRefineWithModel = intent !in setOf(NotificationIntent.SAFETY_CRITICAL, NotificationIntent.CANCELLED_OR_RESOLVED) && reasons.none { it in guarded })
  }
}
