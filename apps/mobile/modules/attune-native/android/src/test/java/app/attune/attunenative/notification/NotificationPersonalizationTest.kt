package app.attune.attunenative.notification

import org.junit.Assert.*
import org.junit.Test

class NotificationPersonalizationTest {
  @Test fun savedImportanceCanRaiseARecognizedCollegeRequestAboveTheAlertThreshold() {
    val meaning = NotificationMeaningEvidence(NotificationIntent.ACTION_REQUIRED, UrgencySignals(), .8f,
      false, listOf("action_request"), "on_device_semantic_and_context")
    val ordinary = NotificationScorer().score(NotificationSignal(SenderTier.UNKNOWN, meaning.urgency, meaning = meaning))
    val important = NotificationScorer().score(NotificationSignal(SenderTier.UNKNOWN, meaning.urgency,
      NotificationFeedback.MARKED_IMPORTANT, meaning))
    assertEquals(42, ordinary.points)
    assertEquals(64, important.points)
    val record = NotificationRecord("request", "com.chat", 100L, SenderTier.UNKNOWN, 0,
      ordinary.basePoints, ordinary.points, ordinary.reasonCodes, semanticIntent = meaning.intent.name,
      semanticSource = meaning.source, semanticConfidence = meaning.confidence)
    assertEquals(important.points, record.withFeedback(NotificationFeedback.MARKED_IMPORTANT, 200L).score)
  }

  @Test fun sourceNotificationUpdatesCannotRenewAnOldPreferenceTimestamp() {
    val now = 1_800_000_000_000L
    val oldFeedback = now - 15L * 24 * 60 * 60 * 1000
    val updated = NotificationRecord("same-source-key", "com.chat", now, SenderTier.UNKNOWN, 0, 14, 14,
      emptyList(), feedback = NotificationFeedback.MARKED_NOT_IMPORTANT, conversationKey = "college-group",
      feedbackAtMs = oldFeedback)
    assertEquals(NotificationFeedback.NONE, LocalNotificationFeedbackResolver.resolve(listOf(updated), null, "college-group", now))
    val explicitNewTap = updated.withFeedback(NotificationFeedback.MARKED_IMPORTANT, now)
    assertEquals(NotificationFeedback.MARKED_IMPORTANT, LocalNotificationFeedbackResolver.resolve(listOf(explicitNewTap), null, "college-group", now))
  }
}
