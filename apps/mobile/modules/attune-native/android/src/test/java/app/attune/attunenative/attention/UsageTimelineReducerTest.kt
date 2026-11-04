package app.attune.attunenative.attention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageTimelineReducerTest {
  private fun event(at: Long, kind: UsageTimelineKind, pkg: String? = null, activity: String? = null) =
    UsageTimelineEvent(at, kind, pkg, activity)

  @Test fun clipsCarryInAtMidnightAndIntegratesAllAppsRatherThanLastSession() {
    val result = UsageTimelineReducer.reduce(listOf(
      event(50, UsageTimelineKind.RESUMED, "first"),
      event(200, UsageTimelineKind.PAUSED, "first"),
      event(250, UsageTimelineKind.RESUMED, "second"),
      event(400, UsageTimelineKind.PAUSED, "second")
    ), 100, 500)
    assertEquals(250L, result.foregroundMs)
    assertEquals(mapOf("first" to 100L, "second" to 150L), result.perPackageMs)
    assertEquals(null, result.currentPackage)
  }

  @Test fun screenOffAndLockEndIntervalsEvenWithoutAppPause() {
    val result = UsageTimelineReducer.reduce(listOf(
      event(100, UsageTimelineKind.RESUMED, "app"),
      event(200, UsageTimelineKind.SCREEN_OFF),
      event(300, UsageTimelineKind.SCREEN_ON),
      event(400, UsageTimelineKind.RESUMED, "app"),
      event(500, UsageTimelineKind.LOCKED),
      event(600, UsageTimelineKind.RESUMED, "lockscreen"),
      event(650, UsageTimelineKind.PAUSED, "lockscreen"),
      event(700, UsageTimelineKind.UNLOCKED),
      event(800, UsageTimelineKind.RESUMED, "app")
    ), 0, 1000)
    assertEquals(400L, result.foregroundMs)
    assertEquals("app", result.currentPackage)
    assertEquals(800L, result.currentSinceMs)
  }

  @Test fun delayedPauseOfOldActivityDoesNotEraseReplacementActivity() {
    val result = UsageTimelineReducer.reduce(listOf(
      event(100, UsageTimelineKind.RESUMED, "app", "first"),
      event(200, UsageTimelineKind.RESUMED, "app", "second"),
      event(210, UsageTimelineKind.PAUSED, "app", "first"),
      event(250, UsageTimelineKind.STOPPED, "app", "first")
    ), 0, 400)
    assertEquals(300L, result.foregroundMs)
    assertEquals(100L, result.currentSinceMs)
  }

  @Test fun overlapAndDuplicateEventsNeverDoubleCountTime() {
    val result = UsageTimelineReducer.reduce(listOf(
      event(100, UsageTimelineKind.RESUMED, "first"),
      event(100, UsageTimelineKind.RESUMED, "first"),
      event(200, UsageTimelineKind.RESUMED, "second"),
      event(250, UsageTimelineKind.PAUSED, "first"),
      event(500, UsageTimelineKind.PAUSED, "second")
    ), 0, 400)
    assertEquals(300L, result.foregroundMs)
    assertEquals(mapOf("first" to 100L, "second" to 200L), result.perPackageMs)
  }

  @Test fun appResumeImmediatelyBeforeUnlockStartsCountingAtUnlock() {
    val result = UsageTimelineReducer.reduce(listOf(
      event(100, UsageTimelineKind.LOCKED),
      event(200, UsageTimelineKind.RESUMED, "app"),
      event(250, UsageTimelineKind.UNLOCKED)
    ), 0, 400)
    assertEquals(150L, result.foregroundMs)
    assertEquals(250L, result.currentSinceMs)
  }

  @Test fun delayedStopAfterSameClassRecreationDoesNotEraseNewSession() {
    val result = UsageTimelineReducer.reduce(listOf(
      event(100, UsageTimelineKind.RESUMED, "app", "Main"),
      event(200, UsageTimelineKind.PAUSED, "app", "Main"),
      event(210, UsageTimelineKind.RESUMED, "app", "Main"),
      event(220, UsageTimelineKind.STOPPED, "app", "Main")
    ), 0, 400)
    assertEquals(290L, result.foregroundMs)
    assertEquals("app", result.currentPackage)
  }

  @Test fun shutdownPreventsCountingTheGapUntilANewForeground() {
    val result = UsageTimelineReducer.reduce(listOf(
      event(50, UsageTimelineKind.RESUMED, "app"),
      event(100, UsageTimelineKind.SHUTDOWN),
      event(300, UsageTimelineKind.STARTUP),
      event(400, UsageTimelineKind.SCREEN_ON),
      event(450, UsageTimelineKind.RESUMED, "app")
    ), 0, 500)
    assertEquals(100L, result.foregroundMs)
  }

  @Test fun ignoresFutureAndNegativeEventsAndRejectsInvalidWindows() {
    assertEquals(0L, UsageTimelineReducer.reduce(listOf(event(-1, UsageTimelineKind.RESUMED, "app")), 0, 100).foregroundMs)
    assertEquals(0L, UsageTimelineReducer.reduce(listOf(event(100, UsageTimelineKind.RESUMED, "app")), 0, 100).foregroundMs)
    assertEquals(0L, UsageTimelineReducer.reduce(emptyList(), 100, 10).foregroundMs)
  }

  @Test fun interactionMustBeRecentAndCannotSurviveScreenOff() {
    assertTrue(UsageTimelineReducer.hasRecentInteraction(listOf(event(900, UsageTimelineKind.INTERACTION, "app")), 1000, 200))
    assertFalse(UsageTimelineReducer.hasRecentInteraction(listOf(event(700, UsageTimelineKind.INTERACTION, "app")), 1000, 200))
    assertFalse(UsageTimelineReducer.hasRecentInteraction(listOf(event(1100, UsageTimelineKind.INTERACTION, "app")), 1000, 200))
    assertFalse(UsageTimelineReducer.hasRecentInteraction(listOf(
      event(900, UsageTimelineKind.INTERACTION, "app"), event(950, UsageTimelineKind.SCREEN_OFF)
    ), 1000, 200))
  }
}
