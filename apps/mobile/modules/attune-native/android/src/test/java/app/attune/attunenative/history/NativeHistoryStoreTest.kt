package app.attune.attunenative.history

import app.attune.attunenative.notification.NotificationRecord
import app.attune.attunenative.notification.SenderTier
import app.attune.attunenative.attention.AttentionClassification
import app.attune.attunenative.attention.AttentionLabel
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeHistoryStoreTest {
  @Test
  fun clearingAttentionInvalidatesBufferedSamplesAcrossStoreInstances() {
    val file = tempFile()
    try {
      val store = NativeHistoryStore(file, nowMs = { 100_000L })
      val generation = NativeHistoryStore.attentionGeneration
      val gaze = AttentionClassification(AttentionLabel.ATTENDED, .8f, "camera", "on_screen")
      store.recordAttention(0L, 0L, gaze, 1000L, generation)
      NativeHistoryStore(file, nowMs = { 100_000L }).clearAttention()
      assertFalse(store.recordAttention(0L, 0L, gaze, 1000L, generation))
      assertTrue(store.attentionHistory().isEmpty())
    } finally { deleteTree(file) }
  }

  @Test
  fun liveAttentionNeverRelabelsTheDailyBaselineOrDuplicatesOnRefresh() {
    val file = tempFile()
    try {
      val store = NativeHistoryStore(file, nowMs = { 100_000L })
      val usage = AttentionClassification(AttentionLabel.USAGE_ONLY, .3f, "usage_stats", "daily")
      val gaze = AttentionClassification(AttentionLabel.ATTENDED, .8f, "camera", "on_screen")
      store.upsertAttentionSnapshot(0L, 60_000L, usage)
      store.recordAttention(0L, 0L, gaze, 2_000L)
      repeat(3) { store.upsertAttentionSnapshot(0L, 60_000L, gaze) }
      val day = store.attentionHistory().single()
      assertEquals(60_000L, day.foregroundMs)
      assertEquals(2_000L, day.attendedMs)
      assertEquals(58_000L, day.usageOnlyMs)
      assertEquals("camera", day.source)
    } finally { deleteTree(file) }
  }

  @Test
  fun sensorEstimateDoesNotTurnMixedHistoryIntoCameraMeasurement() {
    val file = tempFile()
    try {
      val store = NativeHistoryStore(file, nowMs = { 100_000L })
      store.recordAttention(0L, 0L, AttentionClassification(AttentionLabel.ATTENDED, .8f, "camera", "on_screen"), 1_000L)
      store.recordAttention(0L, 0L, AttentionClassification(AttentionLabel.ATTENDED, .4f, "sensor_fusion", "interaction"), 1_000L)
      store.upsertAttentionSnapshot(0L, 30_000L, AttentionClassification(AttentionLabel.USAGE_ONLY, .3f, "usage_stats", "baseline"))
      assertEquals("mixed", store.attentionHistory().single().source)
      assertEquals(2_000L, store.attentionHistory().single().attendedMs)
    } finally { deleteTree(file) }
  }

  @Test
  fun retentionPrunesByAgeAndRowLimit() {
    val file = tempFile()
    var now = 1_000L
    val store = NativeHistoryStore(
      file = file,
      retention = HistoryRetention(
        ambientMaxRows = 2,
        ambientRetentionMs = 100L
      ),
      nowMs = { now }
    )

    try {
      assertTrue(store.recordAmbient(850L, 45f, false, 3, null, "old", null, "microphone", null))
      assertTrue(store.recordAmbient(950L, 55f, true, 5, 0.4f, "newer", null, "microphone", null))
      assertTrue(store.recordAmbient(1_000L, 65f, true, 7, 0.6f, "newest", null, "microphone", null))

      assertEquals(listOf(1_000L, 950L), store.ambientSamples().map { it.atMs })
      now = 1_100L
      assertEquals(listOf(1_000L), store.ambientSamples().map { it.atMs })
    } finally {
      deleteTree(file)
    }
  }

  @Test
  fun malformedHistoryReadsAsEmptyAndCanRecover() {
    val file = tempFile()
    try {
      file.writeText("{ definitely not valid history")

      val store = NativeHistoryStore(file, nowMs = { 10L })
      assertTrue(store.ambientSamples().isEmpty())
      assertTrue(store.recordAmbient(10L, 42f, false, 2, null, "within_hysteresis", null, "microphone", null))
      assertEquals(1, store.ambientSamples().size)
      assertFalse(file.readText().contains("definitely not valid"))
    } finally {
      deleteTree(file)
    }
  }

  @Test
  fun clearRemovesOnlyRequestedCategoryAndClearAllRemovesStore() {
    val file = tempFile()
    try {
      val store = NativeHistoryStore(file, nowMs = { 10L })
      store.upsertNotification(notification("notification-1", 10L))
      store.recordAmbient(10L, 42f, false, 2, null, "within_hysteresis", null, "microphone", null)
      store.upsertContext(
        ContextHistoryRecord("context-1", 10L, "LIMITED", 50, false, listOf("still"), emptyMap())
      )

      assertTrue(store.clearAmbient())
      assertTrue(store.ambientSamples().isEmpty())
      assertEquals(1, store.notificationRecords().size)
      assertEquals(1, store.contextHistory().size)
      assertTrue(store.deleteNotification("notification-1"))
      assertTrue(store.notificationRecords().isEmpty())
      assertTrue(store.clearAll())
      assertTrue(store.notificationRecords().isEmpty())
      assertFalse(file.exists())
    } finally {
      deleteTree(file)
    }
  }

  @Test
  fun notificationUpsertDeduplicatesAcrossInstancesAndNeverPersistsDisplayText() {
    val file = tempFile()
    try {
      val first = NativeHistoryStore(file, nowMs = { 20L })
      first.upsertNotification(notification("same", 10L).copy(text = "private notification text"))

      val second = NativeHistoryStore(file, nowMs = { 20L })
      second.upsertNotification(notification("same", 20L).copy(score = 80))

      val restored = NativeHistoryStore(file, nowMs = { 20L }).notificationRecords()
      assertEquals(1, restored.size)
      assertEquals(20L, restored.single().postedAtMs)
      assertEquals(80, restored.single().score)
      assertNull(restored.single().text)
      assertFalse(file.readText().contains("private notification text"))
    } finally {
      deleteTree(file)
    }
  }

  private fun notification(id: String, postedAtMs: Long): NotificationRecord = NotificationRecord(
    id = id,
    packageName = "com.example.chat",
    postedAtMs = postedAtMs,
    senderTier = SenderTier.UNKNOWN,
    urgencyScore = 0,
    baseScore = SenderTier.UNKNOWN.baseScore,
    score = SenderTier.UNKNOWN.baseScore,
    reasonCodes = listOf("sender_unknown"),
    sender = "Asha",
    conversation = "Weekend plans",
    text = "display text"
  )

  private fun tempFile(): File {
    val file = File.createTempFile("attune-history", ".json")
    file.delete()
    return file
  }

  private fun deleteTree(file: File) {
    file.delete()
    file.parentFile?.listFiles { candidate ->
      candidate.name.startsWith("${file.name}.tmp") || candidate.name.startsWith("${file.name}.corrupt.")
    }?.forEach { it.delete() }
  }
}
