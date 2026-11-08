package app.attune.attunenative.attention

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import app.attune.attunenative.AttuneEventHub
import app.attune.attunenative.history.NativeHistoryStore
import java.io.File
import java.util.Calendar

/** Owned by the sensor foreground service, so accounting continues with the React UI closed. */
class AttentionMeasurementRecorder(private val context: Context) {
  private val usage = AndroidUsageStatsSource(context)
  private val store = NativeHistoryStore(File(context.filesDir, "attune_history.json"))
  private val tracker = AttentionIntervalTracker()
  private val pending = mutableListOf<AttentionInterval>()
  private var lastFlushMs = 0L
  private var generation = NativeHistoryStore.attentionGeneration

  fun sample() {
    if (generation != NativeHistoryStore.attentionGeneration) {
      pending.clear()
      tracker.reset()
      generation = NativeHistoryStore.attentionGeneration
    }
    val now = System.currentTimeMillis()
    val elapsed = SystemClock.elapsedRealtime()
    val window = usage.window()
    val screen = screenUsable(context)
    val classification = currentClassification(context, window, usage.recentInteraction())
    val calendar = Calendar.getInstance().apply { timeInMillis = now }
    tracker.advance(
      AttentionTick(
        elapsed,
        now,
        localDayStart(now),
        screen && window.usageAccessGranted && window.status == "active_foreground",
        classification,
        foregroundPackage = window.topPackage,
        hourOfDay = calendar.get(Calendar.HOUR_OF_DAY)
      )
    )?.let { interval ->
      val last = pending.lastOrNull()
      if (last != null && last.dayStartMs == interval.dayStartMs &&
        last.classification == interval.classification &&
        last.foregroundPackage == interval.foregroundPackage &&
        last.hourOfDay == interval.hourOfDay
      ) {
        pending[pending.lastIndex] = last.copy(durationMs = last.durationMs + interval.durationMs)
      } else pending.add(interval)
    }
    if (elapsed - lastFlushMs >= 15_000L) {
      flush()
      val baseline = usage.dailyWindow(localDayStart(now), now)
      if (baseline.usageAccessGranted && baseline.status != "usage_stats_unavailable") {
        store.upsertAttentionSnapshot(localDayStart(now), baseline.foregroundMs, UsageStatsAttentionClassifier().classify(baseline))
      }
      lastFlushMs = elapsed
    }
    // Re-emitting camera evidence must preserve its capture time and expiry.
    val event = if (classification.source == "camera") CameraAttentionRuntime.event(elapsed, now)
      else classification.toMap() + mapOf("timestampMs" to now)
    AttuneEventHub.publish("onAttentionFrame", event)
  }

  fun reset() {
    flush()
    tracker.reset()
  }

  private fun flush() {
    val iterator = pending.iterator()
    var failed = false
    while (iterator.hasNext()) {
      val interval = iterator.next()
      val written = store.recordAttention(
        interval.dayStartMs,
        0L,
        interval.classification,
        interval.durationMs,
        generation,
        interval.foregroundPackage,
        interval.hourOfDay
      )
      if (written) iterator.remove() else failed = true
    }
    if (failed) {
      AttentionSensorRuntime.status = AttentionSensorRuntime.status.copy(error = "attention_history_write_failed")
    } else if (AttentionSensorRuntime.status.error == "attention_history_write_failed") {
      AttentionSensorRuntime.status = AttentionSensorRuntime.status.copy(error = null)
    }
  }

  companion object {
    fun localDayStart(nowMs: Long): Long = Calendar.getInstance().apply {
      timeInMillis = nowMs
      set(Calendar.HOUR_OF_DAY, 0)
      set(Calendar.MINUTE, 0)
      set(Calendar.SECOND, 0)
      set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun screenUsable(context: Context): Boolean {
      val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
      val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
      return power?.isInteractive == true && keyguard?.isKeyguardLocked != true
    }

    fun currentClassification(
      context: Context,
      window: UsageAttentionWindow = AndroidUsageStatsSource(context).window(),
      recentInteraction: Boolean = AndroidUsageStatsSource(context).recentInteraction()
    ): AttentionClassification {
      if (!screenUsable(context)) return AttentionClassification(AttentionLabel.NOT_IN_USE, 1f, "screen", "screen_off_or_locked")
      if (GazeCalibrationRuntime.active()) return AttentionClassification(AttentionLabel.UNKNOWN, 0f, "calibration", "gaze_calibration_in_progress")
      CameraAttentionRuntime.currentClassification()?.let { return it }
      if (AttentionSensorRuntime.status.running) {
        return SensorAttentionClassifier().classify(
          AttentionSensorRuntime.snapshot(),
          recentInteraction = recentInteraction,
          usageAccessGranted = window.usageAccessGranted,
          foregroundMs = if (window.status == "active_foreground") window.foregroundMs else 0L
        )
      }
      return UsageStatsAttentionClassifier().classify(window)
    }
  }
}
