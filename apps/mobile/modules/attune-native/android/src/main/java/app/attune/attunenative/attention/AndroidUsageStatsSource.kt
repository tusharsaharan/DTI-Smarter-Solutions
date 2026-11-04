package app.attune.attunenative.attention

import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.PowerManager
import app.attune.attunenative.context.AndroidContextSnapshotProvider

class AndroidUsageStatsSource(private val context: Context) {
  private val contextProvider = AndroidContextSnapshotProvider(context)
  private val manager get() = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
  private var sessionCachedAtMs = 0L
  private var sessionCachedDurationMs = 0L
  private var sessionCache: UsageAttentionWindow? = null
  private var dailyCachedAtMs = 0L
  private var dailyCachedStartMs = -1L
  private var dailyCache: UsageAttentionWindow? = null
  private var timelineStartMs = Long.MAX_VALUE
  private var timelineEndMs = 0L
  private var timelineCache = emptyList<UsageTimelineEvent>()

  /** Current foreground session only. Historical aggregate usage cannot prove current activity. */
  @Synchronized fun window(durationMs: Long = 60 * 60 * 1000L): UsageAttentionWindow {
    if (!contextProvider.hasUsageStatsAccess()) return UsageAttentionWindow(0L, false, null)
    if (!isInteractive()) {
      sessionCache = null
      return UsageAttentionWindow(0L, true, null, "screen_off_or_locked")
    }
    val usageManager = manager ?: return unavailable()
    val now = System.currentTimeMillis()
    sessionCache?.takeIf { sessionCachedDurationMs == durationMs && now - sessionCachedAtMs in 0L..SESSION_CACHE_MS }?.let { return it }
    val start = (now - durationMs.coerceIn(1L, LOOKBACK_MS)).coerceAtLeast(0L)
    val events = cachedEvents(usageManager, (start - LOOKBACK_MS).coerceAtLeast(0L), now) ?: return unavailable()
    val current = UsageTimelineReducer.reduce(events, start, now)
    val pkg = current.currentPackage
    val since = current.currentSinceMs
    val result = if (pkg != null && since != null) {
      UsageAttentionWindow((now - maxOf(start, since)).coerceAtLeast(0L), true, pkg, "active_foreground")
    } else UsageAttentionWindow(0L, true, null, "no_foreground")
    sessionCachedAtMs = now
    sessionCachedDurationMs = durationMs
    sessionCache = result
    return result
  }

  /** Daily union of foreground app intervals, excluding screen-off/locked time. */
  @Synchronized fun dailyWindow(startMs: Long, endMs: Long): UsageAttentionWindow {
    if (!contextProvider.hasUsageStatsAccess()) return UsageAttentionWindow(0L, false, null)
    if (startMs < 0L || endMs <= startMs) return UsageAttentionWindow(0L, true, null, "no_foreground")
    val usageManager = manager ?: return unavailable()
    dailyCache?.takeIf { dailyCachedStartMs == startMs && endMs - dailyCachedAtMs in 0L..DAILY_CACHE_MS }?.let { return it }
    // Carry an already-resumed app across local midnight. Only overlap with the day is counted.
    val events = cachedEvents(usageManager, (startMs - LOOKBACK_MS).coerceAtLeast(0L), endMs)
    if (events != null && events.any { it.kind == UsageTimelineKind.RESUMED || it.kind == UsageTimelineKind.PAUSED }) {
      val daily = UsageTimelineReducer.reduce(events, startMs, endMs)
      val result = UsageAttentionWindow(
        daily.foregroundMs, true,
        daily.currentPackage ?: daily.perPackageMs.maxByOrNull { it.value }?.key,
        "daily_events"
      )
      dailyCache = result
      dailyCachedAtMs = endMs
      dailyCachedStartMs = startMs
      return result
    }
    // Android can expand aggregate buckets beyond query bounds; mark this lower-fidelity fallback.
    val stats = runCatching { usageManager.queryAndAggregateUsageStats(startMs, endMs) }.getOrNull()
      ?: return unavailable()
    val total = stats.values.sumOf { it.totalTimeInForeground.coerceAtLeast(0L) }.coerceAtMost(endMs - startMs)
    return UsageAttentionWindow(total, true, stats.maxByOrNull { it.value.totalTimeInForeground }?.key, "aggregate_window")
  }

  @Synchronized fun recentInteraction(nowMs: Long = System.currentTimeMillis(), maxAgeMs: Long = 5_000L): Boolean {
    if (!contextProvider.hasUsageStatsAccess() || !isInteractive()) return false
    val usageManager = manager ?: return false
    val age = maxAgeMs.coerceIn(0L, 60_000L)
    val events = cachedEvents(usageManager, (nowMs - age).coerceAtLeast(0L), nowMs) ?: return false
    return UsageTimelineReducer.hasRecentInteraction(events, nowMs, age)
  }

  private fun isInteractive(): Boolean = runCatching {
    val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    power?.isInteractive == true && keyguard?.isKeyguardLocked != true
  }.getOrDefault(false)

  /** Only the first snapshot scans history. Later samples query the small new event interval. */
  private fun cachedEvents(usageManager: UsageStatsManager, startMs: Long, endMs: Long): List<UsageTimelineEvent>? {
    val canAppend = timelineStartMs <= startMs && endMs >= timelineEndMs && timelineEndMs > 0L
    if (canAppend && endMs - timelineEndMs < 1_000L) return timelineCache
    // Usage reporting may arrive after its event timestamp; overlap catches delayed
    // screen/lifecycle boundaries without rescanning the whole day.
    val queryStart = if (canAppend) maxOf(timelineStartMs, (timelineEndMs - 10_000L).coerceAtLeast(0L)) else startMs
    val added = readEvents(usageManager, queryStart, endMs) ?: return null
    val retainFromMs = (endMs - 3L * LOOKBACK_MS).coerceAtLeast(0L)
    timelineCache = (if (canAppend) (timelineCache + added).distinct() else added)
      .filter { it.timestampMs >= retainFromMs }
    timelineStartMs = maxOf(if (canAppend) timelineStartMs else startMs, retainFromMs)
    timelineEndMs = endMs
    return timelineCache
  }

  private fun readEvents(usageManager: UsageStatsManager, startMs: Long, endMs: Long): List<UsageTimelineEvent>? =
    runCatching<List<UsageTimelineEvent>?> {
      val events = usageManager.queryEvents(startMs, endMs) ?: return@runCatching null
      val event = UsageEvents.Event()
      buildList<UsageTimelineEvent> {
        while (events.hasNextEvent()) {
          events.getNextEvent(event)
          val kind = when (event.eventType) {
            // MOVE_TO_FOREGROUND/BACKGROUND are aliases of RESUMED/PAUSED (1/2).
            UsageEvents.Event.ACTIVITY_RESUMED -> UsageTimelineKind.RESUMED
            UsageEvents.Event.ACTIVITY_PAUSED -> UsageTimelineKind.PAUSED
            UsageEvents.Event.ACTIVITY_STOPPED -> UsageTimelineKind.STOPPED
            UsageEvents.Event.SCREEN_INTERACTIVE -> UsageTimelineKind.SCREEN_ON
            UsageEvents.Event.SCREEN_NON_INTERACTIVE -> UsageTimelineKind.SCREEN_OFF
            UsageEvents.Event.KEYGUARD_SHOWN -> UsageTimelineKind.LOCKED
            UsageEvents.Event.KEYGUARD_HIDDEN -> UsageTimelineKind.UNLOCKED
            UsageEvents.Event.DEVICE_SHUTDOWN -> UsageTimelineKind.SHUTDOWN
            UsageEvents.Event.DEVICE_STARTUP -> UsageTimelineKind.STARTUP
            UsageEvents.Event.USER_INTERACTION -> UsageTimelineKind.INTERACTION
            else -> null
          }
          if (kind != null) add(UsageTimelineEvent(event.timeStamp, kind, event.packageName, event.className))
        }
      }
    }.getOrNull()

  private fun unavailable() = UsageAttentionWindow(0L, true, null, "usage_stats_unavailable")

  private companion object {
    const val LOOKBACK_MS = 24L * 60 * 60 * 1000
    const val SESSION_CACHE_MS = 3_000L
    const val DAILY_CACHE_MS = 15_000L
  }
}
