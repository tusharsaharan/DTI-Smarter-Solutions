package app.attune.attunenative.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationParsingContextTest {
  private val now = 1_800_000_000_000L

  private fun message(text: String, at: Long? = now, sender: String? = "Asha") =
    ParsedNotificationMessage(text, sender, sender, at)

  private fun parse(
    messages: List<ParsedNotificationMessage>,
    title: String = "Class group",
    text: String? = null,
    bigText: String? = null,
    receivedAtMs: Long? = now
  ) = NotificationParser.parseSnapshot(
    NotificationExtrasSnapshot(
      title = title,
      text = text,
      bigText = bigText,
      conversationTitle = title,
      isGroupConversation = true,
      template = "android.app.Notification\$MessagingStyle",
      messages = messages
    ),
    packageName = "com.chat",
    receivedAtMs = receivedAtMs
  )

  @Test
  fun oldUrgentRequestDoesNotPoisonTheCurrentCancellation() {
    val parsed = parse(
      listOf(
        message("Urgent, call me now", now - 60_000L, "Raj"),
        message("All sorted, no need to call", now, "Asha")
      ),
      title = "Urgent exam today",
      text = "Urgent, call me now",
      bigText = "Raj: Urgent, call me now\nAsha: All sorted, no need to call"
    )

    assertEquals("All sorted, no need to call", parsed.latestTextForScoring)
    assertEquals(parsed.latestTextForScoring, parsed.textForScoring)
    assertEquals("Urgent, call me now", parsed.conversationContext.single().text)
    assertEquals("Asha", parsed.senderIdentity)
    assertFalse(parsed.latestTextForScoring.contains("Urgent"))
  }

  @Test
  fun reorderedBundlesUseTheMostRecentTimestampRatherThanTheLastArrayPosition() {
    val parsed = parse(listOf(
      message("It is cancelled", now, "Riya"),
      message("Deadline today, urgent", now - 40_000L, "Prince"),
      message("See the notice", now - 20_000L, "Raj")
    ))

    assertEquals("It is cancelled", parsed.latestTextForScoring)
    assertEquals("Riya", parsed.latestMessage?.senderIdentity)
    assertEquals(listOf(now - 40_000L, now - 20_000L, now), parsed.messages.map { it.timestampMs })
    assertEquals(listOf("Prince", "Raj"), parsed.conversationContext.map { it.senderName })
  }

  @Test
  fun timestampTiesKeepTheirSourceOrder() {
    val parsed = parse(listOf(
      message("first", now), message("second", now), message("last", now)
    ))

    assertEquals(listOf("first", "second", "last"), parsed.messages.map { it.text })
    assertEquals("last", parsed.latestTextForScoring)
    assertEquals(listOf("first", "second"), parsed.conversationContext.map { it.text })
  }

  @Test
  fun shortFollowupHasSeparateRecentThreadContextWithItsAuthorAndTime() {
    val parsed = parse(listOf(
      message("Can you call me before 5pm?", now - 15_000L, "Aman"),
      message("please?", now, "Aman")
    ))

    assertEquals("please?", parsed.latestTextForScoring)
    val context = parsed.conversationContext.single()
    assertEquals("Can you call me before 5pm?", context.text)
    assertEquals("Aman", context.senderName)
    assertEquals("Aman", context.senderIdentity)
    assertEquals(now - 15_000L, context.timestampMs)
  }

  @Test
  fun contextDiscardsOldMessagesAndKeepsTheLatestEvenIfItIsOld() {
    val parsed = parse(listOf(
      message("Urgent, exam today", now - 16 * 60_000L),
      message("okay", now)
    ))
    assertTrue(parsed.conversationContext.isEmpty())
    assertEquals("okay", parsed.latestTextForScoring)

    val staleBundle = parse(listOf(
      message("Urgent, exam today", now - 62 * 60_000L),
      message("It is cancelled", now - 61 * 60_000L)
    ))
    assertEquals("It is cancelled", staleBundle.latestTextForScoring)
    assertTrue(staleBundle.conversationContext.isEmpty())
  }

  @Test
  fun contextIsBoundedAndNearestRepliesKeepTheTextBudget() {
    val parsed = parse((0..20).map { index ->
      message("$index:" + "x".repeat(1_000), now - (20 - index) * 1_000L)
    })

    assertTrue(parsed.conversationContext.size <= 6)
    assertTrue(parsed.conversationContext.all { it.text.length <= 512 })
    assertEquals(2_048, parsed.conversationContext.sumOf { it.text.length })
    assertTrue(parsed.conversationContext.last().text.startsWith("19:"))
    assertFalse(parsed.conversationContext.any { it.text.startsWith("20:") })
    assertTrue(parsed.latestTextForScoring.startsWith("20:"))
  }

  @Test
  fun evenTinyMessagesHaveABoundedContextCount() {
    val parsed = parse((0..20).map { index -> message("$index", now - (20 - index) * 1_000L) })
    assertEquals(listOf("14", "15", "16", "17", "18", "19"), parsed.conversationContext.map { it.text })
  }

  @Test
  fun unknownAndInvalidTimestampsNeverFabricateRecency() {
    val parsed = parse(listOf(
      message("Urgent, call me", null),
      message("another old request", -1L),
      message("thanks", 0L)
    ))
    assertEquals("thanks", parsed.latestTextForScoring)
    assertTrue(parsed.messages.all { it.timestampMs == null })
    assertTrue(parsed.conversationContext.isEmpty())

    val mixed = parse(listOf(message("Old urgent request", now - 1_000L), message("cancelled", null)))
    assertEquals("cancelled", mixed.latestTextForScoring)
    assertTrue(mixed.conversationContext.isEmpty())
  }

  @Test
  fun datedMessagesRemainChronologicalAndUndatedEarlierEntriesCannotOverrideThem() {
    val parsed = parse(listOf(
      message("old untrusted urgent text", null),
      message("newest", now),
      message("earlier", now - 1_000L)
    ))
    assertEquals("newest", parsed.latestTextForScoring)
    assertEquals(listOf("earlier"), parsed.conversationContext.map { it.text })
  }

  @Test
  fun absurdFutureTimestampDoesNotProvideRecencyOrDisplaceADatedLatestMessage() {
    val parsed = parse(listOf(
      message("Urgent", Long.MAX_VALUE),
      message("No action needed", now)
    ))
    assertEquals("No action needed", parsed.latestTextForScoring)
    assertEquals(null, parsed.messages.first().timestampMs)
    assertTrue(parsed.conversationContext.isEmpty())
  }

  @Test
  fun blankLatestAttachmentDoesNotReviveOldUrgentTextOrPreviousSender() {
    val parsed = parse(
      listOf(message("Urgent, call me", now - 1_000L, "Asha"), message("", now, null)),
      text = "Urgent, call me",
      bigText = "Urgent, call me"
    )
    assertEquals("", parsed.latestTextForScoring)
    assertEquals(null, parsed.senderIdentity)
    assertEquals(null, parsed.senderKey)
    assertTrue(parsed.hasDisplayContent)
  }

  @Test
  fun blankLatestSenderAndConversationMetadataCannotCreateASharedUnknownIdentity() {
    val parsed = NotificationParser.parseSnapshot(
      NotificationExtrasSnapshot(
        title = "",
        conversationTitle = " ",
        isGroupConversation = true,
        messages = listOf(
          message("Urgent", now - 1_000L, "Asha"),
          message("", now, " ")
        )
      ),
      shortcutId = "",
      receivedAtMs = now
    )
    assertEquals("", parsed.latestTextForScoring)
    assertEquals(null, parsed.latestMessage?.senderName)
    assertEquals(null, parsed.senderIdentity)
    assertEquals(null, parsed.senderKey)
    assertEquals(null, parsed.conversationKey)
  }

  @Test
  fun missingMessagingExtrasUseCurrentBodyWithoutScoringTitleOrExpandedHistory() {
    val parsed = parse(emptyList(), title = "Urgent exam by 5pm", text = "Photo shared", bigText = "Urgent: call me now")
    assertEquals("Photo shared", parsed.latestTextForScoring)
    assertTrue(parsed.isMessage)
    assertTrue(parsed.conversationContext.isEmpty())
    assertEquals("Urgent exam by 5pm", parsed.displayTitle)
    assertEquals("Urgent: call me now", parsed.displayText)
  }

  @Test
  fun bodylessNotificationsDoNotUseNamesAsTextAndFallbacksStillDisplay() {
    val titleOnly = parse(emptyList(), title = "Emergency response @class now")
    assertEquals("", titleOnly.latestTextForScoring)
    assertTrue(titleOnly.hasDisplayContent)
    assertEquals("Emergency response @class now", titleOnly.displayTitle)

    val empty = NotificationParser.parseSnapshot(NotificationExtrasSnapshot(), packageName = "com.chat")
    assertEquals("", empty.latestTextForScoring)
    assertEquals("com.chat", empty.displayTitle)
    assertFalse(empty.hasDisplayContent)

    val expandedOnly = NotificationParser.parseSnapshot(NotificationExtrasSnapshot(bigText = "Download finished"))
    assertEquals("Download finished", expandedOnly.latestTextForScoring)
    assertEquals("Download finished", expandedOnly.displayText)
  }

  @Test
  fun pureSnapshotWithoutReceiptTimeUsesOnlyTheRelativeDatedThreadWindow() {
    val parsed = parse(
      listOf(message("A recent request", 100_000L), message("please?", 101_000L)),
      receivedAtMs = null
    )
    assertEquals("please?", parsed.latestTextForScoring)
    assertEquals("A recent request", parsed.conversationContext.single().text)
  }
}
