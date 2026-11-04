package app.attune.attunenative.notification

import java.util.Locale

enum class SenderTier(val baseScore: Int) {
  FAMILY(42),
  CLOSE(34),
  CLASS(27),
  WORK(25),
  UNKNOWN(14)
}

enum class NotificationFeedback(val adjustment: Int) {
  NONE(0),
  OPENED(5),
  REPLIED(12),
  DISMISSED(-8),
  MARKED_IMPORTANT(22),
  MARKED_NOT_IMPORTANT(-24),
  FAMILY(0),
  CLOSE(0),
  CLASS(0),
  WORK(0);

  companion object {
    fun fromWire(value: String): NotificationFeedback? {
      val normalized = value.trim().replace('-', '_').replace(' ', '_')
      return values().firstOrNull { it.name.equals(normalized, ignoreCase = true) }
        ?: when (normalized.lowercase(Locale.ROOT)) {
          "important" -> MARKED_IMPORTANT
          "not_important", "notimportant" -> MARKED_NOT_IMPORTANT
          "family_contact" -> FAMILY
          "close_contact" -> CLOSE
          "classmate" -> CLASS
          "coworker" -> WORK
          else -> null
        }
    }
  }

  fun senderTierOverride(): SenderTier? = when (this) {
    FAMILY -> SenderTier.FAMILY
    CLOSE -> SenderTier.CLOSE
    CLASS -> SenderTier.CLASS
    WORK -> SenderTier.WORK
    else -> null
  }
}

data class UrgencySignals(
  val explicitUrgency: Boolean = false,
  val deadline: Boolean = false,
  val directMention: Boolean = false,
  val callbackRequest: Boolean = false,
  val timeSensitive: Boolean = false
) {
  val score: Int
    get() = listOf(
      explicitUrgency to 24,
      deadline to 18,
      directMention to 10,
      callbackRequest to 12,
      timeSensitive to 10
    ).filter { it.first }.sumOf { it.second }.coerceAtMost(50)

  val reasonCodes: List<String>
    get() = buildList {
      if (explicitUrgency) add("explicit_urgency")
      if (deadline) add("deadline")
      if (directMention) add("direct_mention")
      if (callbackRequest) add("callback_request")
      if (timeSensitive) add("time_sensitive")
    }
}

data class NotificationSignal(
  val senderTier: SenderTier,
  val urgency: UrgencySignals,
  val feedback: NotificationFeedback = NotificationFeedback.NONE,
  val meaning: NotificationMeaningEvidence? = null,
  val context: NotificationContextSignal = NotificationContextSignal()
)

/** Live context reduced to interruption evidence before it reaches ranking. */
data class NotificationContextSignal(
  val interruptibilityScore: Int? = null,
  val calendarBusy: Boolean = false,
  val reasonCodes: List<String> = emptyList()
) {
  val suppressing: Boolean
    get() = calendarBusy || (interruptibilityScore != null && interruptibilityScore <= 45)
}

data class NotificationScore(
  val points: Int,
  val basePoints: Int,
  val urgencyScore: Int,
  val reasonCodes: List<String>
)

class NotificationScorer {
  fun score(signal: NotificationSignal): NotificationScore {
    val senderTier = signal.feedback.senderTierOverride() ?: signal.senderTier
    val base = (senderTier.baseScore + signal.urgency.score).coerceIn(0, 100)
    val reasons = buildList {
      add("sender_${senderTier.name.lowercase(Locale.ROOT)}")
      addAll(signal.urgency.reasonCodes)
      when (signal.feedback) {
        NotificationFeedback.NONE -> Unit
        NotificationFeedback.OPENED -> add("previously_opened")
        NotificationFeedback.REPLIED -> add("previously_replied")
        NotificationFeedback.DISMISSED -> add("previously_dismissed")
        NotificationFeedback.MARKED_IMPORTANT -> add("marked_important")
        NotificationFeedback.MARKED_NOT_IMPORTANT -> add("marked_not_important")
        NotificationFeedback.FAMILY,
        NotificationFeedback.CLOSE,
        NotificationFeedback.CLASS,
        NotificationFeedback.WORK -> add("sender_tag_${signal.feedback.name.lowercase(Locale.ROOT)}")
      }
    }
    val meaning = signal.meaning
    val floor = if (meaning != null && !meaning.abstain) when (meaning.intent) {
      NotificationIntent.SAFETY_CRITICAL -> 90
      NotificationIntent.TIME_SENSITIVE -> 65
      NotificationIntent.ACTION_REQUIRED -> if (meaning.urgency.deadline || meaning.urgency.timeSensitive) 65 else 42
      else -> 0
    } else 0
    val semanticBase = maxOf(floor, base)
    val contextProtected = meaning != null && !meaning.abstain && when (meaning.intent) {
      NotificationIntent.SAFETY_CRITICAL,
      NotificationIntent.TIME_SENSITIVE -> true
      NotificationIntent.ACTION_REQUIRED -> meaning.urgency.deadline || meaning.urgency.timeSensitive
      else -> false
    }
    val contextAdjustment = when {
      contextProtected -> 0
      signal.context.suppressing -> -10
      signal.context.interruptibilityScore != null && signal.context.interruptibilityScore >= 75 -> 4
      else -> 0
    }
    val contextReasons = when {
      contextAdjustment < 0 -> listOf("current_context_protects_attention") + signal.context.reasonCodes.filter {
        it in setOf("calendar_busy", "foreground_meeting", "in_vehicle", "foreground_navigation", "headphones_connected")
      }
      contextAdjustment > 0 -> listOf("current_context_is_available")
      else -> emptyList()
    }
    val contextBase = (semanticBase + contextAdjustment).coerceIn(0, 100)
    return NotificationScore(
      points = maxOf(floor, (contextBase + signal.feedback.adjustment).coerceIn(0, 100)),
      basePoints = maxOf(floor, contextBase),
      urgencyScore = signal.urgency.score,
      reasonCodes = (reasons + meaning?.reasonCodes.orEmpty() + contextReasons).distinct()
    )
  }
}

/**
 * Resolves only explicit local sender/conversation tags. Without one, UNKNOWN is intentional:
 * response history can adjust a notification's score, but it cannot honestly identify family,
 * classmates, or work contacts by itself.
 */
object LocalSenderTierResolver {
  fun resolve(
    history: Iterable<NotificationRecord>,
    senderKey: String?,
    conversationKey: String?
  ): SenderTier {
    if (senderKey == null && conversationKey == null) return SenderTier.UNKNOWN

    val conversationMatch = history
      .filter { conversationKey != null && it.conversationKey == conversationKey }
      .sortedByDescending { it.postedAtMs }
      .mapNotNull { it.feedback.senderTierOverride() }
      .firstOrNull()
    if (conversationMatch != null) return conversationMatch

    return history
      .filter { senderKey != null && it.senderKey == senderKey }
      .sortedByDescending { it.postedAtMs }
      .mapNotNull { it.feedback.senderTierOverride() }
      .firstOrNull()
      ?: SenderTier.UNKNOWN
  }
}

/** Reuses the most recent explicit local response for the same conversation or sender. */
object LocalNotificationFeedbackResolver {
  fun resolve(
    history: Iterable<NotificationRecord>,
    senderKey: String?,
    conversationKey: String?,
    nowMs: Long? = null
  ): NotificationFeedback = resolveRecord(history, senderKey, conversationKey, nowMs)?.feedback ?: NotificationFeedback.NONE

  fun resolveRecord(
    history: Iterable<NotificationRecord>, senderKey: String?, conversationKey: String?, nowMs: Long? = null
  ): NotificationRecord? {
    if (senderKey == null && conversationKey == null) return null

    val matching = history
      .filter { record ->
        val sameContext = if (conversationKey != null) record.conversationKey == conversationKey
          else senderKey != null && record.senderKey == senderKey && record.conversationKey == null
        sameContext && (nowMs == null || nowMs - (record.feedbackAtMs ?: record.postedAtMs) in 0L..14L * 24 * 60 * 60 * 1000)
      }
      .sortedByDescending { it.postedAtMs }

    return matching
      .firstOrNull { it.feedback != NotificationFeedback.NONE && it.feedback.senderTierOverride() == null }
  }
}

object UrgencyDetector {
  /** Compatibility API: uses the same scoped current-message analysis as the live listener. */
  fun detect(text: CharSequence?): UrgencySignals = CollegeNotificationContext.refine(
    text?.toString().orEmpty(), NotificationMeaningAnalyzer.analyze(text)
  ).urgency
}
