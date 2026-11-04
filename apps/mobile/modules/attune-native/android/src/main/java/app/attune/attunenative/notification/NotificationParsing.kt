package app.attune.attunenative.notification

import android.app.Notification
import android.app.Person
import android.os.Build
import android.os.Bundle

/**
 * A single message extracted from Android's MessagingStyle extras.
 *
 * The text fields in this model are deliberately short lived. They are used by the listener to
 * score and repost the notification, and are not included in NotificationRecord or event maps.
 */
data class ParsedNotificationMessage(
  val text: String,
  val senderName: String?,
  val senderIdentity: String?,
  val timestampMs: Long?
)

/** Android-independent view of notification extras used by the pure parsing rules. */
data class NotificationExtrasSnapshot(
  val title: String? = null,
  val text: String? = null,
  val bigText: String? = null,
  val conversationTitle: String? = null,
  val isGroupConversation: Boolean? = null,
  val template: String? = null,
  val messages: List<ParsedNotificationMessage> = emptyList()
)

data class ParsedNotification(
  val title: String,
  val text: String,
  val bigText: String?,
  val conversationTitle: String?,
  val isGroupConversation: Boolean?,
  val shortcutId: String?,
  val messages: List<ParsedNotificationMessage>,
  val isMessage: Boolean,
  val senderIdentity: String?,
  val senderKey: String?,
  val conversationKey: String?,
  /** Current body only: title, group name and earlier messages must not add urgency. */
  val textForScoring: String,
  val displayTitle: String,
  val displayText: String,
  /** Short-lived, chronological preceding messages; never include this in history/event maps. */
  val conversationContext: List<ParsedNotificationMessage> = emptyList()
) {
  val latestTextForScoring: String
    get() = textForScoring

  val hasDisplayContent: Boolean
    get() = title.isNotBlank() ||
      text.isNotBlank() ||
      !bigText.isNullOrBlank() ||
      !conversationTitle.isNullOrBlank() ||
      messages.isNotEmpty()

  val latestMessage: ParsedNotificationMessage?
    get() = messages.lastOrNull()
}

/** Reads notification metadata without retaining the source Notification object. */
object NotificationParser {
  private const val KEY_MESSAGE_TEXT = "text"
  private const val KEY_MESSAGE_TIMESTAMP = "time"
  private const val KEY_MESSAGE_SENDER = "sender"
  private const val KEY_MESSAGE_SENDER_PERSON = "sender_person"
  private const val KEY_IS_GROUP_CONVERSATION = "android.isGroupConversation"
  private const val MAX_CONTEXT_MESSAGES = 6
  private const val MAX_CONTEXT_MESSAGE_CHARS = 512
  private const val MAX_CONTEXT_CHARS = 2_048
  private const val MAX_CONTEXT_AGE_MS = 15L * 60 * 1_000
  private const val MAX_FUTURE_SKEW_MS = 5L * 60 * 1_000

  fun parse(
    notification: Notification,
    packageName: String? = null,
    receivedAtMs: Long = System.currentTimeMillis()
  ): ParsedNotification {
    val extras = notification.extras ?: Bundle()
    val shortcutId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      notification.shortcutId
    } else {
      null
    }
    return parseExtras(
      extras = extras,
      packageName = packageName,
      shortcutId = shortcutId,
      receivedAtMs = receivedAtMs
    )
  }

  /**
   * Converts Android Bundle extras to the platform-independent parser input. Keeping this bridge
   * thin makes the notification rules JVM-testable without inventing external notifications.
   */
  fun parseExtras(
    extras: Bundle,
    packageName: String? = null,
    shortcutId: String? = null,
    receivedAtMs: Long = System.currentTimeMillis()
  ): ParsedNotification {
    return parseSnapshot(
      snapshot = NotificationExtrasSnapshot(
        title = extras.charSequenceOrNull(Notification.EXTRA_TITLE),
        text = extras.charSequenceOrNull(Notification.EXTRA_TEXT),
        bigText = extras.charSequenceOrNull(Notification.EXTRA_BIG_TEXT),
        conversationTitle = extras.charSequenceOrNull(Notification.EXTRA_CONVERSATION_TITLE),
        isGroupConversation = extras.booleanOrNull(KEY_IS_GROUP_CONVERSATION),
        template = extras.stringOrNull(Notification.EXTRA_TEMPLATE),
        messages = readMessages(extras)
      ),
      packageName = packageName,
      shortcutId = shortcutId,
      receivedAtMs = receivedAtMs
    )
  }

  fun parseSnapshot(
    snapshot: NotificationExtrasSnapshot,
    packageName: String? = null,
    shortcutId: String? = null,
    receivedAtMs: Long? = null
  ): ParsedNotification {
    val title = snapshot.title.orEmpty()
    val text = snapshot.text.orEmpty()
    val bigText = snapshot.bigText
    val conversationTitle = snapshot.conversationTitle
    val isGroupConversation = snapshot.isGroupConversation
    val messages = orderedMessages(snapshot.messages, receivedAtMs)
    val template = snapshot.template.orEmpty()
    val isMessage = messages.isNotEmpty() ||
      template.contains("MessagingStyle", ignoreCase = true) ||
      messages.any { it.senderIdentity != null }
    val latestMessage = messages.lastOrNull()
    // A missing sender on the new message must not inherit a different author's local tier.
    val senderIdentity = latestMessage?.senderIdentity?.takeIf { it.isNotBlank() }
    val senderKey = senderIdentity?.let { identityKey("sender", packageName, it) }
    val conversationMaterial = shortcutId?.takeIf { it.isNotBlank() }
      ?: conversationTitle?.takeIf { it.isNotBlank() }
      ?: if (isMessage && isGroupConversation == true && title.isNotBlank()) "group:$title" else null
    val conversationKey = conversationMaterial?.let { identityKey("conversation", packageName, it) }
    // EXTRA_TEXT is preferable to an expanded summary which can include the entire thread.
    // An attachment/empty newest message deliberately stays empty instead of reviving old text.
    val textForScoring = latestMessage?.text
      ?: text.takeIf { it.isNotBlank() }
      ?: bigText.orEmpty()
    val conversationContext = precedingContext(messages, receivedAtMs)
    val displayTitle = conversationTitle?.takeIf { it.isNotBlank() }
      ?: title.takeIf { it.isNotBlank() }
      ?: latestMessage?.senderName?.takeIf { it.isNotBlank() }
      ?: packageName.orEmpty()
    val displayText = latestMessage?.text?.takeIf { it.isNotBlank() }
      ?: bigText?.takeIf { it.isNotBlank() }
      ?: text

    return ParsedNotification(
      title = title,
      text = text,
      bigText = bigText,
      conversationTitle = conversationTitle,
      isGroupConversation = isGroupConversation,
      shortcutId = shortcutId,
      messages = messages,
      isMessage = isMessage,
      senderIdentity = senderIdentity,
      senderKey = senderKey,
      conversationKey = conversationKey,
      textForScoring = textForScoring,
      displayTitle = displayTitle,
      displayText = displayText,
      conversationContext = conversationContext
    )
  }

  private fun orderedMessages(
    source: List<ParsedNotificationMessage>,
    receivedAtMs: Long?
  ): List<ParsedNotificationMessage> {
    val reference = receivedAtMs?.takeIf { it > 0L }
    val normalized = source.map { message ->
      val time = message.timestampMs?.takeIf { value ->
        value > 0L && (reference == null || value <= reference || value - reference <= MAX_FUTURE_SKEW_MS)
      }
      message.copy(
        timestampMs = time,
        senderName = message.senderName?.takeIf { it.isNotBlank() },
        senderIdentity = message.senderIdentity?.takeIf { it.isNotBlank() }
      )
    }.withIndex().toList()
    // Timestamp ties retain the source order. Undated entries never invent chronological recency.
    val dated = normalized.filter { it.value.timestampMs != null }
      .sortedWith(compareBy<IndexedValue<ParsedNotificationMessage>> { it.value.timestampMs }.thenBy { it.index })
    // MessagingStyle source order is the only available ordering for an undated final entry.
    // Keeping it current is safer than scoring an old dated urgent message in its place.
    val newest = normalized.lastOrNull()?.takeIf { it.value.timestampMs == null }
      ?: dated.lastOrNull()
      ?: return emptyList()
    val unknown = normalized.filter { it.value.timestampMs == null && it.index != newest.index }
    return (unknown + dated.filter { it.index != newest.index } + newest).map { it.value }
  }

  private fun precedingContext(
    messages: List<ParsedNotificationMessage>,
    receivedAtMs: Long?
  ): List<ParsedNotificationMessage> {
    val latestAt = messages.lastOrNull()?.timestampMs ?: return emptyList()
    val reference = receivedAtMs?.takeIf { it > 0L } ?: latestAt
    val candidates = messages.dropLast(1).filter { message ->
      val at = message.timestampMs
      at != null && at <= latestAt && latestAt - at <= MAX_CONTEXT_AGE_MS &&
        (at >= reference || reference - at <= MAX_CONTEXT_AGE_MS) && message.text.isNotBlank()
    }.takeLast(MAX_CONTEXT_MESSAGES)
    var remaining = MAX_CONTEXT_CHARS
    // Allocate the budget newest-first so the nearest reply context survives long older messages.
    return candidates.asReversed().mapNotNull { message ->
      if (remaining == 0) return@mapNotNull null
      val boundedText = message.text.take(minOf(MAX_CONTEXT_MESSAGE_CHARS, remaining))
      remaining -= boundedText.length
      message.copy(text = boundedText)
    }.asReversed()
  }

  private fun readMessages(extras: Bundle): List<ParsedNotificationMessage> {
    val rawMessages = runCatching {
      extras.getParcelableArray(Notification.EXTRA_MESSAGES)
    }.getOrNull() ?: return emptyList()

    return rawMessages.mapNotNull { rawMessage ->
      val bundle = rawMessage as? Bundle ?: return@mapNotNull null
      // Keep textless attachment messages: dropping the latest one would rescore older content.
      val messageText = bundle.charSequenceOrNull(KEY_MESSAGE_TEXT).orEmpty()
      val person = readPerson(bundle)
      val legacySender = bundle.charSequenceOrNull(KEY_MESSAGE_SENDER)?.takeIf { it.isNotBlank() }
      val senderName = person?.name?.toString()?.takeIf { it.isNotBlank() } ?: legacySender
      val senderIdentity = person?.uri?.takeIf { it.isNotBlank() }
        ?: person?.key?.takeIf { it.isNotBlank() }
        ?: senderName
      val timestamp = runCatching {
        if (bundle.containsKey(KEY_MESSAGE_TIMESTAMP)) bundle.getLong(KEY_MESSAGE_TIMESTAMP) else null
      }.getOrNull()
      ParsedNotificationMessage(
        text = messageText,
        senderName = senderName,
        senderIdentity = senderIdentity,
        timestampMs = timestamp
      )
    }
  }

  private fun readPerson(bundle: Bundle): Person? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
    return runCatching {
      bundle.getParcelable(KEY_MESSAGE_SENDER_PERSON) as? Person
    }.getOrNull() ?: runCatching {
      bundle.getParcelable(KEY_MESSAGE_SENDER) as? Person
    }.getOrNull()
  }

  private fun identityKey(kind: String, packageName: String?, value: String): String {
    return stableNotificationId("$kind|${packageName.orEmpty()}|$value")
  }

  private fun Bundle.charSequenceOrNull(key: String): String? = runCatching {
    getCharSequence(key)?.toString()
  }.getOrNull()

  private fun Bundle.stringOrNull(key: String): String? = runCatching {
    getString(key)
  }.getOrNull()

  private fun Bundle.booleanOrNull(key: String): Boolean? = runCatching {
    if (containsKey(key)) getBoolean(key) else null
  }.getOrNull()
}
