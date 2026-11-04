package app.attune.attunenative.attention

enum class UsageTimelineKind {
  RESUMED, PAUSED, STOPPED, SCREEN_ON, SCREEN_OFF, UNLOCKED, LOCKED, SHUTDOWN, STARTUP, INTERACTION
}

data class UsageTimelineEvent(
  val timestampMs: Long,
  val kind: UsageTimelineKind,
  val packageName: String? = null,
  val activityName: String? = null
)

data class UsageTimelineWindow(
  val foregroundMs: Long,
  val perPackageMs: Map<String, Long>,
  val currentPackage: String?,
  val currentSinceMs: Long?
)

/** Integrates event intervals once, clipped to the requested window; it never infers gaze. */
object UsageTimelineReducer {
  fun reduce(events: Iterable<UsageTimelineEvent>, startMs: Long, endMs: Long): UsageTimelineWindow {
    if (startMs < 0L || endMs <= startMs) return UsageTimelineWindow(0L, emptyMap(), null, null)
    var screenOn = true
    var unlocked = true
    var currentPackage: String? = null
    var currentActivity: String? = null
    var currentSinceMs: Long? = null
    var cursor = startMs
    val totals = linkedMapOf<String, Long>()

    fun accumulate(untilMs: Long) {
      val until = untilMs.coerceIn(startMs, endMs)
      val duration = (until - cursor).coerceAtLeast(0L)
      val pkg = currentPackage
      if (screenOn && unlocked && pkg != null && duration > 0L) {
        totals[pkg] = (totals[pkg] ?: 0L) + duration
      }
      cursor = maxOf(cursor, until)
    }

    fun clearForeground() {
      currentPackage = null
      currentActivity = null
      currentSinceMs = null
    }

    events.asSequence().filter { it.timestampMs >= 0L && it.timestampMs < endMs }
      .sortedBy { it.timestampMs }.forEach { event ->
        accumulate(event.timestampMs)
        when (event.kind) {
          UsageTimelineKind.RESUMED -> {
            val pkg = event.packageName?.takeUnless { it.isBlank() }
            if (pkg != null) {
              if (currentPackage != pkg) currentSinceMs = event.timestampMs
              currentPackage = pkg
              currentActivity = event.activityName
            }
          }
          UsageTimelineKind.PAUSED -> {
            // A pause from another Activity must not end its replacement's foreground time.
            if (currentPackage == event.packageName &&
              (event.activityName == null || currentActivity == null || currentActivity == event.activityName)) {
              clearForeground()
            }
          }
          UsageTimelineKind.SCREEN_OFF -> { screenOn = false; clearForeground() }
          UsageTimelineKind.SCREEN_ON -> {
            screenOn = true
            if (currentPackage != null) currentSinceMs = event.timestampMs
          }
          UsageTimelineKind.LOCKED -> { unlocked = false; clearForeground() }
          UsageTimelineKind.UNLOCKED -> {
            unlocked = true
            if (currentPackage != null) currentSinceMs = event.timestampMs
          }
          UsageTimelineKind.SHUTDOWN, UsageTimelineKind.STARTUP -> { screenOn = false; clearForeground() }
          // onStop can arrive after a same-class replacement's onResume (e.g. rotation).
          // UsageEvents exposes no public instance ID; onPause is the foreground boundary.
          UsageTimelineKind.STOPPED, UsageTimelineKind.INTERACTION -> Unit
        }
      }
    accumulate(endMs)
    return UsageTimelineWindow(
      totals.values.sum(), totals,
      currentPackage.takeIf { screenOn && unlocked }, currentSinceMs.takeIf { screenOn && unlocked }
    )
  }

  /** A recent resume is interaction evidence, never proof of the user's eye direction. */
  fun hasRecentInteraction(events: Iterable<UsageTimelineEvent>, nowMs: Long, maxAgeMs: Long): Boolean {
    if (maxAgeMs < 0L || nowMs < 0L) return false
    var latestInteraction: Long? = null
    events.asSequence().filter { it.timestampMs in (nowMs - maxAgeMs).coerceAtLeast(0L)..nowMs }
      .sortedBy { it.timestampMs }.forEach { event ->
        when (event.kind) {
          UsageTimelineKind.RESUMED, UsageTimelineKind.INTERACTION ->
            if (!event.packageName.isNullOrBlank()) latestInteraction = event.timestampMs
          UsageTimelineKind.SCREEN_OFF, UsageTimelineKind.LOCKED,
          UsageTimelineKind.SHUTDOWN, UsageTimelineKind.STARTUP -> latestInteraction = null
          else -> Unit
        }
      }
    return latestInteraction != null
  }
}
